package dev.hypercraft.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hypercraft.trading.AccountMetric;
import org.junit.jupiter.api.Test;

/** Every mode a sensor was saved with turns into a reading and a test that give the same redstone. */
class SensorModeTest {
	private static final double[] SAMPLES = {-200_000, -500, -3, -1, -0.4, 0, 0.3, 0.62, 1, 3, 50, 99, 150, 60_000, 120_000};
	private static final double[][] PARAMS = {{100, 200}, {-3, 5}, {0.5, 0.9}, {0, 100}, {60_000, 130_000}};

	private static int on(boolean b) {
		return b ? 15 : 0;
	}

	private static int clamp(double x) {
		return (int) Math.max(0, Math.min(15, Math.round(x)));
	}

	/**
	 * What the sensor did before the split, for one natural input {@code x}: the price, the change %, the funding,
	 * the position (USD), the PnL, an order count, margin usage %, the spread, the book's bid share, an account figure,
	 * a signal's value, or 1/0 for the exchange being open. {@code ch} is the account figure's change.
	 */
	private static int old(SensorMode m, double x, double a, double b) {
		return switch (m) {
			case PRICE_ABOVE, FUNDING_ABOVE, ACCOUNT_ABOVE, SIGNAL_ABOVE -> on(x >= a);
			case PRICE_BELOW, FUNDING_BELOW, ACCOUNT_BELOW, SIGNAL_BELOW, SPREAD_BELOW -> on(x <= a);
			case RISING -> on(x >= Math.abs(a));
			case FALLING -> on(x <= -Math.abs(a));
			case ANALOG -> b > a ? clamp((x - a) / (b - a) * 15) : 0;
			case ACCOUNT_ANALOG, SIGNAL_ANALOG -> b != a ? clamp((x - a) / (b - a) * 15) : 0;
			case IN_LONG -> on(x > 0);
			case IN_SHORT -> on(x < 0);
			case PNL_ABOVE -> on(x >= a);
			case PNL_BELOW -> on(x <= a);
			case OPEN_ORDERS -> on(x >= Math.max(1, a));
			case MARGIN_USAGE -> clamp(x / 100 * 15);
			case EXPOSURE -> a <= 0 ? 0 : clamp(Math.abs(x) / a * 15);
			case BOOK_TILT -> clamp(x * 15);
			case ACCOUNT_RISE -> on(x >= Math.abs(a));
			case ACCOUNT_DROP -> on(x <= -Math.abs(a));
			case MARKET_OPEN -> on(x > 0);
			case FILLED, MARKET_BELL -> 0;
		};
	}

	/** The new reading for the same natural input: Side is the position's sign, the bell an open flag as 1/0. */
	private static double reading(SensorMode m, SensorMode.Setup s, double x) {
		if (s.reading() == SensorReading.SIDE) return Math.signum(x);
		if (m == SensorMode.MARKET_OPEN) return x > 0 ? 1 : 0;
		return s.abs() ? Math.abs(x) : x;
	}

	@Test
	void everyModeKeepsItsRedstone() {
		for (SensorMode m : SensorMode.values()) {
			for (double[] p : PARAMS) {
				SensorMode.Setup s = m.setup(p[0], p[1]);
				if (m == SensorMode.FILLED || m == SensorMode.MARKET_BELL) {
					assertEquals(SensorTest.PULSE, s.test(), m.name());
					assertTrue(s.reading().event(), m.name());
					continue;
				}
				for (double x : SAMPLES) {
					// rises and falls test the change over the window, which is x here
					double change = s.test().windowed() ? x : Double.NaN;
					int now = s.test().power(reading(m, s, x), s.a(), s.b(), change);
					assertEquals(old(m, x, p[0], p[1]), now, m + " a=" + p[0] + " b=" + p[1] + " x=" + x);
				}
			}
		}
	}

	@Test
	void marginUsageReadsTheAccountFigure() {
		assertEquals(AccountMetric.MARGIN_USAGE, SensorMode.MARGIN_USAGE.setup(0, 0).metric());
		assertEquals(SensorMode.MARGIN_USAGE, SensorMode.matching(SensorReading.ACCOUNT, SensorTest.ANALOG, false, AccountMetric.MARGIN_USAGE));
		assertEquals(SensorMode.ACCOUNT_ANALOG, SensorMode.matching(SensorReading.ACCOUNT, SensorTest.ANALOG, false, AccountMetric.EQUITY));
	}

	@Test
	void noReadingIsOffForEveryTest() {
		for (SensorTest t : SensorTest.values()) assertEquals(0, t.power(Double.NaN, -1e9, 1e9, 5), t.name());
	}
}
