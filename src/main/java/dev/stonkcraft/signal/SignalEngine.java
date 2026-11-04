package dev.stonkcraft.signal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Evaluates signals. Pure: everything outside (accounts, prices, the clock) comes through {@link Env}, so it runs the
 * same in game and in tests. One {@link #tick} evaluates every signal once; DELAY and SMOOTH keep their state on the
 * node between ticks.
 */
public final class SignalEngine {
	/** Deepest chain of signals evaluated. */
	public static final int MAX_DEPTH = 16;

	/** The world as signals see it. */
	public interface Env {
		@Nullable
		SignalNode node(UUID id);

		/** An account input's portfolio, or NONE if its card no longer works. */
		SignalValue account(SignalNode.Input in);

		/** An account input's figure (an AccountMetric name; per-coin figures read {@code market}), NaN when unavailable. */
		double metric(SignalNode.Input in, String metric, String market);

		/** An account input's position in a market in USD (+ long, - short), NaN when unavailable. */
		default double position(SignalNode.Input in, String market) {
			return Double.NaN;
		}

		/** An account input's unrealized PnL on a market, NaN when unavailable. */
		default double positionPnl(SignalNode.Input in, String market) {
			return Double.NaN;
		}

		/** Open orders of an account input on a market ("" = all), -1 when unavailable. */
		default int openOrders(SignalNode.Input in, String market) {
			return -1;
		}

		/** When an account input's latest fill on a market happened (epoch ms; 0 = never), -1 when unavailable. */
		default long lastFill(SignalNode.Input in, String market) {
			return -1;
		}

		default double spreadBps(String market) {
			return Double.NaN;
		}

		/** The bid side's share of the top 10 book levels, NaN without a book. */
		default double bookTilt(String market) {
			return Double.NaN;
		}

		/** A Copy Desk's tracking error, NaN if it's gone. */
		default double deskTracking(String deskId) {
			return Double.NaN;
		}

		SignalValue address(String address);

		/** Canonical market name, or null if unknown. */
		@Nullable
		String resolve(String market);

		double mid(String market);

		double changePct(String market, long windowMs);

		double fundingApr(String market);

		boolean marketOpen(long nowMs);

		long now();
	}

	private SignalEngine() {}

	/** Evaluates every node once, sharing results; returns id -> value. */
	public static Map<UUID, SignalValue> tick(Iterable<SignalNode> nodes, Env env) {
		Map<UUID, SignalValue> memo = new HashMap<>();
		for (SignalNode n : nodes) evaluate(n, env, memo, new HashSet<>(), 0);
		return memo;
	}

	public static SignalValue evaluate(SignalNode n, Env env, Map<UUID, SignalValue> memo, Set<UUID> visiting, int depth) {
		SignalValue cached = memo.get(n.id);
		if (cached != null) return cached;
		SignalValue v;
		if (depth > MAX_DEPTH || !visiting.add(n.id)) {
			v = SignalValue.NONE;
		} else {
			try {
				v = compute(n, env, memo, visiting, depth);
			} catch (RuntimeException e) {
				v = SignalValue.NONE;
			}
			visiting.remove(n.id);
		}
		if (v == null) v = SignalValue.NONE;
		memo.put(n.id, v);
		n.last = v;
		return v;
	}

	private static SignalValue input(SignalNode.Input in, Env env, Map<UUID, SignalValue> memo, Set<UUID> visiting, int depth) {
		if (in.account()) return env.account(in);
		UUID id = in.uuid();
		SignalNode src = id == null ? null : env.node(id);
		return src == null ? SignalValue.NONE : evaluate(src, env, memo, visiting, depth + 1);
	}

	private static SignalValue compute(SignalNode n, Env env, Map<UUID, SignalValue> memo, Set<UUID> visiting, int depth) {
		List<SignalValue> in = new ArrayList<>();
		for (SignalNode.Input i : n.inputs) in.add(input(i, env, memo, visiting, depth));
		if (in.size() < n.op.minInputs) return SignalValue.NONE;
		SignalValue first = in.isEmpty() ? SignalValue.NONE : in.get(0);
		return switch (n.op) {
			case ADDRESS -> env.address(n.text.trim());
			case BASKET -> basket(n.text, env);
			case PRICE -> scalar(env.mid(market(n.text, env)));
			case CHANGE -> scalar(env.changePct(market(n.text, env), (long) (Math.max(1, Math.min(900, n.a)) * 1000)));
			case FUNDING -> scalar(env.fundingApr(market(n.text, env)));
			case METRIC -> {
				if (!n.inputs.get(0).account()) yield SignalValue.NONE;
				// a mixer can name a per-coin figure as "POSITION:BTC"
				String fig = n.text.trim().toUpperCase(Locale.ROOT), coin = n.market;
				int colon = fig.indexOf(':');
				if (colon > 0 && coin.isBlank()) {
					coin = market(n.text.trim().substring(colon + 1), env);
					fig = fig.substring(0, colon);
				}
				yield scalar(env.metric(n.inputs.get(0), fig, coin.isBlank() ? "" : market(coin, env)));
			}
			case POSITION, SIDE, POSITION_PNL, OPEN_ORDERS, FILL -> account(n, env);
			case SPREAD -> scalar(env.spreadBps(market(n.text, env)));
			case BOOK_TILT -> scalar(env.bookTilt(market(n.text, env)));
			case BELL -> bell(n, env);
			case DESK -> scalar(env.deskTracking(n.text.trim()));
			// "nothing yet" is an event too, so a watcher's baseline is set before the first order
			case ORDER -> n.pushed instanceof SignalValue.Event ? n.pushed : new SignalValue.Event(0, 0, "");
			case DELTA -> delta(n, first, env.now());
			case REDSTONE -> new SignalValue.Scalar(n.redstone);
			case CONSTANT -> new SignalValue.Scalar(n.a);
			case MARKET_HOURS -> new SignalValue.Scalar(env.marketOpen(env.now()) ? 1 : 0);
			case BLEND -> blend(in, n.inputs);
			case SCALE -> {
				double k = in.size() > 1 ? number(in.get(1)) : n.a;
				yield Double.isNaN(k) ? SignalValue.NONE : times(first, k);
			}
			case INVERT -> times(first, -1);
			case FILTER -> first instanceof SignalValue.Portfolio p ? filter(p, n.text, n.a != 0, (int) n.b, env) : SignalValue.NONE;
			case TOP -> first instanceof SignalValue.Portfolio p ? top(p, (int) Math.max(1, n.a)) : SignalValue.NONE;
			case CAP -> first instanceof SignalValue.Portfolio p ? cap(p, n.a, n.b) : SignalValue.NONE;
			case DELAY -> delay(n, first, env.now());
			case SMOOTH -> smooth(n, first, env.now());
			case GATE -> {
				double g = in.size() > 1 ? number(in.get(1)) : Double.NaN;
				if (Double.isNaN(g) || first.none()) yield SignalValue.NONE;
				yield g > n.a ? first : zero(first);
			}
			case COMPARE -> {
				double v = number(first);
				yield Double.isNaN(v) ? SignalValue.NONE : new SignalValue.Scalar((n.b != 0 ? v <= n.a : v >= n.a) ? 1 : 0);
			}
			case MAP -> {
				double v = number(first);
				if (Double.isNaN(v) || n.b == n.a) yield SignalValue.NONE;
				yield new SignalValue.Scalar(Math.max(0, Math.min(1, (v - n.a) / (n.b - n.a))));
			}
			case PICK -> {
				if (!(first instanceof SignalValue.Portfolio p)) yield SignalValue.NONE;
				String what = n.text.trim();
				if (what.equalsIgnoreCase("GROSS") || what.isEmpty()) yield new SignalValue.Scalar(p.gross());
				if (what.equalsIgnoreCase("NET")) yield new SignalValue.Scalar(p.net());
				yield new SignalValue.Scalar(p.weight(market(what, env)));
			}
		};
	}

	/** The account-reading sources; their one input must be an account card. */
	private static SignalValue account(SignalNode n, Env env) {
		SignalNode.Input in = n.inputs.get(0);
		if (!in.account()) return SignalValue.NONE;
		String m = n.text.isBlank() ? "" : market(n.text, env);
		switch (n.op) {
			case POSITION:
				return m.isEmpty() ? SignalValue.NONE : scalar(env.position(in, m));
			case SIDE: {
				double p = m.isEmpty() ? Double.NaN : env.position(in, m);
				return Double.isNaN(p) ? SignalValue.NONE : new SignalValue.Scalar(Math.signum(p));
			}
			case POSITION_PNL: {
				double p = m.isEmpty() ? Double.NaN : env.position(in, m);
				// flat has no PnL to test, as a sensor's PnL modes always treated it
				return Double.isNaN(p) || p == 0 ? SignalValue.NONE : scalar(env.positionPnl(in, m));
			}
			case OPEN_ORDERS: {
				int c = env.openOrders(in, m);
				return c < 0 ? SignalValue.NONE : new SignalValue.Scalar(c);
			}
			case FILL: {
				long at = m.isEmpty() ? -1 : env.lastFill(in, m);
				return at < 0 ? SignalValue.NONE : new SignalValue.Event(at, at, at == 0 ? "" : "fill on " + bare(m));
			}
			default:
				return SignalValue.NONE;
		}
	}

	/** An event at each opening and closing bell; the node remembers whether the exchange was open. */
	private static SignalValue bell(SignalNode n, Env env) {
		boolean open = env.marketOpen(env.now());
		// events are stamped with the wall clock, which is what freshness is judged by (env.now() may be shifted)
		long wall = System.currentTimeMillis();
		if (n.wasOpen != null && n.wasOpen != open) n.bell = new SignalValue.Event(wall, wall, open ? "opening bell" : "closing bell");
		n.wasOpen = open;
		return n.bell == null ? new SignalValue.Event(0, 0, "") : n.bell;
	}

	/** The input now minus the input {@code a} seconds ago; nothing until there's that much history. */
	private static SignalValue delta(SignalNode n, SignalValue v, long now) {
		double x = number(v);
		if (n.history == null) n.history = new ArrayDeque<>();
		if (!Double.isNaN(x)) n.history.addLast(new Object[]{now, x});
		long window = (long) (Math.max(1, Math.min(3600, n.a)) * 1000);
		// keep one sample at or before the window's start
		while (n.history.size() > 1) {
			Object[] head = n.history.pollFirst();
			if ((long) n.history.peekFirst()[0] > now - window) {
				n.history.addFirst(head);
				break;
			}
		}
		Object[] first = n.history.peekFirst();
		if (Double.isNaN(x) || first == null || (long) first[0] > now - window) return SignalValue.NONE;
		return new SignalValue.Scalar(x - (double) first[1]);
	}

	private static String bare(String market) {
		return market.contains(":") ? market.substring(market.indexOf(':') + 1) : market;
	}

	private static String market(String text, Env env) {
		String r = env.resolve(text.trim());
		return r == null ? text.trim() : r;
	}

	private static SignalValue scalar(double v) {
		return Double.isFinite(v) ? new SignalValue.Scalar(v) : SignalValue.NONE;
	}

	/** A scalar's value (an event counts as 1 while fresh); NaN for anything else. */
	private static double number(SignalValue v) {
		if (v instanceof SignalValue.Event e) return v.asNumber("");
		return v instanceof SignalValue.Scalar s ? s.value() : Double.NaN;
	}

	private static SignalValue zero(SignalValue like) {
		if (like instanceof SignalValue.Event) return new SignalValue.Event(0, 0, "");
		return like instanceof SignalValue.Portfolio p ? SignalValue.Portfolio.empty(p.equity()) : new SignalValue.Scalar(0);
	}

	private static SignalValue times(SignalValue v, double k) {
		if (v instanceof SignalValue.Scalar s) return new SignalValue.Scalar(s.value() * k);
		if (v instanceof SignalValue.Portfolio p) return p.scaled(k);
		return SignalValue.NONE;
	}

	/** "BTC:0.5, ETH:-0.25" (markets resolved; unknown ones dropped). */
	static SignalValue basket(String text, Env env) {
		Map<String, Double> w = new LinkedHashMap<>();
		for (String part : text.split("[,;]")) {
			String[] kv = part.trim().split("[:=\\s]+");
			if (kv.length < 2) continue;
			String coin = String.join(":", java.util.Arrays.copyOf(kv, kv.length - 1));
			String m = env.resolve(coin);
			if (m == null) continue;
			try {
				w.merge(m, Double.parseDouble(kv[kv.length - 1]), Double::sum);
			} catch (NumberFormatException ignored) {
			}
		}
		return w.isEmpty() ? SignalValue.NONE : new SignalValue.Portfolio(w, 1);
	}

	private static SignalValue blend(List<SignalValue> in, List<SignalNode.Input> inputs) {
		boolean portfolio = false, scalar = false;
		// one input passes through untouched at weight 1 (so an event survives); events don't add up
		if (in.size() == 1 && inputs.get(0).weight == 1 && in.get(0) instanceof SignalValue.Event) return in.get(0);
		for (SignalValue v : in) {
			if (v.none() || v instanceof SignalValue.Event) return SignalValue.NONE;
			if (v instanceof SignalValue.Portfolio) portfolio = true;
			else scalar = true;
		}
		if (portfolio && scalar) return SignalValue.NONE;
		if (scalar) {
			double sum = 0;
			for (int i = 0; i < in.size(); i++) sum += inputs.get(i).weight * number(in.get(i));
			return new SignalValue.Scalar(sum);
		}
		Map<String, Double> w = new LinkedHashMap<>();
		double eq = 0, k = 0;
		for (int i = 0; i < in.size(); i++) {
			SignalValue.Portfolio p = (SignalValue.Portfolio) in.get(i);
			double wt = inputs.get(i).weight;
			p.weights().forEach((c, x) -> w.merge(c, wt * x, Double::sum));
			eq += Math.abs(wt) * p.equity();
			k += Math.abs(wt);
		}
		w.values().removeIf(x -> Math.abs(x) < 1e-12);
		return new SignalValue.Portfolio(w, k == 0 ? 0 : eq / k);
	}

	/** Market names match exactly or by bare ticker ("GOLD" matches "xyz:GOLD"). */
	private static SignalValue filter(SignalValue.Portfolio p, String list, boolean drop, int side, Env env) {
		Set<String> names = new HashSet<>();
		for (String s : list.split("[,;\\s]+")) {
			if (s.isBlank()) continue;
			String r = env.resolve(s);
			names.add((r == null ? s : r).toUpperCase(Locale.ROOT));
		}
		Map<String, Double> w = new LinkedHashMap<>();
		p.weights().forEach((c, x) -> {
			boolean listed = names.contains(c.toUpperCase(Locale.ROOT));
			if (!names.isEmpty() && listed == drop) return;
			if (side == 1 && x < 0 || side == 2 && x > 0) return;
			w.put(c, x);
		});
		return new SignalValue.Portfolio(w, p.equity());
	}

	private static SignalValue top(SignalValue.Portfolio p, int n) {
		List<Map.Entry<String, Double>> e = new ArrayList<>(p.weights().entrySet());
		e.sort((x, y) -> Double.compare(Math.abs(y.getValue()), Math.abs(x.getValue())));
		Map<String, Double> w = new LinkedHashMap<>();
		for (int i = 0; i < Math.min(n, e.size()); i++) w.put(e.get(i).getKey(), e.get(i).getValue());
		return new SignalValue.Portfolio(w, p.equity());
	}

	private static SignalValue cap(SignalValue.Portfolio p, double gross, double perCoin) {
		Map<String, Double> w = new LinkedHashMap<>();
		p.weights().forEach((c, x) -> w.put(c, perCoin > 0 ? Math.max(-perCoin, Math.min(perCoin, x)) : x));
		SignalValue.Portfolio out = new SignalValue.Portfolio(w, p.equity());
		double g = out.gross();
		return gross > 0 && g > gross ? out.scaled(gross / g) : out;
	}

	private static SignalValue delay(SignalNode n, SignalValue v, long now) {
		if (n.history == null) n.history = new ArrayDeque<>();
		long window = (long) (Math.max(0, Math.min(3600, n.a)) * 1000);
		n.history.addLast(new Object[]{now, v});
		Object[] chosen = null;
		while (!n.history.isEmpty() && (long) n.history.peekFirst()[0] <= now - window) chosen = n.history.pollFirst();
		if (chosen != null) {
			n.history.addFirst(chosen);
			return (SignalValue) chosen[1];
		}
		return window == 0 ? v : SignalValue.NONE;
	}

	private static SignalValue smooth(SignalNode n, SignalValue v, long now) {
		if (v.none()) return n.ema == null ? SignalValue.NONE : n.ema;
		double halfLife = Math.max(0.001, n.a);
		if (n.ema == null || n.ema.getClass() != v.getClass()) {
			n.ema = v;
			n.emaAt = now;
			return v;
		}
		double alpha = 1 - Math.pow(0.5, Math.max(0, now - n.emaAt) / 1000.0 / halfLife);
		n.emaAt = now;
		if (v instanceof SignalValue.Scalar s) {
			n.ema = new SignalValue.Scalar(number(n.ema) + alpha * (s.value() - number(n.ema)));
		} else {
			SignalValue.Portfolio prev = (SignalValue.Portfolio) n.ema, cur = (SignalValue.Portfolio) v;
			Map<String, Double> w = new LinkedHashMap<>();
			Set<String> keys = new HashSet<>(prev.weights().keySet());
			keys.addAll(cur.weights().keySet());
			for (String c : keys) {
				double x = prev.weight(c) + alpha * (cur.weight(c) - prev.weight(c));
				if (Math.abs(x) > 1e-9) w.put(c, x);
			}
			n.ema = new SignalValue.Portfolio(w, prev.equity() + alpha * (cur.equity() - prev.equity()));
		}
		return n.ema;
	}

	/** Whether {@code candidate} (or anything it reads) is {@code target}: adding it as an input would make a loop. */
	public static boolean dependsOn(SignalNode candidate, UUID target, java.util.function.Function<UUID, SignalNode> nodes) {
		ArrayDeque<SignalNode> todo = new ArrayDeque<>();
		Set<UUID> seen = new HashSet<>();
		todo.add(candidate);
		while (!todo.isEmpty()) {
			SignalNode n = todo.poll();
			if (n.id.equals(target)) return true;
			if (!seen.add(n.id) || seen.size() > 256) continue;
			for (SignalNode.Input in : n.inputs) {
				if (in.account()) continue;
				UUID id = in.uuid();
				SignalNode next = id == null ? null : nodes.apply(id);
				if (next != null) todo.add(next);
			}
		}
		return false;
	}

	/** A short description of a value for labels: "1.23" or "BTC +0.50 · ETH -0.20". */
	public static String describe(SignalValue v) {
		if (v instanceof SignalValue.Scalar s) return dev.stonkcraft.trading.Fmt.size(s.value());
		if (v instanceof SignalValue.Event e) {
			if (e.at() <= 0) return "nothing yet";
			long s = Math.max(0, (System.currentTimeMillis() - e.at()) / 1000);
			return e.what() + " · " + (s < 60 ? s + "s ago" : s < 3600 ? s / 60 + "m ago" : s / 3600 + "h ago");
		}
		if (v instanceof SignalValue.Portfolio p) {
			if (p.weights().isEmpty()) return "flat";
			List<Map.Entry<String, Double>> e = new ArrayList<>(p.weights().entrySet());
			e.sort((x, y) -> Double.compare(Math.abs(y.getValue()), Math.abs(x.getValue())));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(3, e.size()); i++) {
				if (i > 0) sb.append(" · ");
				String c = e.get(i).getKey();
				sb.append(c.contains(":") ? c.substring(c.indexOf(':') + 1) : c).append(String.format(Locale.ROOT, " %+.2f×", e.get(i).getValue())); // × equity
			}
			if (e.size() > 3) sb.append(" · ").append(e.size() - 3).append(" more");
			return sb.toString();
		}
		return "--";
	}
}
