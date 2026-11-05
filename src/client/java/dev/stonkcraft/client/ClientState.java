package dev.stonkcraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Everything the client knows, fed by server packets. Touched on the render thread only. */
public final class ClientState {
	private ClientState() {}

	public static final int HISTORY = 120;

	public static final class Ticker {
		public final String name;
		public double mid = Double.NaN;
		public double prevDay;
		public double funding;
		/** Hyperliquid's mark and oracle prices (the mid on spot). */
		public double mark = Double.NaN;
		public double oracle = Double.NaN;
		public double volume;
		public int szDecimals;
		public int maxLeverage;
		public boolean spot;
		public int assetIndex = -1;
		/** "" main dex, "xyz" HIP-3 stocks and commodities, "unit" Stonkcraft's metal-backed tokens. */
		public String dex = "";
		/** The exchange's key for this market; for a Unit token, the market it's priced off. */
		public String apiCoin = "";
		/** Last tick direction (+1/-1) and when it happened, for price flashes. */
		public int lastMove;
		public long lastMoveAt;
		private final double[] history = new double[HISTORY];
		private int head;
		private int count;

		Ticker(String name) {
			this.name = name;
		}

		void push(double price) {
			if (!Double.isNaN(mid) && price != mid) {
				lastMove = price > mid ? 1 : -1;
				lastMoveAt = System.currentTimeMillis();
			}
			mid = price;
			history[head] = price;
			head = (head + 1) % HISTORY;
			if (count < HISTORY) count++;
		}

		/** Oldest → newest. */
		public double[] history() {
			double[] out = new double[count];
			for (int i = 0; i < count; i++) out[i] = history[Math.floorMod(head - count + i, HISTORY)];
			return out;
		}

		public double dayChangePct() {
			return prevDay == 0 || Double.isNaN(mid) ? 0 : (mid - prevDay) / prevDay * 100;
		}

		public double fundingApr() {
			return funding * 24 * 365 * 100;
		}
	}

	public static String mode = "PAPER";
	public static boolean healthy;
	public static long lastMarketAt;
	/** Ordered by 24h volume, as sent by the server. */
	public static final Map<String, Ticker> TICKERS = new LinkedHashMap<>();
	@Nullable
	public static JsonObject portfolio;
	@Nullable
	public static JsonObject candles;
	/** The owner's Access screen data for one account (grants, cards, activity). */
	@Nullable
	public static JsonObject access;
	@Nullable
	public static JsonObject book;
	@Nullable
	public static JsonObject fundingHistory;
	/** The selected account's full trade and order history and long equity record, as last asked for. */
	@Nullable
	public static JsonObject history;
	/** Accounts this player can use: id, name, label, equity, withdrawable, upnl, positions, owned, selected, available. */
	public static JsonArray accounts = new JsonArray();
	/** The player's running speedrun (start, netWorth), or null. */
	@Nullable
	public static JsonObject speedrun;
	/** Whether the build menu gives blocks free, sent with the account list. */
	public static boolean canBuild;
	/** The Trading Terminal block the open terminal belongs to (BlockPos as long), or null for none. */
	@org.jetbrains.annotations.Nullable
	public static Long terminalPos;
	/** The server's paper mint, and the most one press prints. */
	public static boolean faucet;
	public static double faucetUsd;
	public static String lastVaultMsg = "";
	public static boolean lastVaultOk;
	public static long lastVaultAt;
	public static String lastOrderMsg = "";
	public static boolean lastOrderOk;
	public static long lastOrderAt;

