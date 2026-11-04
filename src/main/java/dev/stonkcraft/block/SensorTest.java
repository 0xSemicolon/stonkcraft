package dev.stonkcraft.block;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** How a Market Sensor turns its reading into redstone. Pure, so the mapping from the old modes can be tested. */
public enum SensorTest {
	AT_LEAST("At least", "Value", null, "On (15) while the reading is at or above the value"),
	AT_MOST("At most", "Value", null, "On (15) while the reading is at or below the value"),
	ANALOG("Analog", "Low", "High", "Signal strength 0-15 scales with the reading between low and high"),
	RISES("Rises by", "By", null, "On while the reading has risen by at least the amount over the window"),
	FALLS("Falls by", "By", null, "On while the reading has fallen by at least the amount over the window"),
	PULSE("Pulse", null, null, "Pulses for a second each time the event happens");

	public final String label;
	@Nullable
	public final String aLabel, bLabel;
	public final String help;

	SensorTest(String label, @Nullable String aLabel, @Nullable String bLabel, String help) {
		this.label = label;
		this.aLabel = aLabel;
		this.bLabel = bLabel;
		this.help = help;
	}

	/** Uses the window (seconds) as well as its own numbers. */
	public boolean windowed() {
		return this == RISES || this == FALLS;
	}

	/**
	 * Redstone for a reading {@code v} (NaN = no reading), given the reading's change over the window (NaN until
	 * there's history). PULSE is handled by the sensor, which remembers events; here it's always 0.
	 */
	public int power(double v, double a, double b, double change) {
		if (Double.isNaN(v)) return 0;
		return switch (this) {
			case AT_LEAST -> v >= a ? 15 : 0;
			case AT_MOST -> v <= a ? 15 : 0;
			case ANALOG -> b != a ? (int) Math.max(0, Math.min(15, Math.round((v - a) / (b - a) * 15))) : 0;
			case RISES -> !Double.isNaN(change) && change >= Math.abs(a) ? 15 : 0;
			case FALLS -> !Double.isNaN(change) && change <= -Math.abs(a) ? 15 : 0;
			case PULSE -> 0;
		};
	}

	public static SensorTest parse(String s) {
		try {
			return valueOf(s.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException | NullPointerException e) {
			return AT_LEAST;
		}
	}
}
