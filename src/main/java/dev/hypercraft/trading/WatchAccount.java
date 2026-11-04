package dev.hypercraft.trading;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hypercraft.HyperCraft;
import dev.hypercraft.market.MarketData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * A read-only mirror of any Hyperliquid mainnet address: positions (main dex and the xyz HIP-3 dex), spot balances,
 * open orders, fills, funding and Hyperliquid's own PnL. Refuses every write. Watched accounts drive sensors,
 * displays and copy trading.
 */
public final class WatchAccount implements TradingAccount {
	private record WatchPos(double size, double entry, double mark, double upnl, double liq, int lev, boolean cross) {}

	private record WatchOrder(long oid, String coin, boolean isBuy, double size, String type, double px, boolean reduceOnly, long time) {}

	private record WatchBalance(String token, String market, double qty, double cost) {}

	/** HIP-3 dexes whose positions are folded into the account. */
	private static final String[] EXTRA_DEXES = {"xyz"};
	private static final String WATCH_ONLY = "Watch-only account: it can't trade";

	private final MarketData market;
	private final String user;
	private final ScheduledExecutorService exec;

	private volatile Map<String, WatchPos> positions = Map.of();
	/** Positions and account value on the extra dexes, refreshed by REST only (no per-dex feed). */
	private volatile Map<String, WatchPos> dexPositions = Map.of();
	private volatile double dexEquity, dexMargin;
	private volatile List<WatchOrder> orders = List.of();
	private volatile List<WatchBalance> balances = List.of();
	private volatile Map<String, Long> lastFill = Map.of();
	private volatile double equity = Double.NaN;
	private volatile double marginUsed;
	private volatile double withdrawable;
	private volatile JsonArray fills = new JsonArray();
	private volatile JsonArray funding = new JsonArray();
	/** Spot (and anything else outside perps) from the portfolio endpoint: total value minus perps value at that moment. */
	private volatile double offPerps;
	private volatile double pnlDay = Double.NaN, pnlWeek = Double.NaN, pnlMonth = Double.NaN, pnlAll = Double.NaN;
	private long portfolioAt;
	private final List<Double> equityHistory = new ArrayList<>();
	private long lastEquitySample;

	public WatchAccount(MarketData market, String address, ScheduledExecutorService exec) {
		this.market = market;
		this.user = address.toLowerCase();
		this.exec = exec;
	}

	public void start() {
		exec.scheduleWithFixedDelay(this::refresh, 0, 3, TimeUnit.SECONDS);
	}

	/** Forces a state refresh soon. */
	public void refreshSoon() {
		exec.execute(this::refresh);
	}

	public String user() {
		return user;
	}

	private JsonElement info(String type) throws Exception {
		return market.api().info(type, user).get(10, TimeUnit.SECONDS);
	}

	/** Full REST refresh: every 3s, or every 30s as a consistency check while the live feed carries updates. */
	private void refresh() {
		long now = System.currentTimeMillis();
		try {
			boolean full = !liveFeed || now - lastFullRefresh >= 30_000;
			if (full) {
				applyClearinghouse(info("clearinghouseState").getAsJsonObject());
				applySpot(info("spotClearinghouseState").getAsJsonObject());
				applyOpenOrders(info("frontendOpenOrders").getAsJsonArray());
				applyFills(info("userFills").getAsJsonArray(), true);
				lastFullRefresh = now;
			}
			if (now - lastDexRefresh >= 6_000) {
				lastDexRefresh = now;
				refreshDexes();
			}
			if (now - lastFundingRefresh >= 30_000) {
				lastFundingRefresh = now;
				refreshFunding();
			}
			checkPortfolio();
			sampleEquity();
		} catch (Exception e) {
			HyperCraft.LOG.debug("Watch account refresh failed", e);
		}
	}

