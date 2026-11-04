package dev.hypercraft.trading;

import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * Everything a front end can ask for in one order: the terminal, commands and Trade Executors all build these.
 * Prices are either absolute ({@link #price} &gt; 0) or relative to the mid ({@link #offsetPct}).
 */
public final class OrderRequest {
	public enum Type {
		MARKET("Market"), LIMIT("Limit"), STOP("Stop"), TAKE_PROFIT("Take profit"), TWAP("TWAP"),
		STOP_LIMIT("Stop limit"), TAKE_LIMIT("Take limit"), SCALE("Scale"), TRAILING("Trailing stop");

		/** What a Trade Executor or an Order Ticket can run: the rest need settings only the terminal has. */
		public static final Type[] BLOCK_TYPES = {MARKET, LIMIT, STOP, TAKE_PROFIT, TWAP};

		public final String label;

		Type(String label) {
			this.label = label;
		}

		public static Type parse(String s) {
			try {
				return valueOf(s.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException | NullPointerException e) {
				return MARKET;
			}
		}

		public boolean isTrigger() {
			return this == STOP || this == TAKE_PROFIT || this == STOP_LIMIT || this == TAKE_LIMIT || this == TRAILING;
		}

		/** Triggers a limit order at {@link OrderRequest#limitPrice}, rather than a market order. */
		public boolean isTriggerLimit() {
			return this == STOP_LIMIT || this == TAKE_LIMIT;
		}

		/** Runs on a block: everything except the terminal-only types. */
		public boolean onBlocks() {
			return ordinal() < BLOCK_TYPES.length;
		}
	}

	/** Time in force for limit orders. */
	public enum Tif {
		GTC("Gtc"), ALO("Alo"), IOC("Ioc");

		public final String wire;

		Tif(String wire) {
			this.wire = wire;
		}

		public static Tif parse(String s) {
			try {
				return valueOf(s.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException | NullPointerException e) {
				return GTC;
			}
		}
	}

	public String coin = "BTC";
	public Side side = Side.BUY;
	public double usd;
	public Type type = Type.MARKET;
	/** Absolute limit/trigger price; 0 means use {@link #offsetPct} from the mid. */
	public double price;
	public double offsetPct;
	public Tif tif = Tif.GTC;
	public boolean reduceOnly;
	/** A copy of this request for another coin name (same settings, same cloid). */
	public OrderRequest copyFor(String coinName) {
		OrderRequest o = fromJson(toJson());
		o.coin = coinName;
		o.cloid = cloid;
		return o;
	}

	/** Client order id for the entry order (see {@link Cloids}), or null. Not part of saved config. */
	@org.jetbrains.annotations.Nullable
	public transient String cloid;
	/** Bracket: take-profit / stop-loss distance in % from the entry price; 0 = none. */
	public double tpPct;
	public double slPct;
	/** Bracket by price rather than distance: wins over the % when set. */
	public double tpPx;
	public double slPx;
	/** TWAP duration in minutes (Hyperliquid allows 5 minutes to 7 days) and whether slices are randomized. */
	public int minutes = 30;
	public boolean randomize = true;
	/** TWAP: a price it won't trade through (buys wait at or below it, sells at or above). 0 = none. */
	public double twapLimit;
	/** Order size in coin. Above 0 it wins over {@link #usd} for sizing; {@link #usd} is still what limits check. */
	public double sizeCoin;
	/** Stop Limit / Take Limit: the limit price the order rests at once triggered. */
	public double limitPrice;
	/** Scale: limit orders spread from {@link #scaleStart} to {@link #scaleEnd}, the end's size {@link #scaleSkew} times the start's. */
	public double scaleStart;
	public double scaleEnd;
	public int scaleOrders = 5;
	public double scaleSkew = 1;
	/** Trailing stop: how far (%) the mark may retrace from its best, and the mark that starts it (0 = at once). */
	public double trailPct;
	public double activationPx;
	/** Most slippage a market order accepts, as a fraction; 0 = the server's default. */
	public double maxSlippage;

	public static OrderRequest market(String coin, Side side, double usd) {
		OrderRequest r = new OrderRequest();
		r.coin = coin;
		r.side = side;
		r.usd = usd;
		return r;
	}

	/** The same order as a plain market order (used to size TWAPs). */
	public OrderRequest asMarket() {
		OrderRequest m = fromJson(toJson());
		m.type = Type.MARKET;
		m.tpPct = 0;
		m.slPct = 0;
		m.tpPx = 0;
		m.slPx = 0;
		return m;
	}

	/** Limit/trigger price for a given mid. */
	public double resolvePrice(double mid) {
		return price > 0 ? price : mid * (1 + offsetPct / 100.0);
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		o.addProperty("side", side.name());
		o.addProperty("usd", usd);
		o.addProperty("type", type.name());
		o.addProperty("price", price);
		o.addProperty("offsetPct", offsetPct);
		o.addProperty("tif", tif.name());
		o.addProperty("reduceOnly", reduceOnly);
		o.addProperty("tpPct", tpPct);
		o.addProperty("slPct", slPct);
		o.addProperty("minutes", minutes);
		o.addProperty("randomize", randomize);
		// only when set: a ticket or an executor's saved config never carries terminal-only settings it doesn't use
		put(o, "tpPx", tpPx);
		put(o, "slPx", slPx);
		put(o, "twapLimit", twapLimit);
		put(o, "sizeCoin", sizeCoin);
		put(o, "limitPrice", limitPrice);
		put(o, "scaleStart", scaleStart);
		put(o, "scaleEnd", scaleEnd);
		if (type == Type.SCALE) {
			o.addProperty("scaleOrders", scaleOrders);
			o.addProperty("scaleSkew", scaleSkew);
		}
		put(o, "trailPct", trailPct);
		put(o, "activationPx", activationPx);
		put(o, "maxSlippage", maxSlippage);
		return o;
	}

	private static void put(JsonObject o, String key, double v) {
		if (v != 0) o.addProperty(key, v);
	}

	public static OrderRequest fromJson(JsonObject o) {
		OrderRequest r = new OrderRequest();
		r.coin = str(o, "coin", r.coin);
		r.side = Side.parse(str(o, "side", "BUY"));
		r.usd = num(o, "usd");
		r.type = Type.parse(str(o, "type", "MARKET"));
		r.price = num(o, "price");
		r.offsetPct = num(o, "offsetPct");
		r.tif = Tif.parse(str(o, "tif", "GTC"));
		r.reduceOnly = o.has("reduceOnly") && o.get("reduceOnly").getAsBoolean();
		r.tpPct = Math.max(0, num(o, "tpPct"));
		r.slPct = Math.max(0, num(o, "slPct"));
		if (o.has("minutes")) r.minutes = (int) Math.max(5, Math.min(MAX_TWAP_MINUTES, num(o, "minutes")));
		r.randomize = !o.has("randomize") || o.get("randomize").getAsBoolean();
		r.tpPx = num(o, "tpPx");
		r.slPx = num(o, "slPx");
		r.twapLimit = num(o, "twapLimit");
		r.sizeCoin = num(o, "sizeCoin");
		r.limitPrice = num(o, "limitPrice");
		r.scaleStart = num(o, "scaleStart");
		r.scaleEnd = num(o, "scaleEnd");
		if (o.has("scaleOrders")) r.scaleOrders = (int) num(o, "scaleOrders");
		if (o.has("scaleSkew")) r.scaleSkew = num(o, "scaleSkew");
		r.trailPct = num(o, "trailPct");
		r.activationPx = num(o, "activationPx");
		r.maxSlippage = num(o, "maxSlippage");
		return r.sanitized();
	}

	/** Clamps anything a client could send into sane ranges (NaN and infinities become 0). */
	public OrderRequest sanitized() {
		usd = clamp(usd, 0, 1e9);
		price = clamp(price, 0, 1e12);
		offsetPct = clamp(offsetPct, -50, 50);
		tpPct = clamp(tpPct, 0, 1000);
		slPct = clamp(slPct, 0, 1000);
		minutes = Math.max(5, Math.min(MAX_TWAP_MINUTES, minutes));
		tpPx = clamp(tpPx, 0, 1e12);
		slPx = clamp(slPx, 0, 1e12);
		twapLimit = clamp(twapLimit, 0, 1e12);
		sizeCoin = clamp(sizeCoin, 0, 1e12);
		limitPrice = clamp(limitPrice, 0, 1e12);
		scaleStart = clamp(scaleStart, 0, 1e12);
		scaleEnd = clamp(scaleEnd, 0, 1e12);
		scaleOrders = Math.max(2, Math.min(MAX_SCALE_ORDERS, scaleOrders));
		scaleSkew = Double.isFinite(scaleSkew) && scaleSkew > 0 ? Math.min(100, scaleSkew) : 1;
		trailPct = clamp(trailPct, 0, 50);
		activationPx = clamp(activationPx, 0, 1e12);
		maxSlippage = clamp(maxSlippage, 0, 1);
		if (coin == null || coin.length() > 32) coin = "BTC";
		return this;
	}

	/** Seven days, Hyperliquid's longest. */
	public static final int MAX_TWAP_MINUTES = 10_080;
	public static final int MAX_SCALE_ORDERS = 100;

	private static double clamp(double v, double lo, double hi) {
		return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : 0;
	}

	private static String str(JsonObject o, String k, String def) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
	}

	private static double num(JsonObject o, String k) {
		try {
			return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0;
		} catch (RuntimeException e) {
			return 0;
		}
	}
}