	public static void onMarket(JsonObject o) {
		mode = o.get("mode").getAsString();
		healthy = o.get("healthy").getAsBoolean();
		lastMarketAt = System.currentTimeMillis();
		Map<String, Ticker> next = new LinkedHashMap<>();
		for (JsonElement el : o.getAsJsonArray("coins")) {
			JsonArray r = el.getAsJsonArray();
			String name = r.get(0).getAsString();
			Ticker t = TICKERS.get(name);
			if (t == null) t = new Ticker(name);
			t.push(r.get(1).getAsDouble());
			t.prevDay = r.get(2).getAsDouble();
			t.funding = r.get(3).getAsDouble();
			t.volume = r.get(4).getAsDouble();
			t.szDecimals = r.get(5).getAsInt();
			t.maxLeverage = r.get(6).getAsInt();
			t.spot = r.size() > 7 && r.get(7).getAsInt() == 1;
			if (r.size() > 8) t.assetIndex = r.get(8).getAsInt();
			if (r.size() > 9) t.dex = r.get(9).getAsString();
			if (r.size() > 10) t.apiCoin = r.get(10).getAsString();
			if (r.size() > 12) {
				t.mark = r.get(11).getAsDouble();
				t.oracle = r.get(12).getAsDouble();
			}
			next.put(name, t);
		}
		TICKERS.clear();
		TICKERS.putAll(next);
	}

	@Nullable
	public static Ticker ticker(String coin) {
		if (coin == null) return null;
		Ticker t = TICKERS.get(coin);
		if (t != null) return t;
		String lower = coin.trim().toLowerCase(Locale.ROOT);
		for (Ticker x : TICKERS.values()) if (x.name.toLowerCase(Locale.ROOT).equals(lower)) return x;
		// bare tickers: "gold" -> xyz:GOLD, "ugold" -> UGOLD/USDC
		for (Ticker x : TICKERS.values()) if (display(x.name).toLowerCase(Locale.ROOT).equals(lower) && !x.dex.isEmpty()) return x;
		return null;
	}

	/** A market's short name for lists and labels: "xyz:GOLD" -> "GOLD", "HYPE/USDC" -> "HYPE". */
	public static String display(String name) {
		return dev.stonkcraft.trading.Fmt.market(name);
	}

	public static List<Ticker> search(String prefix, int limit) {
		if (prefix.isBlank()) return Collections.emptyList();
		String p = prefix.trim().toLowerCase(Locale.ROOT);
		List<Ticker> out = new ArrayList<>();
		for (Ticker t : TICKERS.values()) {
			if (t.name.toLowerCase(Locale.ROOT).startsWith(p) || display(t.name).toLowerCase(Locale.ROOT).startsWith(p)) out.add(t);
			if (out.size() >= limit) break;
		}
		return out;
	}

	@Nullable
	public static JsonObject account(String id) {
		for (JsonElement el : accounts) {
			JsonObject o = el.getAsJsonObject();
			if (o.get("id").getAsString().equals(id)) return o;
		}
		return null;
	}

	/** True when the terminal has a usable account to trade. */
	public static boolean hasAccount() {
		return portfolio != null && !portfolio.has("none");
	}

	public static String selectedAccountId() {
		return portfolio != null && portfolio.has("accountId") ? portfolio.get("accountId").getAsString() : "";
	}

	public static double equity() {
		return hasAccount() ? portfolio.get("equity").getAsDouble() : Double.NaN;
	}

	public static double positionSize(String coin) {
		if (!hasAccount()) return 0;
		for (JsonElement el : portfolio.getAsJsonArray("positions")) {
			JsonObject p = el.getAsJsonObject();
			if (p.get("coin").getAsString().equals(coin)) return p.get("size").getAsDouble();
		}
		return 0;
	}

	@Nullable
	public static JsonObject position(String coin) {
		if (!hasAccount()) return null;
		for (JsonElement el : portfolio.getAsJsonArray("positions")) {
			JsonObject p = el.getAsJsonObject();
			if (p.get("coin").getAsString().equals(coin)) return p;
		}
		return null;
	}

	public static void reset() {
		TICKERS.clear();
		portfolio = null;
		accounts = new JsonArray();
		candles = null;
		history = null;
		lastOrderMsg = "";
	}
}