	private void refreshDexes() throws Exception {
		Map<String, WatchPos> map = new ConcurrentHashMap<>();
		double eq = 0, margin = 0;
		for (String dex : EXTRA_DEXES) {
			JsonElement r = market.api().info(Map.of("type", "clearinghouseState", "user", user, "dex", dex)).get(10, TimeUnit.SECONDS);
			if (r == null || !r.isJsonObject()) continue;
			JsonObject st = r.getAsJsonObject();
			JsonObject ms = st.getAsJsonObject("marginSummary");
			if (ms != null) {
				eq += ms.get("accountValue").getAsDouble();
				margin += ms.get("totalMarginUsed").getAsDouble();
			}
			map.putAll(readPositions(st));
		}
		dexPositions = map;
		dexEquity = eq;
		dexMargin = margin;
	}

	private void sampleEquity() {
		long now = System.currentTimeMillis();
		synchronized (equityHistory) {
			if (now - lastEquitySample >= 15_000 && !Double.isNaN(equity)) {
				equityHistory.add(equity());
				if (equityHistory.size() > 240) equityHistory.remove(0);
				lastEquitySample = now;
			}
		}
	}

	// ------------------------------------------------------------------ state from REST or the feed

	private static Map<String, WatchPos> readPositions(JsonObject st) {
		Map<String, WatchPos> map = new ConcurrentHashMap<>();
		for (JsonElement el : st.getAsJsonArray("assetPositions")) {
			JsonObject p = el.getAsJsonObject().getAsJsonObject("position");
			double szi = p.get("szi").getAsDouble();
			if (szi == 0) continue;
			double value = p.get("positionValue").getAsDouble();
			JsonElement liq = p.get("liquidationPx");
			JsonObject lev = p.getAsJsonObject("leverage");
			map.put(p.get("coin").getAsString(), new WatchPos(szi, p.get("entryPx").getAsDouble(), value / Math.abs(szi),
					p.get("unrealizedPnl").getAsDouble(), liq == null || liq.isJsonNull() ? Double.NaN : liq.getAsDouble(),
					lev == null ? 0 : lev.get("value").getAsInt(), lev == null || "cross".equals(lev.get("type").getAsString())));
		}
		return map;
	}

	void applyClearinghouse(JsonObject st) {
		JsonObject ms = st.getAsJsonObject("marginSummary");
		equity = ms.get("accountValue").getAsDouble();
		marginUsed = ms.get("totalMarginUsed").getAsDouble();
		withdrawable = st.get("withdrawable").getAsDouble();
		positions = readPositions(st);
	}

	void applySpot(JsonObject spotState) {
		List<WatchBalance> bal = new ArrayList<>();
		for (JsonElement el : spotState.getAsJsonArray("balances")) {
			JsonObject b = el.getAsJsonObject();
			String token = b.get("coin").getAsString();
			double total = b.get("total").getAsDouble();
			if (token.equals("USDC") || total == 0) continue;
			bal.add(new WatchBalance(token, token + "/USDC", total, b.has("entryNtl") ? b.get("entryNtl").getAsDouble() : 0));
		}
		balances = bal;
	}

	/** Raw fills, newest first, kept so feed updates can be merged into the REST snapshot. */
	private final java.util.ArrayDeque<JsonObject> rawFills = new java.util.ArrayDeque<>();

	/** @param snapshot true replaces the list (REST, or the feed's first message); false merges new fills in */
	void applyFills(JsonArray incoming, boolean snapshot) {
		synchronized (rawFills) {
			if (snapshot) rawFills.clear();
			List<JsonObject> fresh = new ArrayList<>();
			for (JsonElement el : incoming) fresh.add(el.getAsJsonObject());
			fresh.sort((a, b) -> Long.compare(b.get("time").getAsLong(), a.get("time").getAsLong()));
			if (snapshot) {
				rawFills.addAll(fresh);
			} else {
				for (int i = fresh.size() - 1; i >= 0; i--) rawFills.addFirst(fresh.get(i));
			}
			while (rawFills.size() > 200) rawFills.removeLast();
			JsonArray out = new JsonArray();
			Map<String, Long> latest = new HashMap<>();
			int i = 0;
			for (JsonObject f : rawFills) {
				String coin = market.nameForApiCoin(f.get("coin").getAsString());
				long time = f.get("time").getAsLong();
				latest.merge(coin, time, Math::max);
				if (i++ < 40) {
					double sz = f.get("sz").getAsDouble() * ("B".equals(f.get("side").getAsString()) ? 1 : -1);
					out.add(Fmt.fill(time, coin, sz, f.get("px").getAsDouble(), f.has("closedPnl") ? f.get("closedPnl").getAsDouble() : 0));
				}
			}
			fills = out;
			lastFill = latest;
		}
	}

