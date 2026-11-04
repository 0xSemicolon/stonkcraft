package dev.stonkcraft.trading;

/** Hyperliquid's fee tiers (§7 of the UI review): a rolling 14-day volume sets the rate, and spot volume counts double. */
public final class FeeSchedule {
	private FeeSchedule() {}

	/** Volume above which each tier starts. */
	private static final double[] VOLUME = {0, 5e6, 25e6, 100e6, 500e6, 2e9, 7e9};
	private static final double[] PERP_TAKER = {0.00045, 0.00040, 0.00035, 0.00030, 0.00028, 0.00026, 0.00024};
	private static final double[] PERP_MAKER = {0.00015, 0.00012, 0.00008, 0.00004, 0, 0, 0};
	private static final double[] SPOT_TAKER = {0.00070, 0.00060, 0.00050, 0.00040, 0.00035, 0.00030, 0.00025};
	private static final double[] SPOT_MAKER = {0.00040, 0.00030, 0.00020, 0.00010, 0, 0, 0};
	public static final int WINDOW_DAYS = 14;
	public static final int SPOT_VOLUME_WEIGHT = 2;

	public static int tier(double volume14d) {
		int t = 0;
		while (t + 1 < VOLUME.length && volume14d > VOLUME[t + 1]) t++;
		return t;
	}

	/** Volume that reaches the next tier, or NaN at the top. */
	public static double nextTierVolume(int tier) {
		return tier + 1 < VOLUME.length ? VOLUME[tier + 1] : Double.NaN;
	}

	public static double rate(int tier, boolean spot, boolean maker) {
		int t = Math.max(0, Math.min(VOLUME.length - 1, tier));
		return (spot ? (maker ? SPOT_MAKER : SPOT_TAKER) : maker ? PERP_MAKER : PERP_TAKER)[t];
	}
}
