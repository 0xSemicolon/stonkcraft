package dev.stonkcraft.signal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a signal evaluates to, once a second: a number, a target portfolio, or nothing (an input is unavailable:
 * revoked card, unknown market, no price yet). Sinks hold still on nothing.
 */
public sealed interface SignalValue {
	None NONE = new None();

	record None() implements SignalValue {}

	record Scalar(double value) implements SignalValue {}

	/**
	 * Something happened: a fill, a bell, an executor's order. {@code seq} changes once per occurrence (it's the
	 * occurrence's time, so it never repeats across restarts); {@code at} is when it happened, epoch ms.
	 */
	record Event(long seq, long at, String what) implements SignalValue {
		/** Long enough for a sensor ticking twice a second and a registry ticking once a second to both see it. */
		public static final long FRESH_MS = 1500;

		public boolean fresh(long now) {
			return at > 0 && now - at < FRESH_MS;
		}
	}

	/**
	 * Target weights, coin -> signed fraction of equity (+0.5 = long half of equity), and the equity (USD) of whatever
	 * it came from, so a follower can mirror absolute sizes.
	 */
	record Portfolio(Map<String, Double> weights, double equity) implements SignalValue {
		public static Portfolio empty(double equity) {
			return new Portfolio(Map.of(), equity);
		}

		public double gross() {
			double g = 0;
			for (double w : weights.values()) g += Math.abs(w);
			return g;
		}

		public double net() {
			double n = 0;
			for (double w : weights.values()) n += w;
			return n;
		}

		public double weight(String coin) {
			Double w = weights.get(coin);
			return w == null ? 0 : w;
		}

		public Portfolio scaled(double k) {
			Map<String, Double> out = new LinkedHashMap<>();
			weights.forEach((c, w) -> {
				if (w * k != 0) out.put(c, w * k);
			});
			return new Portfolio(out, equity);
		}
	}

	default boolean none() {
		return this instanceof None;
	}

	/**
	 * A number for sensors and executors: a scalar's value, a portfolio's gross leverage (or one coin's weight), or 1
	 * just after an event and 0 otherwise.
	 */
	default double asNumber(String coin) {
		if (this instanceof Scalar s) return s.value();
		if (this instanceof Event e) return e.fresh(System.currentTimeMillis()) ? 1 : 0;
		if (this instanceof Portfolio p) return coin == null || coin.isBlank() ? p.gross() : p.weight(coin);
		return Double.NaN;
	}
}