	private void refreshFunding() throws Exception {
		JsonArray fund = new JsonArray();
		JsonElement uf = market.api().info(Map.of("type", "userFunding", "user", user, "startTime", System.currentTimeMillis() - 86_400_000L))
				.get(10, TimeUnit.SECONDS);
		if (uf != null && uf.isJsonArray()) {
			JsonArray arr = uf.getAsJsonArray();
			for (int i = arr.size() - 1; i >= 0 && fund.size() < 60; i--) {
				JsonObject f = arr.get(i).getAsJsonObject();
				JsonObject d = f.getAsJsonObject("delta");
				if (d == null || !d.has("usdc")) continue;
				fund.add(Fmt.funding(f.get("time").getAsLong(), d.get("coin").getAsString(), d.get("usdc").getAsDouble(),
						d.has("fundingRate") ? d.get("fundingRate").getAsDouble() : 0));
			}
		}
		funding = fund;
	}

	// ------------------------------------------------------------------ live feed

	private volatile boolean liveFeed;
	private long lastFullRefresh, lastFundingRefresh, lastDexRefresh;

	public boolean hasLiveFeed() {
		return liveFeed;
	}

	/** Streams this account's state over the shared feed (true) or goes back to 3s polling (false). */
	public void setLiveFeed(boolean on) {
		dev.hypercraft.hl.HlSocket socket = market.socket();
		if (socket == null || on == liveFeed) return;
		liveFeed = on;
		for (String type : FEEDS) {
			JsonObject sub = dev.hypercraft.hl.HlSocket.sub(type, null, user);
			if (on) socket.subscribe(sub, data -> onFeed(type, data));
			else socket.unsubscribe(sub);
		}
		if (!on) refreshSoon();
	}

	/** Feeds a recorded feed message in, as if it arrived over the socket. For tests. */
	public void applyForTest(String type, JsonObject data) {
		onFeed(type, data);
	}

	private static final String[] FEEDS = {"clearinghouseState", "openOrders", "spotState", "userFills"};

	private void onFeed(String type, JsonObject data) {
		switch (type) {
			case "clearinghouseState" -> applyClearinghouse(data.getAsJsonObject("clearinghouseState"));
			case "openOrders" -> applyOpenOrders(data.getAsJsonArray("orders"));
			case "spotState" -> applySpot(data.getAsJsonObject("spotState"));
			case "userFills" -> applyFills(data.getAsJsonArray("fills"), data.has("isSnapshot") && data.get("isSnapshot").getAsBoolean());
			default -> {}
		}
		sampleEquity();
	}

	/** Replaces the open-order list from a frontendOpenOrders-shaped array. */
	void applyOpenOrders(JsonArray arr) {
		List<WatchOrder> open = new ArrayList<>();
		for (JsonElement el : arr) {
			JsonObject o = el.getAsJsonObject();
			boolean trig = o.has("isTrigger") && o.get("isTrigger").getAsBoolean();
			double px = trig && o.has("triggerPx") ? o.get("triggerPx").getAsDouble() : o.get("limitPx").getAsDouble();
			open.add(new WatchOrder(o.get("oid").getAsLong(), market.nameForApiCoin(o.get("coin").getAsString()), "B".equals(o.get("side").getAsString()),
					o.get("sz").getAsDouble(), o.has("orderType") ? o.get("orderType").getAsString() : "Limit", px,
					o.has("reduceOnly") && o.get("reduceOnly").getAsBoolean(), o.has("timestamp") ? o.get("timestamp").getAsLong() : System.currentTimeMillis()));
		}
		orders = open;
	}

