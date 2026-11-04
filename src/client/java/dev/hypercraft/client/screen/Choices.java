package dev.hypercraft.client.screen;

import dev.hypercraft.block.SensorMode;
import dev.hypercraft.block.SensorReading;
import dev.hypercraft.block.SensorTest;
import dev.hypercraft.client.ui.HcDropdown.Option;
import dev.hypercraft.trading.AccountMetric;
import java.util.ArrayList;
import java.util.List;

/** Grouped option lists for the dropdowns block screens share. */
final class Choices {
	private Choices() {}

	static List<Option<SensorMode>> sensorModes() {
		List<Option<SensorMode>> out = new ArrayList<>();
		group(out, "Price", SensorMode.PRICE_ABOVE, SensorMode.PRICE_BELOW, SensorMode.RISING, SensorMode.FALLING, SensorMode.ANALOG);
		group(out, "Funding & book", SensorMode.FUNDING_ABOVE, SensorMode.FUNDING_BELOW, SensorMode.SPREAD_BELOW, SensorMode.BOOK_TILT);
		group(out, "My position", SensorMode.IN_LONG, SensorMode.IN_SHORT, SensorMode.PNL_ABOVE, SensorMode.PNL_BELOW, SensorMode.FILLED,
				SensorMode.OPEN_ORDERS, SensorMode.MARGIN_USAGE, SensorMode.EXPOSURE);
		group(out, "My account", SensorMode.ACCOUNT_ABOVE, SensorMode.ACCOUNT_BELOW, SensorMode.ACCOUNT_ANALOG, SensorMode.ACCOUNT_RISE, SensorMode.ACCOUNT_DROP);
		group(out, "Signal card", SensorMode.SIGNAL_ABOVE, SensorMode.SIGNAL_BELOW, SensorMode.SIGNAL_ANALOG);
		group(out, "Exchange clock", SensorMode.MARKET_OPEN, SensorMode.MARKET_BELL);
		return out;
	}

	static List<Option<SensorReading>> sensorReadings() {
		List<Option<SensorReading>> out = new ArrayList<>();
		readings(out, "Market", SensorReading.PRICE, SensorReading.CHANGE, SensorReading.FUNDING, SensorReading.SPREAD, SensorReading.BOOK_TILT);
		readings(out, "Card's account", SensorReading.POSITION, SensorReading.SIDE, SensorReading.POSITION_PNL, SensorReading.OPEN_ORDERS, SensorReading.ACCOUNT, SensorReading.FILL);
		readings(out, "Other", SensorReading.SIGNAL, SensorReading.NYSE_OPEN, SensorReading.NYSE_BELL);
		return out;
	}

	private static void readings(List<Option<SensorReading>> out, String name, SensorReading... rs) {
		for (SensorReading r : rs) out.add(new Option<>(r, r.label, name, r.op.help + (r.event() ? " (an event: use the Pulse test)." : ".")));
	}

	/** An event can only pulse; a level can be tested every other way. */
	static List<Option<SensorTest>> sensorTests(boolean event) {
		List<Option<SensorTest>> out = new ArrayList<>();
		for (SensorTest t : SensorTest.values()) if ((t == SensorTest.PULSE) == event) out.add(new Option<>(t, t.label, "", t.help + "."));
		return out;
	}

	private static void group(List<Option<SensorMode>> out, String name, SensorMode... modes) {
		for (SensorMode m : modes) out.add(new Option<>(m, m.label, name, m.help + "."));
	}

	static List<Option<AccountMetric>> metrics() {
		List<Option<AccountMetric>> out = new ArrayList<>();
		metric(out, "Value", AccountMetric.EQUITY, "Everything the account is worth: cash plus open positions at mark");
		metric(out, "Value", AccountMetric.FREE, "Collateral not tied up as margin, free for new orders");
		metric(out, "Value", AccountMetric.RETURN, "Equity against what was deposited, in %");
		metric(out, "Profit", AccountMetric.PNL, "All-time profit and loss, $");
		metric(out, "Profit", AccountMetric.PNL_DAY, "Profit and loss over the last 24 hours, $");
		metric(out, "Profit", AccountMetric.PNL_WEEK, "Profit and loss over the last 7 days, $");
		metric(out, "Profit", AccountMetric.PNL_MONTH, "Profit and loss over the last 30 days, $");
		metric(out, "Profit", AccountMetric.UPNL, "Unrealized PnL of open positions, $");
		metric(out, "Profit", AccountMetric.REALIZED, "PnL locked in by closed trades, $");
		metric(out, "Profit", AccountMetric.FUNDING, "Funding paid (negative) or received over 24 hours, $");
		metric(out, "Risk", AccountMetric.MARGIN, "Margin held by open positions, $");
		metric(out, "Risk", AccountMetric.MARGIN_USAGE, "Margin as a share of equity, %");
		metric(out, "Risk", AccountMetric.LEVERAGE, "Gross exposure divided by equity, x");
		metric(out, "Risk", AccountMetric.EXPOSURE, "Size of all positions added up, either side, $");
		metric(out, "Risk", AccountMetric.NET_EXPOSURE, "Longs minus shorts, $");
		metric(out, "Activity", AccountMetric.POSITIONS, "How many positions are open");
		metric(out, "Activity", AccountMetric.ORDERS, "Resting orders; set a coin to count one market");
		metric(out, "Activity", AccountMetric.POSITION, "Position size in the coin above");
		return out;
	}

	private static void metric(List<Option<AccountMetric>> out, String group, AccountMetric m, String help) {
		out.add(new Option<>(m, m.label, group, help + "."));
	}
}
