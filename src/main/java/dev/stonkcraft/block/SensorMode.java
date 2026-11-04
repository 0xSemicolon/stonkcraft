package dev.stonkcraft.block;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * The sensor's presets. A sensor is a reading and a test ({@link SensorReading}, {@link SensorTest}); each preset
 * sets both, and sensors saved before that split are converted through {@link #setup}. {@code a}/{@code b} are the
 * numbers the preset asks for, null when unused.
 */
public enum SensorMode {
	PRICE_ABOVE("Price ≥", "Price", null, "On while the mid price is at or above the threshold"),
	PRICE_BELOW("Price ≤", "Price", null, "On while the mid price is at or below the threshold"),
	RISING("Pumping", "Change %", "Window s", "On while price is up by at least the set % over the window (max 900s)"),
	FALLING("Dumping", "Change %", "Window s", "On while price is down by at least the set % over the window (max 900s)"),
	ANALOG("Analog", "Low price", "High price", "Signal strength 0-15 scales with price between low and high"),
	FUNDING_ABOVE("Funding ≥", "APR %", null, "On while annualized funding is at or above the set %"),
	FUNDING_BELOW("Funding ≤", "APR %", null, "On while annualized funding is at or below the set %"),
	IN_LONG("Long?", null, null, "On while the card's account holds a long position"),
	IN_SHORT("Short?", null, null, "On while the card's account holds a short position"),
	PNL_ABOVE("Position PnL ≥", "USD", null, "On while the card's account's unrealized PnL is at or above the set amount (take-profit)"),
	PNL_BELOW("Position PnL ≤", "USD", null, "On while the card's account's unrealized PnL is at or below the set amount (stop-loss, use negatives)"),
	FILLED("On fill", null, null, "Pulses whenever an order on this market fills. Chain trades off it"),
	OPEN_ORDERS("Orders ≥", "Count", null, "On while the account has at least the set number of open orders on this market"),
	MARGIN_USAGE("Margin", null, null, "Signal 0-15 is the share of equity tied up as margin (15 = all of it)"),
	EXPOSURE("Exposure", "Max USD", null, "Signal 0-15 is the position size as a share of Max USD, either side"),
	SPREAD_BELOW("Spread ≤", "bps", null, "On while the order book's bid-ask spread is at most the set number of basis points"),
	BOOK_TILT("Book tilt", null, null, "Signal 0-15 is the bid side's share of the top 10 book levels (8 = balanced)"),
	ACCOUNT_ABOVE("Account ≥", "Value", null, "On while the card account's chosen figure is at or above the value"),
	ACCOUNT_BELOW("Account ≤", "Value", null, "On while the card account's chosen figure is at or below the value (use negatives for losses)"),
	ACCOUNT_ANALOG("Account analog", "Low", "High", "Signal 0-15 scales the account's chosen figure between low and high"),
	ACCOUNT_RISE("Account rise", "By", "Window s", "On while the chosen figure has risen by at least the set amount over the window (max 3600s)"),
	ACCOUNT_DROP("Account drop", "By", "Window s", "On while the chosen figure has fallen by at least the set amount over the window (max 3600s), e.g. a drawdown alarm"),
	SIGNAL_ABOVE("Signal ≥", "Value", null, "On while the inserted Signal Card's value is at or above the set value (a portfolio reads as gross leverage, or the coin's weight)"),
	SIGNAL_BELOW("Signal ≤", "Value", null, "On while the inserted Signal Card's value is at or below the set value"),
	SIGNAL_ANALOG("Signal analog", "Low", "High", "Signal strength 0-15 scales the Signal Card's value between low and high"),
	MARKET_OPEN("NYSE open", null, null, "On while the New York Stock Exchange is open (09:30-16:00 New York time, trading days)"),
	MARKET_BELL("NYSE bell", null, null, "Pulses for a second at the opening and the closing bell");

	/** Reads the Signal Card in the card slot. */
	public boolean isSignal() {
		return name().startsWith("SIGNAL_");
	}

	/** Reads the exchange clock; no coin or card needed. */
	public boolean isClock() {
		return this == MARKET_OPEN || this == MARKET_BELL;
	}

	/** Reads one of the card account's figures (AccountMetric) rather than a market. */
	public boolean isAccount() {
		return name().startsWith("ACCOUNT_");
	}

	public final String label;
	@Nullable
	public final String aLabel;
	@Nullable
	public final String bLabel;
	public final String help;

	SensorMode(String label, @Nullable String aLabel, @Nullable String bLabel, String help) {
		this.label = label;
		this.aLabel = aLabel;
		this.bLabel = bLabel;
		this.help = help;
	}

	public static SensorMode parse(String s) {
		try {
			return valueOf(s.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return PRICE_ABOVE;
		}
	}

	public SensorMode next() {
		return values()[(ordinal() + 1) % values().length];
	}

	/** What a preset sets: reading, test, its numbers, the window in seconds, and the figure (null = keep). */
	public record Setup(SensorReading reading, SensorTest test, double a, double b, double window, boolean abs, @Nullable dev.stonkcraft.trading.AccountMetric metric) {}

	/** This preset as a reading and a test, carrying over the two numbers it was configured with. */
	public Setup setup(double a, double b) {
		return switch (this) {
			case PRICE_ABOVE -> s(SensorReading.PRICE, SensorTest.AT_LEAST, a, b, 60);
			case PRICE_BELOW -> s(SensorReading.PRICE, SensorTest.AT_MOST, a, b, 60);
			case RISING -> s(SensorReading.CHANGE, SensorTest.AT_LEAST, Math.abs(a), 0, b);
			case FALLING -> s(SensorReading.CHANGE, SensorTest.AT_MOST, -Math.abs(a), 0, b);
			case ANALOG -> s(SensorReading.PRICE, SensorTest.ANALOG, a, b, 60);
			case FUNDING_ABOVE -> s(SensorReading.FUNDING, SensorTest.AT_LEAST, a, b, 60);
			case FUNDING_BELOW -> s(SensorReading.FUNDING, SensorTest.AT_MOST, a, b, 60);
			case IN_LONG -> s(SensorReading.SIDE, SensorTest.AT_LEAST, 1, 0, 60);
			case IN_SHORT -> s(SensorReading.SIDE, SensorTest.AT_MOST, -1, 0, 60);
			case PNL_ABOVE -> s(SensorReading.POSITION_PNL, SensorTest.AT_LEAST, a, b, 60);
			case PNL_BELOW -> s(SensorReading.POSITION_PNL, SensorTest.AT_MOST, a, b, 60);
			case FILLED -> s(SensorReading.FILL, SensorTest.PULSE, a, b, 60);
			case OPEN_ORDERS -> s(SensorReading.OPEN_ORDERS, SensorTest.AT_LEAST, Math.max(1, a), 0, 60);
			case MARGIN_USAGE -> new Setup(SensorReading.ACCOUNT, SensorTest.ANALOG, 0, 100, 60, false, dev.stonkcraft.trading.AccountMetric.MARGIN_USAGE);
			case EXPOSURE -> new Setup(SensorReading.POSITION, SensorTest.ANALOG, 0, a, 60, true, null);
			case SPREAD_BELOW -> s(SensorReading.SPREAD, SensorTest.AT_MOST, a, b, 60);
			case BOOK_TILT -> s(SensorReading.BOOK_TILT, SensorTest.ANALOG, 0, 1, 60);
			case ACCOUNT_ABOVE -> s(SensorReading.ACCOUNT, SensorTest.AT_LEAST, a, b, 60);
			case ACCOUNT_BELOW -> s(SensorReading.ACCOUNT, SensorTest.AT_MOST, a, b, 60);
			case ACCOUNT_ANALOG -> s(SensorReading.ACCOUNT, SensorTest.ANALOG, a, b, 60);
			case ACCOUNT_RISE -> s(SensorReading.ACCOUNT, SensorTest.RISES, Math.abs(a), 0, b);
			case ACCOUNT_DROP -> s(SensorReading.ACCOUNT, SensorTest.FALLS, Math.abs(a), 0, b);
			case SIGNAL_ABOVE -> s(SensorReading.SIGNAL, SensorTest.AT_LEAST, a, b, 60);
			case SIGNAL_BELOW -> s(SensorReading.SIGNAL, SensorTest.AT_MOST, a, b, 60);
			case SIGNAL_ANALOG -> s(SensorReading.SIGNAL, SensorTest.ANALOG, a, b, 60);
			case MARKET_OPEN -> s(SensorReading.NYSE_OPEN, SensorTest.AT_LEAST, 1, 0, 60);
			case MARKET_BELL -> s(SensorReading.NYSE_BELL, SensorTest.PULSE, a, b, 60);
		};
	}

	private static Setup s(SensorReading r, SensorTest t, double a, double b, double window) {
		return new Setup(r, t, a, b, window, false, null);
	}

	/** The preset a setup came from, if it matches one exactly (for the screen's preset label), else null. */
	@Nullable
	public static SensorMode matching(SensorReading r, SensorTest t, boolean abs, dev.stonkcraft.trading.AccountMetric metric) {
		for (SensorMode m : values()) {
			Setup s = m.setup(0, 0);
			if (s.reading() == r && s.test() == t && s.abs() == abs && (s.metric() == null || s.metric() == metric)) return m;
		}
		return null;
	}
}