	/** Exchange-reported PnL per period and total account value (perps + spot), every 30s. */
	private void checkPortfolio() {
		long now = System.currentTimeMillis();
		if (now - portfolioAt < 30_000) return;
		portfolioAt = now;
		try {
			JsonElement r = market.api().info(Map.of("type", "portfolio", "user", user)).get(10, TimeUnit.SECONDS);
			if (r == null || !r.isJsonArray()) return;
			for (JsonElement el : r.getAsJsonArray()) {
				JsonArray pair = el.getAsJsonArray();
				String period = pair.get(0).getAsString();
				JsonObject d = pair.get(1).getAsJsonObject();
				double pnl = last(d.getAsJsonArray("pnlHistory"));
				switch (period) {
					case "day" -> pnlDay = pnl;
					case "week" -> pnlWeek = pnl;
					case "month" -> pnlMonth = pnl;
					case "allTime" -> {
						pnlAll = pnl;
						double total = last(d.getAsJsonArray("accountValueHistory"));
						if (!Double.isNaN(total) && !Double.isNaN(equity)) offPerps = Math.max(0, total - equity - dexEquity);
					}
					default -> {}
				}
			}
		} catch (Exception e) {
			HyperCraft.LOG.debug("portfolio failed", e);
		}
	}

	private static double last(@Nullable JsonArray series) {
		if (series == null || series.isEmpty()) return Double.NaN;
		JsonArray pt = series.get(series.size() - 1).getAsJsonArray();
		return pt.get(1).getAsDouble();
	}

	// ------------------------------------------------------------------ reads

	@Nullable
	private WatchPos pos(String coin) {
		WatchPos p = positions.get(coin);
		return p != null ? p : dexPositions.get(coin);
	}

	/** Every perp position, main dex and extra dexes: coin -> {signed size, mark}. */
	@Override
	public Map<String, double[]> perpPositions() {
		Map<String, double[]> out = new HashMap<>();
		positions.forEach((c, p) -> out.put(c, new double[]{p.size, p.mark}));
		dexPositions.forEach((c, p) -> out.put(c, new double[]{p.size, p.mark}));
		return out;
	}

	@Override
	public String mode() {
		return "WATCH";
	}

	@Override
	public double positionSize(String coin) {
		MarketData.Asset a = market.asset(coin);
		if (a != null && a.spot()) {
			for (WatchBalance b : balances) if (b.token.equals(a.base())) return b.qty;
			return 0;
		}
		WatchPos p = pos(a == null ? coin : a.name());
		return p == null ? 0 : p.size;
	}

	@Override
	public double unrealizedPnl(String coin) {
		WatchPos p = pos(coin);
		return p == null ? 0 : p.upnl;
	}

	@Override
	public double equity() {
		return Double.isNaN(equity) ? equity : equity + dexEquity + offPerps;
	}

	@Override
	public double marginUsed() {
		return marginUsed + dexMargin;
	}

	@Override
	public double withdrawable() {
		return withdrawable;
	}

	@Override
	public int leverage(String coin) {
		WatchPos p = pos(coin);
		if (p != null && p.lev > 0) return p.lev;
		MarketData.Asset a = market.asset(coin);
		return a == null || a.spot() ? 0 : Math.min(20, a.maxLeverage());
	}

	@Override
	public boolean isCross(String coin) {
		WatchPos p = pos(coin);
		return p == null || p.cross;
	}

	@Override
	public int openOrders(@Nullable String coin) {
		int n = 0;
		for (WatchOrder o : orders) if (coin == null || o.coin.equals(coin)) n++;
		return n;
	}

	@Override
	public long scheduledCancel() {
		return 0;
	}

	@Override
	public long lastFillTime(String coin) {
		return lastFill.getOrDefault(coin, 0L);
	}

