package dev.hypercraft.trading;

import java.util.Locale;

/** What an order should do relative to the current position. */
public enum Side {
	/** Add a long of N USD (or reduce a short). */
	BUY("Buy", 0x3fe0a6),
	/** Add a short of N USD (or reduce a long). */
	SELL("Sell", 0xff5c6c),
	/** Flatten the position. */
	CLOSE("Close", 0xf2c94c),
	/** Target a long of N USD; no-op if already long. Idempotent, ideal for redstone levels. */
	LONG("Go Long", 0x5ef0c3),
	/** Target a short of N USD; no-op if already short. */
	SHORT("Go Short", 0xff8a7a),
	/** Close the position and open the same size the other way. */
	REVERSE("Reverse", 0xc792ea);

	public final String label;
	public final int color;

	Side(String label, int color) {
		this.label = label;
		this.color = color;
	}

	public boolean usesAmount() {
		return this != CLOSE && this != REVERSE;
	}

	public static Side parse(String s) {
		try {
			return valueOf(s.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return BUY;
		}
	}

	/**
	 * Signed size delta to send given the current signed position, mid price and USD amount.
	 * Returns 0 when nothing should be done.
	 */
	public double delta(double currentSize, double mid, double usd) {
		double target = usd / mid;
		return switch (this) {
			case BUY -> target;
			case SELL -> -target;
			case CLOSE -> -currentSize;
			case LONG -> currentSize > 0 ? 0 : target - currentSize;
			case SHORT -> currentSize < 0 ? 0 : -target - currentSize;
			case REVERSE -> -2 * currentSize;
		};
	}
}
