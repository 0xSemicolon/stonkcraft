package dev.hypercraft.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hypercraft.HyperCraft;
import dev.hypercraft.hl.HlFormat;
import dev.hypercraft.hl.HyperliquidApi;
import dev.hypercraft.trading.PaperAccount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * Background poller for mids and asset metadata, plus a short per-coin price history. Covers Hyperliquid's main dex
 * (perps and USDC spot pairs), the xyz HIP-3 dex (stocks, indices, commodities: "xyz:GOLD"), and HyperCraft's own
 * Unit tokens: spot assets backed by in-game metal and priced off a reference perp ("UGOLD/USDC" at xyz:GOLD's mid).
 */
public final class MarketData implements PaperAccount.Quotes {
	/** Samples kept per coin; at 1 poll/second this is 15 minutes, the max sensor window. */
	public static final int HISTORY = 900;
	/** A mark price older than this has stopped being news: fall back to the mid. */
	private static final long MARK_STALE_MS = 30_000;

	/** HIP-3 dexes loaded alongside the main one. */
	public static final String[] EXTRA_DEXES = {"xyz"};
	/** {@link Asset#dex} of Unit tokens. */
	public static final String UNIT_DEX = "unit";

	public static boolean isExtraDex(String dex) {
		for (String d : EXTRA_DEXES) if (d.equals(dex)) return true;
		return false;
	}

	/**
	 * A tradable market. Perps are named by coin ("BTC", or "xyz:GOLD" on a HIP-3 dex); spot pairs as "BASE/USDC"
	 * with {@code apiCoin} being the exchange's key for mids and candles ("@107"). Spot asset ids are 10000 + the
	 * spot index. {@code dex} is "" for the main dex, "xyz" for HIP-3 markets, "unit" for Unit tokens.
	 */
	public record Asset(String name, int index, int szDecimals, int maxLeverage, boolean spot, String apiCoin, String base, String dex) {
		public Asset(String name, int index, int szDecimals, int maxLeverage) {
			this(name, index, szDecimals, maxLeverage, false, name, name, "");
		}

		public Asset(String name, int index, int szDecimals, int maxLeverage, boolean spot, String apiCoin, String base) {
			this(name, index, szDecimals, maxLeverage, spot, apiCoin, base, "");
		}

		/** A Unit token: HyperCraft-only spot asset backed by bridged metal. */
		public boolean unit() {
			return UNIT_DEX.equals(dex);
		}

		/** Price rounded to the exchange's tick rules for this market. */
		public double roundPrice(double px) {
			return spot ? HlFormat.roundSpotPrice(px, szDecimals) : HlFormat.roundPerpPrice(px, szDecimals);
		}
	}

	public static final class Ticker {
		public final Asset asset;
		public volatile double mid = Double.NaN;
		public volatile double prevDay = Double.NaN;
		public volatile double funding;
		/** Hyperliquid's own mark and oracle prices, from the last poll; NaN until the first (and on spot). */
		public volatile double mark = Double.NaN;
		public volatile double oracle = Double.NaN;
		/** When {@link #mark} was read: past {@link #MARK_STALE_MS} the live mid is the better price. */
		public volatile long markAt;
		public volatile double volume;
		public volatile double openInterest;
		private final long[] times = new long[HISTORY];
		private final double[] prices = new double[HISTORY];
		private int head;
		private int count;

		Ticker(Asset asset) {
			this.asset = asset;
		}

		synchronized void record(long now, double price) {
			times[head] = now;
			prices[head] = price;
			head = (head + 1) % HISTORY;
			if (count < HISTORY) count++;
		}

		/** Oldest recorded price within the last windowMs, or NaN if there is no history yet. */
		public synchronized double priceAgo(long windowMs) {
			if (count == 0) return Double.NaN;
			long cutoff = System.currentTimeMillis() - windowMs;
			double result = Double.NaN;
			for (int i = 1; i <= count; i++) {
				int idx = Math.floorMod(head - i, HISTORY);
				if (times[idx] < cutoff) break;
				result = prices[idx];
			}
			return result;
		}

		/** Percent change over the window; 0 until there is enough history. */
		public double changePct(long windowMs) {
			double then = priceAgo(windowMs);
			double now = mid;
			if (Double.isNaN(then) || Double.isNaN(now) || then == 0) return 0;
			return (now - then) / then * 100.0;
		}

		public double dayChangePct() {
			if (Double.isNaN(prevDay) || prevDay == 0 || Double.isNaN(mid)) return 0;
			return (mid - prevDay) / prevDay * 100.0;
		}
	}

	private final HyperliquidApi api;
	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "HyperCraft-Market");
		t.setDaemon(true);
		return t;
	});
	private final Map<String, Ticker> tickers = new ConcurrentHashMap<>();
	private final Map<String, String> lowerToName = new ConcurrentHashMap<>();
	private final Map<String, String> apiToName = new ConcurrentHashMap<>();
	private volatile long lastMidsUpdate;
	private volatile String lastError;

	/** Live feed (null when disabled); REST polling remains as the fallback. */
	@Nullable
	private dev.hypercraft.hl.HlSocket socket;
	private volatile long lastFeedMids;
	private volatile long lastRestMids;
	/** Books something is looking at, by market name -> last access. Subscribed while watched. */
	private final Map<String, Long> bookWatchers = new ConcurrentHashMap<>();

	/** Unit tokens to list: token name -> reference perp. */
	private volatile Map<String, String> units = Map.of();

	public MarketData(HyperliquidApi api) {
		this.api = api;
	}

	/** Lists Unit tokens ("UGOLD" priced off "xyz:GOLD"); takes effect on the next meta poll. */
	public void setUnits(Map<String, String> tokenToMarket) {
		units = new java.util.LinkedHashMap<>(tokenToMarket);
	}

	@Nullable
	public dev.hypercraft.hl.HlSocket socket() {
		return socket;
	}

	/** True while prices are arriving over the feed. */
	public boolean feedLive() {
		return socket != null && socket.connected() && System.currentTimeMillis() - lastFeedMids < 5_000;
	}

	public HyperliquidApi api() {
		return api;
	}

	public void start(int pollMillis) {
		start(pollMillis, null);
	}

	/** @param apiBase when non-null, prices and watched books come over the WebSocket feed at this API host */
	public void start(int pollMillis, @Nullable String apiBase) {
		exec.scheduleWithFixedDelay(this::pollMeta, 0, 15, TimeUnit.SECONDS);
		exec.scheduleWithFixedDelay(this::pollMids, 500, Math.max(250, pollMillis), TimeUnit.MILLISECONDS);
		if (apiBase != null) {
			socket = new dev.hypercraft.hl.HlSocket(apiBase, exec);
			socket.subscribe(dev.hypercraft.hl.HlSocket.sub("allMids", null, null), data -> {
				applyMids(data.getAsJsonObject("mids"));
				lastFeedMids = System.currentTimeMillis();
			});
			for (String dex : EXTRA_DEXES) socket.subscribe(dev.hypercraft.hl.HlSocket.subDex("allMids", dex), data -> applyMids(data.getAsJsonObject("mids")));
			socket.start();
			exec.scheduleWithFixedDelay(this::dropIdleBooks, 10, 10, TimeUnit.SECONDS);
		}
	}

	public void stop() {
		if (socket != null) socket.stop();
		exec.shutdownNow();
	}

	private void applyMids(JsonObject mids) {
		long now = System.currentTimeMillis();
		for (Ticker t : tickers.values()) {
			JsonElement px = mids.get(t.asset.apiCoin());
			if (px == null) continue;
			double v = px.getAsDouble();
			t.mid = v;
			t.record(now, v);
		}
		lastMidsUpdate = now;
		lastError = null;
	}

	private void dropIdleBooks() {
		long now = System.currentTimeMillis();
		bookWatchers.entrySet().removeIf(e -> {
			if (now - e.getValue() < 30_000) return false;
			Ticker t = tickers.get(e.getKey());
			if (socket != null && t != null) socket.unsubscribe(dev.hypercraft.hl.HlSocket.sub("l2Book", t.asset.apiCoin(), null));
			return true;
		});
	}

	private void pollMeta() {
		pollPerps("", 0);
		for (int d = 0; d < EXTRA_DEXES.length; d++) pollPerps(EXTRA_DEXES[d], d + 1);
		pollSpotMeta();
		refreshUnits();
	}

	/**
	 * Perps of one dex. HIP-3 asset ids are 100000 + dex number * 10000 + index. A bare ticker also resolves to a HIP-3
	 * market ("gold" -> "xyz:GOLD") unless a main-dex coin has that name.
	 */
	private void pollPerps(String dex, int dexNo) {
		try {
			JsonArray arr = (dex.isEmpty() ? api.info("metaAndAssetCtxs") : api.info(Map.of("type", "metaAndAssetCtxs", "dex", dex)))
					.get(10, TimeUnit.SECONDS).getAsJsonArray();
			JsonArray universe = arr.get(0).getAsJsonObject().getAsJsonArray("universe");
			JsonArray ctxs = arr.get(1).getAsJsonArray();
			int base = dex.isEmpty() ? 0 : 100_000 + dexNo * 10_000;
			for (int i = 0; i < universe.size(); i++) {
				JsonObject u = universe.get(i).getAsJsonObject();
				String name = u.get("name").getAsString();
				if (u.has("isDelisted") && u.get("isDelisted").getAsBoolean()) {
					tickers.remove(name);
					continue;
				}
				Ticker t = tickers.get(name);
				if (t == null || t.asset.index != base + i) {
					String bare = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
					t = new Ticker(new Asset(name, base + i, u.get("szDecimals").getAsInt(), u.get("maxLeverage").getAsInt(), false, name, bare, dex));
					tickers.put(name, t);
					lowerToName.put(name.toLowerCase(Locale.ROOT), name);
					if (!dex.isEmpty()) aliasBare(bare, name);
					// "pepe" finds kPEPE (Hyperliquid lists some memecoins per thousand)
					if (dex.isEmpty() && name.length() > 1 && name.charAt(0) == 'k' && Character.isUpperCase(name.charAt(1))) {
						lowerToName.putIfAbsent(name.substring(1).toLowerCase(Locale.ROOT), name);
					}
					apiToName.put(name, name);
				}
				if (i < ctxs.size()) {
					JsonObject c = ctxs.get(i).getAsJsonObject();
					t.prevDay = num(c, "prevDayPx");
					t.funding = num(c, "funding");
					t.mark = num(c, "markPx");
					t.oracle = num(c, "oraclePx");
					t.markAt = System.currentTimeMillis();
					t.volume = num(c, "dayNtlVlm");
					t.openInterest = num(c, "openInterest");
					if (Double.isNaN(t.mid)) t.mid = num(c, "midPx");
				}
			}
		} catch (Exception e) {
			fail(dex.isEmpty() ? "meta" : dex + " meta", e);
		}
	}

	/** "gold" -> "xyz:GOLD", unless the bare name already means a main-dex market or a Unit token. */
	private void aliasBare(String bare, String name) {
		String alias = bare.toLowerCase(Locale.ROOT);
		String held = lowerToName.get(alias);
		Ticker heldBy = held == null ? null : tickers.get(held);
		if (heldBy == null || heldBy.asset.name().equals(name) || !heldBy.asset.dex().isEmpty() && !heldBy.asset.unit()) lowerToName.put(alias, name);
	}

	/** Unit tokens track their reference perp: same mid, same previous-day price. */
	private void refreshUnits() {
		int k = 0;
		for (Map.Entry<String, String> e : units.entrySet()) {
			k++;
			Ticker ref = tickers.get(e.getValue());
			String name = e.getKey() + "/USDC";
			if (ref == null) continue;
			Ticker t = tickers.get(name);
			if (t == null || !t.asset.apiCoin().equals(ref.asset.name())) {
				t = new Ticker(new Asset(name, 90_000 + k, 4, 1, true, ref.asset.name(), e.getKey(), UNIT_DEX));
				tickers.put(name, t);
				lowerToName.put(name.toLowerCase(Locale.ROOT), name);
				lowerToName.put(e.getKey().toLowerCase(Locale.ROOT), name);
			}
			t.prevDay = ref.prevDay;
			if (Double.isNaN(t.mid)) t.mid = ref.mid;
		}
	}

	/** Puts a market in by hand, as if the exchange listed it. For tests. */
	public void putForTest(Asset asset, double mid) {
		Ticker t = new Ticker(asset);
		t.mid = mid;
		tickers.put(asset.name(), t);
		lowerToName.put(asset.name().toLowerCase(Locale.ROOT), asset.name());
		if (!asset.dex().isEmpty() && !asset.unit()) aliasBare(asset.base(), asset.name());
		String n = asset.name();
		if (asset.dex().isEmpty() && n.length() > 1 && n.charAt(0) == 'k' && Character.isUpperCase(n.charAt(1))) lowerToName.putIfAbsent(n.substring(1).toLowerCase(Locale.ROOT), n);
		apiToName.put(asset.apiCoin(), asset.name());
	}

	/** Runs the Unit-token refresh now. For tests. */
	public void refreshUnitsForTest() {
		refreshUnits();
	}

	/** USDC-quoted spot pairs. */
	private void pollSpotMeta() {
		try {
			JsonArray arr = api.info("spotMetaAndAssetCtxs").get(10, TimeUnit.SECONDS).getAsJsonArray();
			JsonObject meta = arr.get(0).getAsJsonObject();
			JsonArray ctxs = arr.get(1).getAsJsonArray();
			Map<Integer, JsonObject> tokens = new java.util.HashMap<>();
			for (JsonElement t : meta.getAsJsonArray("tokens")) tokens.put(t.getAsJsonObject().get("index").getAsInt(), t.getAsJsonObject());
			JsonArray universe = meta.getAsJsonArray("universe");
			for (int i = 0; i < universe.size(); i++) {
				JsonObject u = universe.get(i).getAsJsonObject();
				JsonArray pair = u.getAsJsonArray("tokens");
				if (pair.get(1).getAsInt() != 0) continue; // USDC quote only
				JsonObject base = tokens.get(pair.get(0).getAsInt());
				if (base == null) continue;
				String baseName = base.get("name").getAsString();
				String name = baseName + "/USDC";
				int spotIndex = u.get("index").getAsInt();
				String apiCoin = u.get("name").getAsString();
				JsonObject c = i < ctxs.size() ? ctxs.get(i).getAsJsonObject() : new JsonObject();
				double volume = num(c, "dayNtlVlm");
				Ticker existing = tickers.get(name);
				// several pairs can share a base token name; keep the most traded one
				if (existing != null && existing.asset.index() != 10000 + spotIndex && existing.volume >= volume) continue;
				Ticker t = existing;
				if (t == null || t.asset.index() != 10000 + spotIndex) {
					t = new Ticker(new Asset(name, 10000 + spotIndex, base.get("szDecimals").getAsInt(), 1, true, apiCoin, baseName));
					tickers.put(name, t);
					lowerToName.put(name.toLowerCase(Locale.ROOT), name);
					apiToName.put(apiCoin, name);
				}
				t.prevDay = num(c, "prevDayPx");
				t.volume = volume;
				if (Double.isNaN(t.mid)) t.mid = num(c, "midPx");
			}
		} catch (Exception e) {
			fail("spot meta", e);
		}
	}

	private void pollMids() {
		// with a live feed, REST is only a slow consistency check
		if (feedLive() && System.currentTimeMillis() - lastRestMids < 15_000) return;
		try {
			JsonObject mids = api.info("allMids").get(10, TimeUnit.SECONDS).getAsJsonObject();
			for (String dex : EXTRA_DEXES) {
				try {
					mids.asMap().putAll(api.info(Map.of("type", "allMids", "dex", dex)).get(10, TimeUnit.SECONDS).getAsJsonObject().asMap());
				} catch (Exception e) {
					fail(dex + " mids", e);
				}
			}
			lastRestMids = System.currentTimeMillis();
			applyMids(mids);
		} catch (Exception e) {
			fail("mids", e);
		}
	}

	private void fail(String what, Exception e) {
		String msg = what + ": " + e.getMessage();
		if (!msg.equals(lastError)) HyperCraft.LOG.warn("Hyperliquid {} poll failed: {}", what, e.toString());
		lastError = msg;
	}

	private static double num(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
	}

	private static double finite(double v) {
		return Double.isFinite(v) ? v : 0;
	}

	/** Case-insensitive coin lookup, returns the canonical name or null. */
	@Nullable
	public String resolve(String coin) {
		if (coin == null) return null;
		return lowerToName.get(coin.trim().toLowerCase(Locale.ROOT));
	}

	/** Market name for an exchange coin key ("@107" -> "HYPE/USDC", "BTC" -> "BTC"). */
	public String nameForApiCoin(String apiCoin) {
		return apiToName.getOrDefault(apiCoin, apiCoin);
	}

	@Nullable
	public Ticker ticker(String coin) {
		String name = resolve(coin);
		return name == null ? null : tickers.get(name);
	}

	@Override
	@Nullable
	public Asset asset(String coin) {
		Ticker t = ticker(coin);
		return t == null ? null : t.asset;
	}

	@Override
	public double funding(String coin) {
		Ticker t = ticker(coin);
		return t == null || t.asset.spot() ? 0 : t.funding;
	}

	/** Hyperliquid's mark price while it's fresh, else the mid. Spot and Unit tokens have no mark. */
	@Override
	public double mark(String coin) {
		Ticker t = ticker(coin);
		if (t == null) return Double.NaN;
		boolean fresh = !Double.isNaN(t.mark) && t.mark > 0 && System.currentTimeMillis() - t.markAt < MARK_STALE_MS;
		return fresh ? t.mark : t.mid;
	}

	@Override
	public double oracle(String coin) {
		Ticker t = ticker(coin);
		if (t == null) return Double.NaN;
		return !Double.isNaN(t.oracle) && t.oracle > 0 ? t.oracle : mark(coin);
	}

	/** Top of the order book; px/sz pairs, best first. */
	public record Book(long time, double[][] bids, double[][] asks) {
		public double spreadBps() {
			if (bids.length == 0 || asks.length == 0) return Double.NaN;
			double mid = (bids[0][0] + asks[0][0]) / 2;
			return (asks[0][0] - bids[0][0]) / mid * 10_000;
		}

		/** Share of resting size on the bid side across the top {@code levels}, 0..1. */
		public double bidShare(int levels) {
			double b = 0, a = 0;
			for (int i = 0; i < Math.min(levels, bids.length); i++) b += bids[i][1];
			for (int i = 0; i < Math.min(levels, asks.length); i++) a += asks[i][1];
			return a + b == 0 ? 0.5 : b / (a + b);
		}
	}

	private final Map<String, Book> books = new ConcurrentHashMap<>();
	private final Map<String, Long> bookRequested = new ConcurrentHashMap<>();

	/** Latest cached book for a market (may be null or a couple of seconds old); refreshes in the background. */
	@Nullable
	public Book book(String coin) {
		Ticker t = ticker(coin);
		if (t == null) return null;
		String name = t.asset.name();
		long now = System.currentTimeMillis();
		Book b = books.get(name);
		if (socket != null && socket.connected()) {
			if (bookWatchers.put(name, now) == null) {
				socket.subscribe(dev.hypercraft.hl.HlSocket.sub("l2Book", t.asset.apiCoin(), null), data -> {
					JsonArray levels = data.getAsJsonArray("levels");
					books.put(name, new Book(System.currentTimeMillis(), side(levels.get(0).getAsJsonArray()), side(levels.get(1).getAsJsonArray())));
				});
			}
			if (b != null && now - b.time < 10_000) return b;
		}
		Long asked = bookRequested.get(name);
		if ((b == null || now - b.time > 1500) && (asked == null || now - asked > 1500)) {
			bookRequested.put(name, now);
			api.info(Map.of("type", "l2Book", "coin", t.asset.apiCoin())).thenAccept(resp -> {
				JsonArray levels = resp.getAsJsonObject().getAsJsonArray("levels");
				books.put(name, new Book(System.currentTimeMillis(), side(levels.get(0).getAsJsonArray()), side(levels.get(1).getAsJsonArray())));
			}).exceptionally(e -> null);
		}
		return b;
	}

	private static double[][] side(JsonArray levels) {
		double[][] out = new double[Math.min(20, levels.size())][2];
		for (int i = 0; i < out.length; i++) {
			JsonObject l = levels.get(i).getAsJsonObject();
			out[i][0] = l.get("px").getAsDouble();
			out[i][1] = l.get("sz").getAsDouble();
		}
		return out;
	}

	/** Hourly funding rates for a perp over the last {@code hours}: [timeMs, rate] pairs. */
	public java.util.concurrent.CompletableFuture<JsonArray> fundingHistory(String coin, int hours) {
		Ticker t = ticker(coin);
		if (t == null || t.asset.spot()) return java.util.concurrent.CompletableFuture.completedFuture(new JsonArray());
		long start = System.currentTimeMillis() - hours * 3_600_000L;
		return api.info(Map.of("type", "fundingHistory", "coin", t.asset.name(), "startTime", start)).thenApply(resp -> {
			JsonArray out = new JsonArray();
			for (JsonElement el : resp.getAsJsonArray()) {
				JsonObject f = el.getAsJsonObject();
				JsonArray row = new JsonArray();
				row.add(f.get("time").getAsLong());
				row.add(f.get("fundingRate").getAsDouble());
				out.add(row);
			}
			return out;
		});
	}

	@Override
	public double mid(String coin) {
		Ticker t = ticker(coin);
		return t == null ? Double.NaN : t.mid;
	}

	public boolean healthy() {
		return System.currentTimeMillis() - lastMidsUpdate < 10_000;
	}

	@Nullable
	public String lastError() {
		return lastError;
	}

	public int coinCount() {
		return tickers.size();
	}

	/** Compact snapshot broadcast to clients: [name, mid, prevDay, funding, volume, szDecimals, maxLev, spot, index, dex, apiCoin]. */
	public JsonArray snapshot() {
		List<Ticker> list = new ArrayList<>(tickers.values());
		list.sort(Comparator.comparingDouble((Ticker t) -> -t.volume));
		JsonArray out = new JsonArray();
		for (Ticker t : list) {
			if (Double.isNaN(t.mid)) continue;
			JsonArray row = new JsonArray();
			row.add(t.asset.name);
			row.add(t.mid);
			row.add(Double.isNaN(t.prevDay) ? t.mid : t.prevDay);
			row.add(finite(t.funding));
			row.add(finite(t.volume));
			row.add(t.asset.szDecimals);
			row.add(t.asset.maxLeverage);
			row.add(t.asset.spot() ? 1 : 0);
			row.add(t.asset.index());
			row.add(t.asset.dex());
			row.add(t.asset.apiCoin());
			row.add(finite(mark(t.asset.name())));
			row.add(finite(oracle(t.asset.name())));
			out.add(row);
		}
		return out;
	}
}