	@Override
	public OrderStatus orderStatus(String cloid) {
		return OrderStatus.NONE;
	}

	// ------------------------------------------------------------------ writes: all refused

	private static CompletableFuture<OrderResult> refuse(String coin) {
		return CompletableFuture.completedFuture(OrderResult.fail(coin, WATCH_ONLY));
	}

	@Override
	public CompletableFuture<OrderResult> submit(OrderRequest r) {
		return refuse(r.coin);
	}

	@Override
	public CompletableFuture<OrderResult> cancel(String coin, long oid) {
		return refuse(coin);
	}

	@Override
	public CompletableFuture<OrderResult> modify(String cloid, OrderRequest r) {
		return refuse(r.coin);
	}

	@Override
	public CompletableFuture<OrderResult> cancelByCloid(String coin, String cloid) {
		return refuse(coin);
	}

	@Override
	public CompletableFuture<OrderResult> cancelAll(@Nullable String coin) {
		return refuse(coin == null ? "" : coin);
	}

	@Override
	public CompletableFuture<OrderResult> setLeverage(String coin, int leverage, boolean cross) {
		return refuse(coin);
	}

	@Override
	public CompletableFuture<OrderResult> cancelTwap(String coin, long twapId) {
		return refuse(coin);
	}

	@Override
	public CompletableFuture<OrderResult> scheduleCancel(long timeMs) {
		return refuse("");
	}

	@Override
	public CompletableFuture<OrderResult> adjustMargin(String coin, double usd) {
		return refuse(coin);
	}

	// ------------------------------------------------------------------ snapshot

	@Override
	public JsonObject snapshot() {
		JsonObject o = new JsonObject();
		o.addProperty("mode", mode());
		o.addProperty("account", user);
		double upnl = 0;
		JsonArray arr = new JsonArray();
		JsonObject lev = new JsonObject();
		for (Map<String, WatchPos> m : List.of(positions, dexPositions)) {
			for (Map.Entry<String, WatchPos> e : m.entrySet()) {
				WatchPos p = e.getValue();
				upnl += p.upnl;
				JsonObject row = Fmt.position(e.getKey(), p.size, p.entry, p.mark, p.upnl, p.liq);
				row.addProperty("lev", p.lev);
				row.addProperty("cross", p.cross);
				arr.add(row);
				lev.add(e.getKey(), Fmt.leverage(p.lev, p.cross));
			}
		}
		o.addProperty("equity", Double.isNaN(equity) ? 0 : equity());
		if (!Double.isNaN(pnlAll)) {
			o.addProperty("pnlDay", pnlDay);
			o.addProperty("pnlWeek", pnlWeek);
			o.addProperty("pnlMonth", pnlMonth);
			o.addProperty("pnlAll", pnlAll);
		}
		o.addProperty("readOnly", true);
		o.addProperty("upnl", upnl);
		o.addProperty("margin", marginUsed());
		o.addProperty("withdrawable", withdrawable);
		o.add("positions", arr);
		JsonArray bal = new JsonArray();
		for (WatchBalance b : balances) {
			double m = market.mid(b.market);
			bal.add(Fmt.balance(b.token, b.market, b.qty, Double.isNaN(m) ? 0 : b.qty * m, b.cost));
		}
		o.add("balances", bal);
		JsonArray ords = new JsonArray();
		for (WatchOrder lo : orders) ords.add(Fmt.order(lo.oid, lo.coin, lo.isBuy, lo.size, lo.type, lo.px, lo.reduceOnly, false, lo.time));
		o.add("orders", ords);
		o.add("twaps", new JsonArray());
		o.addProperty("scheduledCancel", 0);
		o.add("funding", funding);
		o.add("leverage", lev);
		o.addProperty("defaultLev", 20);
		o.add("fills", fills);
		JsonArray hist = new JsonArray();
		synchronized (equityHistory) {
			for (double d : equityHistory) hist.add(d);
		}
		if (!Double.isNaN(equity)) hist.add(equity());
		o.add("equityHistory", hist);
		return o;
	}
}
