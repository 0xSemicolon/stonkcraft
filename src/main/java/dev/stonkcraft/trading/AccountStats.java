package dev.stonkcraft.trading;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * One reading of an account's figures, shared by the Account Display (which shows them) and the Market Sensor
 * (which turns them into redstone). Works the same for paper, live and watch-only accounts.
 */
public final class AccountStats {
	public final TradingAccount account;
	public final JsonObject snap;
	public final double equity, upnl, start, gross, net;
	public final int positions;
	/** Exchange-reported PnL per period when known (live/watch), else NaN. */
	public final double pnlDay, pnlWeek, pnlMonth, pnlAll;
	public final boolean exactPnl;

	public AccountStats(TradingAccount account) {
		this.account = account;
		this.snap = account.snapshot();
		double eq = account.equity();
		this.equity = Double.isNaN(eq) ? 0 : eq;
		this.upnl = snap.get("upnl").getAsDouble();
		JsonArray hist = snap.getAsJsonArray("equityHistory");
		pnlDay = opt("pnlDay");
		pnlWeek = opt("pnlWeek");
		pnlMonth = opt("pnlMonth");
		pnlAll = opt("pnlAll");
		exactPnl = snap.has("start") || !Double.isNaN(pnlAll);
		if (snap.has("start")) start = snap.get("start").getAsDouble();
		else if (!Double.isNaN(pnlAll)) start = equity - pnlAll;
		else start = hist.isEmpty() ? equity : hist.get(0).getAsDouble();
		double g = 0, n = 0;
		int count = 0;
		for (JsonElement el : snap.getAsJsonArray("positions")) {
			JsonObject p = el.getAsJsonObject();
			double v = p.get("size").getAsDouble() * p.get("mark").getAsDouble();
			g += Math.abs(v);
			n += v;
			count++;
		}
		for (JsonElement el : snap.getAsJsonArray("balances")) {
			double v = el.getAsJsonObject().get("value").getAsDouble();
			g += Math.abs(v);
			n += v;
			count++;
		}
		gross = g;
		net = n;
		positions = count;
	}

	private double opt(String key) {
		return snap.has(key) ? snap.get(key).getAsDouble() : Double.NaN;
	}

	public double pnl() {
		return equity - start;
	}

	/** PnL over a rolling period, falling back to equity history for paper accounts. */
	private double periodPnl(double reported, long ms) {
		if (!Double.isNaN(reported)) return reported;
		if (snap.has("equityHistoryT")) {
			JsonArray t = snap.getAsJsonArray("equityHistoryT");
			JsonArray v = snap.getAsJsonArray("equityHistory");
			long since = System.currentTimeMillis() - ms;
			for (int i = 0; i < t.size(); i++) {
				if (t.get(i).getAsLong() >= since) return equity - v.get(i).getAsDouble();
			}
		}
		return 0;
	}

	public double realized() {
		if (snap.has("realized")) return snap.get("realized").getAsDouble();
		double r = 0;
		for (JsonElement el : snap.getAsJsonArray("fills")) r += el.getAsJsonObject().get("pnl").getAsDouble();
		return r;
	}

	public double funding24h() {
		double f = 0;
		long since = System.currentTimeMillis() - 86_400_000L;
		for (JsonElement el : snap.getAsJsonArray("funding")) {
			JsonObject o = el.getAsJsonObject();
			if (o.get("t").getAsLong() >= since) f += o.get("usdc").getAsDouble();
		}
		return f;
	}

	/** The figure as a number, for comparisons and analog signals. */
	public double value(AccountMetric m, String coin) {
		return switch (m) {
			case EQUITY -> equity;
			case PNL -> pnl();
			case PNL_DAY -> periodPnl(pnlDay, 86_400_000L);
			case PNL_WEEK -> periodPnl(pnlWeek, 7 * 86_400_000L);
			case PNL_MONTH -> periodPnl(pnlMonth, 30 * 86_400_000L);
			case RETURN -> start <= 0 ? 0 : pnl() / start * 100;
			case UPNL -> upnl;
			case REALIZED -> realized();
			case FREE -> account.withdrawable();
			case MARGIN -> account.marginUsed();
			case MARGIN_USAGE -> equity <= 0 ? 0 : account.marginUsed() / equity * 100;
			case LEVERAGE -> equity <= 0 ? 0 : gross / equity;
			case EXPOSURE -> gross;
			case NET_EXPOSURE -> net;
			case POSITIONS -> positions;
			case ORDERS -> account.openOrders(coin.isEmpty() ? null : coin);
			case FUNDING -> funding24h();
			case POSITION -> coin.isEmpty() ? 0 : account.positionSize(coin);
		};
	}

