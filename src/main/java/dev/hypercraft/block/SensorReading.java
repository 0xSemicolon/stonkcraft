package dev.hypercraft.block;

import dev.hypercraft.signal.SignalOp;
import java.util.Locale;

/** What a Market Sensor reads: one signal source, kept as the sensor's tap so a Signal Card can read it too. */
public enum SensorReading {
	PRICE("Price", SignalOp.PRICE, true, false, "$"),
	CHANGE("Change %", SignalOp.CHANGE, true, false, "%"),
	FUNDING("Funding APR", SignalOp.FUNDING, true, false, "%"),
	POSITION("Position $", SignalOp.POSITION, true, true, "$"),
	SIDE("Side", SignalOp.SIDE, true, true, ""),
	POSITION_PNL("Position PnL", SignalOp.POSITION_PNL, true, true, "$"),
	OPEN_ORDERS("Open orders", SignalOp.OPEN_ORDERS, true, true, ""),
	SPREAD("Spread", SignalOp.SPREAD, true, false, "bps"),
	BOOK_TILT("Book tilt", SignalOp.BOOK_TILT, true, false, ""),
	ACCOUNT("Account figure", SignalOp.METRIC, false, true, ""),
	FILL("Fill", SignalOp.FILL, true, true, ""),
	NYSE_OPEN("NYSE open", SignalOp.MARKET_HOURS, false, false, ""),
	NYSE_BELL("NYSE bell", SignalOp.BELL, false, false, ""),
	SIGNAL("Signal Card", SignalOp.BLEND, false, false, "");

	public final String label;
	public final SignalOp op;
	/** Reads a market: the coin row applies. */
	public final boolean coin;
	/** Reads the inserted Watch or Trade Card's account. */
	public final boolean card;
	/** Suffix for thresholds in the screen. */
	public final String unit;

	SensorReading(String label, SignalOp op, boolean coin, boolean card, String unit) {
		this.label = label;
		this.op = op;
		this.coin = coin;
		this.card = card;
		this.unit = unit;
	}

	/** Happens rather than has a level: only the Pulse test applies. */
	public boolean event() {
		return this == FILL || this == NYSE_BELL;
	}

	public static SensorReading parse(String s) {
		try {
			return valueOf(s.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException | NullPointerException e) {
			return PRICE;
		}
	}
}
