package dev.hypercraft.trading;

import com.google.gson.JsonObject;
import java.util.Locale;

/** Number formatting shared by server messages and client UI, plus the portfolio JSON row shapes. */
public final class Fmt {
	private Fmt() {}

	public static String price(double p) {
		if (Double.isNaN(p)) return "--";
		double a = Math.abs(p);
		if (a >= 10_000) return String.format(Locale.ROOT, "%,.1f", p);
		if (a >= 1_000) return String.format(Locale.ROOT, "%,.2f", p);
		if (a >= 10) return String.format(Locale.ROOT, "%.3f", p);
		if (a >= 1) return String.format(Locale.ROOT, "%.4f", p);
		if (a >= 0.01) return String.format(Locale.ROOT, "%.5f", p);
		return String.format(Locale.ROOT, "%.7f", p);
	}

	/** Plain number for input boxes: 6 significant figures, no grouping. */
	public static String plain(double v) {
		if (Double.isNaN(v)) return "";
		return new java.math.BigDecimal(v).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString();
	}

	public static String usd(double v) {
		if (Double.isNaN(v)) return "--";
		return (v < 0 ? "-$" : "$") + String.format(Locale.ROOT, "%,.2f", Math.abs(v));
	}

	public static String usdSigned(double v) {
		return (v >= 0 ? "+" : "") + usd(v);
	}

	public static String usdCompact(double v) {
		double a = Math.abs(v);
		String s;
		if (a >= 1e9) s = String.format(Locale.ROOT, "%.2fB", a / 1e9);
		else if (a >= 1e6) s = String.format(Locale.ROOT, "%.1fM", a / 1e6);
		else if (a >= 1e3) s = String.format(Locale.ROOT, "%.1fK", a / 1e3);
		else s = String.format(Locale.ROOT, "%.0f", a);
		return (v < 0 ? "-$" : "$") + s;
	}

	public static String usdCompactSigned(double v) {
		return (v >= 0 ? "+" : "") + usdCompact(v);
	}

	public static String pct(double v) {
		return String.format(Locale.ROOT, "%+.2f%%", v);
	}

	public static String size(double v) {
		String s = String.format(Locale.ROOT, "%.5f", v);
		s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		return s;
	}

	public static JsonObject position(String coin, double size, double entry, double mark, double upnl, double liq) {
		JsonObject p = new JsonObject();
		p.addProperty("coin", coin);
		p.addProperty("size", size);
		p.addProperty("entry", entry);
		p.addProperty("mark", mark);
		p.addProperty("upnl", upnl);
		if (Double.isFinite(liq)) p.addProperty("liq", liq);
		return p;
	}

	public static JsonObject balance(String token, String market, double qty, double value, double cost) {
		JsonObject b = new JsonObject();
		b.addProperty("token", token);
		b.addProperty("market", market);
		b.addProperty("qty", qty);
		b.addProperty("value", value);
		b.addProperty("cost", cost);
		return b;
	}

	public static JsonObject order(long oid, String coin, boolean isBuy, double size, String type, double px, boolean reduceOnly,
			boolean pending, long time) {
		JsonObject o = new JsonObject();
		o.addProperty("oid", oid);
		o.addProperty("coin", coin);
		o.addProperty("buy", isBuy);
		o.addProperty("size", size);
		o.addProperty("type", type);
		o.addProperty("px", px);
		o.addProperty("reduceOnly", reduceOnly);
		o.addProperty("pending", pending);
		o.addProperty("t", time);
		return o;
	}

	public static JsonObject twap(long id, String coin, boolean isBuy, double total, double filled, long start, int minutes) {
		JsonObject t = new JsonObject();
		t.addProperty("id", id);
		t.addProperty("coin", coin);
		t.addProperty("buy", isBuy);
		t.addProperty("total", total);
		t.addProperty("filled", filled);
		t.addProperty("start", start);
		t.addProperty("minutes", minutes);
		return t;
	}

	public static JsonObject funding(long time, String coin, double usdc, double rate) {
		JsonObject f = new JsonObject();
		f.addProperty("t", time);
		f.addProperty("coin", coin);
		f.addProperty("usdc", usdc);
		f.addProperty("rate", rate);
		return f;
	}

	/** A market's name as players see it: "xyz:AAPL" → "AAPL", "HYPE/USDC" → "HYPE". */
	public static String market(String name) {
		String n = name.endsWith("/USDC") ? name.substring(0, name.length() - 5) : name;
		int colon = n.indexOf(':');
		return colon >= 0 ? n.substring(colon + 1) : n;
	}

	public static JsonObject leverage(int lev, boolean cross) {
		JsonObject l = new JsonObject();
		l.addProperty("lev", lev);
		l.addProperty("cross", cross);
		return l;
	}

	public static JsonObject fill(long time, String coin, double size, double price, double pnl) {
		JsonObject f = new JsonObject();
		f.addProperty("t", time);
		f.addProperty("coin", coin);
		f.addProperty("size", size);
		f.addProperty("px", price);
		f.addProperty("pnl", pnl);
		return f;
	}
}