	/** Display text: {value, detail line, tone (1 good, -1 bad, 0 neutral)}. */
	public Object[] display(AccountMetric m, String coin) {
		double v = value(m, coin);
		String value, sub = "";
		int tone = 0;
		switch (m) {
			case EQUITY -> {
				value = Fmt.usd(v);
				sub = (exactPnl ? "start " : "since watching ") + Fmt.usdCompact(start);
				tone = (int) Math.signum(v - start);
			}
			case PNL, PNL_DAY, PNL_WEEK, PNL_MONTH -> {
				value = Fmt.usdSigned(v);
				double base = equity - v;
				sub = Fmt.pct(base <= 0 ? 0 : v / base * 100) + (m == AccountMetric.PNL && !exactPnl ? " since watching" : "");
				tone = (int) Math.signum(v);
			}
			case RETURN -> {
				value = Fmt.pct(v);
				sub = Fmt.usdSigned(pnl());
				tone = (int) Math.signum(v);
			}
			case UPNL -> {
				value = Fmt.usdSigned(v);
				sub = positions + " open · " + Fmt.pct(equity == 0 ? 0 : v / equity * 100) + " of equity";
				tone = (int) Math.signum(v);
			}
			case REALIZED -> {
				value = Fmt.usdSigned(v);
				sub = snap.has("fees") ? "fees " + Fmt.usd(snap.get("fees").getAsDouble()) : "recent fills";
				tone = (int) Math.signum(v);
			}
			case FREE -> {
				value = Fmt.usd(v);
				sub = String.format(Locale.ROOT, "%.0f%% of equity", equity <= 0 ? 0 : v / equity * 100);
			}
			case MARGIN -> {
				value = Fmt.usd(v);
				sub = "of " + Fmt.usdCompact(equity);
			}
			case MARGIN_USAGE -> {
				value = String.format(Locale.ROOT, "%.1f%%", v);
				sub = Fmt.usdCompact(account.marginUsed()) + " / " + Fmt.usdCompact(equity);
				tone = v >= 75 ? -1 : v >= 40 ? 0 : 1;
			}
			case LEVERAGE -> {
				value = String.format(Locale.ROOT, "%.2fx", v);
				sub = Fmt.usdCompact(gross) + " gross";
				tone = v >= 10 ? -1 : 0;
			}
			case EXPOSURE -> {
				value = Fmt.usdCompact(v);
				sub = "long " + Fmt.usdCompact((gross + net) / 2) + " / short " + Fmt.usdCompact((gross - net) / 2);
			}
			case NET_EXPOSURE -> {
				value = (v >= 0 ? "+" : "-") + Fmt.usdCompact(Math.abs(v));
				sub = v > 0 ? "net long" : v < 0 ? "net short" : "flat";
				tone = (int) Math.signum(v);
			}
			case POSITIONS -> {
				value = String.valueOf((int) v);
				sub = positions == 0 ? "flat" : Fmt.usdCompact(gross) + " gross";
			}
			case ORDERS -> {
				value = String.valueOf((int) v);
				sub = coin.isEmpty() ? "all markets" : coin;
			}
			case FUNDING -> {
				value = Fmt.usdSigned(v);
				sub = v >= 0 ? "received" : "paid";
				tone = (int) Math.signum(v);
			}
			case POSITION -> {
				if (coin.isEmpty()) {
					value = "set a coin";
				} else if (v == 0) {
					value = "flat";
					sub = coin;
				} else {
					double pu = account.unrealizedPnl(coin);
					value = (v > 0 ? "LONG " : "SHORT ") + Fmt.size(Math.abs(v));
					sub = coin + " · " + Fmt.usdSigned(pu);
					tone = (int) Math.signum(pu);
				}
			}
			default -> value = "";
		}
		return new Object[]{value, sub, tone};
	}

	/** Compact reading for a sensor's floating label. */
	public String brief(AccountMetric m, String coin) {
		return (String) display(m, coin)[0];
	}
}
