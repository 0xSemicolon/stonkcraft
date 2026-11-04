package dev.hypercraft.hl;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Hyperliquid number rules: wire strings, tick-size price rounding and lot-size rounding. */
public final class HlFormat {
	private HlFormat() {}

	/** Same as the python SDK's float_to_wire: at most 8 decimals, no trailing zeros, no exponent. */
	public static String toWire(double x) {
		BigDecimal rounded = new BigDecimal(x).setScale(8, RoundingMode.HALF_EVEN);
		if (Math.abs(rounded.doubleValue() - x) >= 1e-12) {
			throw new IllegalArgumentException("toWire would round " + x);
		}
		if (rounded.signum() == 0) return "0";
		return rounded.stripTrailingZeros().toPlainString();
	}

	/** Perp prices: at most 5 significant figures and at most (6 - szDecimals) decimals. */
	public static double roundPerpPrice(double px, int szDecimals) {
		BigDecimal sig = new BigDecimal(px).round(new MathContext(5, RoundingMode.HALF_EVEN));
		return sig.setScale(Math.max(0, 6 - szDecimals), RoundingMode.HALF_EVEN).doubleValue();
	}

	/** Spot prices: at most 5 significant figures and at most (8 - szDecimals) decimals. */
	public static double roundSpotPrice(double px, int szDecimals) {
		BigDecimal sig = new BigDecimal(px).round(new MathContext(5, RoundingMode.HALF_EVEN));
		return sig.setScale(Math.max(0, 8 - szDecimals), RoundingMode.HALF_EVEN).doubleValue();
	}

	public static double roundSize(double sz, int szDecimals) {
		return BigDecimal.valueOf(sz).setScale(szDecimals, RoundingMode.DOWN).doubleValue();
	}
}
