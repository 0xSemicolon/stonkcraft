package dev.stonkcraft.signal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A Copy Desk: drives a paper account (the follower, through a trade card) toward a leader's target portfolio. The
 * leader is a signal, an account read through its card, or an address. Lives in the signal registry, so it keeps
 * copying while its block is unloaded.
 */
public final class CopyDesk {
	public enum Sizing { PROPORTIONAL, MIRROR }

	public enum OnLost { HOLD, FLATTEN }

	public enum Redstone {
		IGNORE("Ignore redstone"), PAUSE("Pause while powered"), RUN("Run only while powered");

		public final String label;

		Redstone(String label) {
			this.label = label;
		}
	}

	public UUID id = UUID.randomUUID();
	@Nullable
	public UUID owner;
	public String ownerName = "";
	public long pos;
	public String dim = "minecraft:overworld";

	/** The follower's card (must be TRADE level). */
	@Nullable
	public SignalNode.Input follower;
	/** A signal or account card; null when following {@link #address}. */
	@Nullable
	public SignalNode.Input leader;
	public String address = "";

	public boolean running;
	public Sizing sizing = Sizing.PROPORTIONAL;
	public double ratio = 1;
	public double maxLeverage = 2;
	public double bandPct = 2;
	public double bandMin = 10;
	public double maxStep = 5000;
	public int interval = 10;
	public double drawdownPct;
	public OnLost onLost = OnLost.HOLD;
	public Redstone redstone = Redstone.IGNORE;
	public boolean powered;

	// state
	public double peakEquity;
	public long lastStep;
	public String status = "";
	/** Tracking error as a fraction of follower equity. */
	public double tracking;
	public int markets;
	public boolean ordered;

	public void upgrade() {
		if (sizing == null) sizing = Sizing.PROPORTIONAL;
		if (onLost == null) onLost = OnLost.HOLD;
		if (redstone == null) redstone = Redstone.IGNORE;
		if (address == null) address = "";
		if (status == null) status = "";
		interval = Math.max(3, interval);
	}

	/** Why the desk shouldn't trade this step, or null when it may. */
	@Nullable
	public String paused() {
		if (!running) return "stopped";
		if (redstone == Redstone.PAUSE && powered) return "paused by redstone";
		if (redstone == Redstone.RUN && !powered) return "waiting for redstone";
		return null;
	}

	/** One rebalance order: signed USD (positive buys), reduce-only when it only shrinks a position. */
	public record Trade(String coin, double usd, boolean reduceOnly) {}

	/** Leverage to set on a coin before trading it. */
	public record Leverage(String coin, int leverage) {}

	public record Plan(List<Trade> trades, List<Leverage> leverage, Map<String, Double> targets, double trackingError) {}

	/**
	 * Target notional per coin, then the orders that move toward it this step.
	 *
	 * @param current     coin -> {signed size, mark}
	 * @param leverageNow coin -> leverage currently set on the follower
	 * @param maxLevOf    coin -> the market's max leverage (0 when unknown)
	 */
	public Plan plan(SignalValue.Portfolio target, double equity, Map<String, double[]> current, Map<String, Integer> leverageNow,
			java.util.function.ToIntFunction<String> maxLevOf, int maxOrders) {
		double base = sizing == Sizing.MIRROR ? target.equity() : equity;
		Map<String, Double> tgt = new LinkedHashMap<>();
		double gross = 0;
		for (Map.Entry<String, Double> e : target.weights().entrySet()) {
			double n = e.getValue() * base * ratio;
			if (!Double.isFinite(n)) continue;
			tgt.put(e.getKey(), n);
			gross += Math.abs(n);
		}
		double cap = maxLeverage * Math.max(0, equity);
		if (gross > cap && gross > 0) {
			double k = cap / gross;
			tgt.replaceAll((c, n) -> n * k);
			gross = cap;
		}
		List<Trade> trades = new ArrayList<>();
		List<String> coins = new ArrayList<>(tgt.keySet());
		for (String c : current.keySet()) if (!coins.contains(c)) coins.add(c);
		double off = 0;
		List<double[]> diffs = new ArrayList<>();
		for (int i = 0; i < coins.size(); i++) {
			String c = coins.get(i);
			double[] pos = current.get(c);
			double cur = pos == null ? 0 : pos[0] * pos[1];
			double want = tgt.getOrDefault(c, 0.0);
			double diff = want - cur;
			off += Math.abs(diff);
			// the band is relative to each market's own target, so a book of many small positions still gets copied
			double band = Math.max(bandMin, bandPct / 100 * Math.abs(want));
			if (Math.abs(diff) >= band) diffs.add(new double[]{i, diff, cur, want});
		}
		diffs.sort((x, y) -> Double.compare(Math.abs(y[1]), Math.abs(x[1])));
		List<Leverage> lev = new ArrayList<>();
		double grossLev = equity > 0 ? gross / equity : 0;
		for (double[] d : diffs) {
			if (trades.size() >= maxOrders) break;
			String c = coins.get((int) d[0]);
			double diff = d[1], cur = d[2], want = d[3];
			boolean towardZero = cur != 0 && Math.signum(diff) != Math.signum(cur);
			if (towardZero && Math.signum(want) != Math.signum(cur) && want != 0) {
				// flipping sides: close what's there first, open the other side next step
				trades.add(new Trade(c, Math.copySign(Math.min(Math.abs(cur), maxStep), diff), true));
				continue;
			}
			trades.add(new Trade(c, Math.copySign(Math.min(Math.abs(diff), maxStep), diff), towardZero));
			if (!towardZero) {
				int max = maxLevOf.applyAsInt(c);
				int need = (int) Math.ceil(Math.max(1, grossLev * 1.25));
				if (max > 0) need = Math.min(need, max);
				Integer now = leverageNow.get(c);
				if (now == null || now < need) lev.add(new Leverage(c, need));
			}
		}
		return new Plan(trades, lev, tgt, equity > 0 ? off / equity : 0);
	}
}
