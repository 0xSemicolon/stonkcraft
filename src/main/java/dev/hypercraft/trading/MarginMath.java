package dev.hypercraft.trading;

import java.util.Map;

/**
 * Hyperliquid's margin rules as pure sums, shared by the paper engine and the terminal's order form so the estimate a
 * player sees is the sum the server runs. See docs/research/2026-10-31-hyperliquid-ui-review.md §6.
 */
public final class MarginMath {
	private MarginMath() {}

	/** Below this margin ratio's reciprocal a position's in the backstop's hands: equity under 2/3 of maintenance margin. */
	public static final double BACKSTOP_SHARE = 2.0 / 3.0;
	/** Positions over this notional go to the book a fifth at a time. */
	public static final double PARTIAL_OVER_USD = 100_000;
	public static final double PARTIAL_STEP = 0.2;
	public static final long PARTIAL_COOLDOWN_MS = 30_000;

	/** A market's second margin tier: above {@code above} notional, max leverage is {@code lev}. */
	private record Tier(double above, int lev) {}

	private static final Tier T_20M = new Tier(20_000_000, 5);
	private static final Tier T_3M = new Tier(3_000_000, 5);
	private static final Map<String, Tier> TIERS = Map.ofEntries(
			Map.entry("BTC", new Tier(150_000_000, 20)), Map.entry("ETH", new Tier(100_000_000, 15)),
			Map.entry("SOL", new Tier(70_000_000, 10)), Map.entry("XRP", new Tier(40_000_000, 10)),
			Map.entry("AAVE", T_20M), Map.entry("ADA", T_20M), Map.entry("APT", T_20M), Map.entry("AVAX", T_20M), Map.entry("BCH", T_20M),
			Map.entry("CRV", T_20M), Map.entry("DOGE", T_20M), Map.entry("ENA", T_20M), Map.entry("FARTCOIN", T_20M), Map.entry("HYPE", T_20M),
			Map.entry("kBONK", T_20M), Map.entry("kPEPE", T_20M), Map.entry("LINK", T_20M), Map.entry("LTC", T_20M), Map.entry("NEAR", T_20M),
			Map.entry("PUMP", T_20M), Map.entry("SUI", T_20M), Map.entry("TRUMP", T_20M), Map.entry("UNI", T_20M), Map.entry("WLD", T_20M),
			Map.entry("ZEC", T_20M),
			Map.entry("ARB", T_3M), Map.entry("BNB", T_3M), Map.entry("DOT", T_3M), Map.entry("JUP", T_3M), Map.entry("kSHIB", T_3M),
			Map.entry("MKR", T_3M), Map.entry("ONDO", T_3M), Map.entry("PAXG", T_3M), Map.entry("TON", T_3M), Map.entry("TRX", T_3M),
			Map.entry("XPL", T_3M));

	/** The most leverage a position of this notional may hold: the market's maximum, or its upper tier's above the bound. */
	public static int maxLeverage(String coin, int marketMax, double notional) {
		Tier t = TIERS.get(coin);
		return t != null && notional > t.above ? Math.min(marketMax, t.lev) : marketMax;
	}

	/** Initial margin at the player's leverage, which a large position's tier can cap. */
	public static double initialMargin(String coin, int marketMax, int leverage, double notional) {
		return notional / Math.max(1, Math.min(leverage, maxLeverage(coin, marketMax, notional)));
	}

	/**
	 * Maintenance margin: half of initial margin at max leverage, tiered as {@code notional × mmr − deduction}, so it's
	 * continuous across a tier bound.
	 */
	public static double maintenance(String coin, int marketMax, double notional) {
		double mmr0 = 1.0 / (2.0 * Math.max(1, marketMax));
		Tier t = TIERS.get(coin);
		if (t == null || notional <= t.above || t.lev >= marketMax) return notional * mmr0;
		double mmr1 = 1.0 / (2.0 * t.lev);
		return notional * mmr1 - t.above * (mmr1 - mmr0);
	}

	/** The rate a price move changes maintenance margin at: the tier this notional sits in. */
	public static double maintenanceRate(String coin, int marketMax, double notional) {
		return 1.0 / (2.0 * maxLeverage(coin, marketMax, notional));
	}

	/**
	 * §6's formula: {@code price − side × margin_available / size / (1 − l × side)}, with {@code l} the maintenance rate.
	 * {@code marginAvailable} is account value less maintenance margin (cross: the whole pool; isolated: the position).
	 *
	 * @return the mark price that takes margin available to zero, or NaN when no positive price does (risk is low)
	 */
	public static double liquidationPrice(double mark, double signedSize, double marginAvailable, double rate) {
		if (signedSize == 0 || !(mark > 0)) return Double.NaN;
		int side = signedSize > 0 ? 1 : -1;
		double liq = mark - side * marginAvailable / Math.abs(signedSize) / (1 - rate * side);
		return liq > 0 ? liq : Double.NaN;
	}
}
