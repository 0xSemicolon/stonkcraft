package dev.stonkcraft.client.screen;

import com.google.gson.JsonObject;
import dev.stonkcraft.block.SensorMode;
import dev.stonkcraft.block.SensorReading;
import dev.stonkcraft.block.SensorTest;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcDropdown;
import dev.stonkcraft.client.ui.HcField;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.trading.AccountMetric;
import dev.stonkcraft.trading.Fmt;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/** A sensor is a reading and a test; the preset menu sets both at once, as the old single mode menu did. */
public class SensorScreen extends BlockConfigScreen {
	private SensorReading reading;
	private SensorTest test;
	private AccountMetric metric;
	private boolean abs;
	private double a, b, window;
	private HcField aBox, bBox, windowBox;
	private int previewY;

	public SensorScreen(JsonObject data) {
		super("Market Sensor", data);
		reading = SensorReading.parse(data.get("reading").getAsString());
		test = SensorTest.parse(data.get("test").getAsString());
		metric = AccountMetric.parse(data.has("metric") ? data.get("metric").getAsString() : "PNL_DAY");
		abs = data.has("abs") && data.get("abs").getAsBoolean();
		a = data.get("a").getAsDouble();
		b = data.get("b").getAsDouble();
		window = data.has("window") ? data.get("window").getAsDouble() : 60;
	}

	@Override
	protected int panelWidth() {
		return 320;
	}

	@Override
	protected boolean usesCoin() {
		return reading.coin || reading == SensorReading.SIGNAL || reading == SensorReading.ACCOUNT && (metric.perCoin || metric == AccountMetric.ORDERS);
	}

	@Override
	protected String blankCoinMeaning() {
		return reading == SensorReading.SIGNAL ? "reads gross leverage" : reading == SensorReading.ACCOUNT || reading == SensorReading.OPEN_ORDERS ? "every market" : "pick a market";
	}

	/** Keeps what's typed when the layout is rebuilt (a preset or reading change). */
	private void keepNumbers() {
		if (aBox != null) a = parse(aBox, a);
		if (bBox != null) b = parse(bBox, b);
		if (windowBox != null) window = parse(windowBox, window);
	}

	private List<HcDropdown.Option<String>> presets(SensorMode current) {
		List<HcDropdown.Option<String>> out = new ArrayList<>();
		if (current == null) out.add(new HcDropdown.Option<>("", "Custom", "", "Your own reading and test"));
		for (HcDropdown.Option<SensorMode> o : Choices.sensorModes()) out.add(new HcDropdown.Option<>(o.value().name(), o.label(), o.group(), o.help()));
		return out;
	}

	@Override
	protected void fields(Flow f) {
		keepNumbers();
		SensorMode current = SensorMode.matching(reading, test, abs, metric);
		int y = f.row("Preset", ROW);
		f.add(new HcDropdown<>(f.left + 58, y, 150, ROW, presets(current), current == null ? "" : current.name(), key -> {
			if (key.isEmpty()) return;
			keepNumbers();
			SensorMode.Setup s = SensorMode.valueOf(key).setup(a, b);
			reading = s.reading();
			test = s.test();
			a = s.a();
			b = s.b();
			abs = s.abs();
			if (s.reading() == SensorReading.CHANGE || s.test().windowed()) window = s.window();
			if (s.metric() != null) metric = s.metric();
			// the preset's numbers win over what the old fields held
			aBox = bBox = windowBox = null;
			relayout();
		}));
		y = f.row("Reads", ROW);
		f.add(new HcDropdown<>(f.left + 58, y, 150, ROW, Choices.sensorReadings(), reading, r -> {
			keepNumbers();
			reading = r;
			if (r.event()) test = SensorTest.PULSE;
			else if (test == SensorTest.PULSE) test = SensorTest.AT_LEAST;
			relayout();
		}));
		if (!reading.event() && reading != SensorReading.NYSE_OPEN) {
			f.add(new HcButton(f.left + 214, y, 92, ROW, (abs ? "■" : "□") + " Either side", btn -> {
				abs = !abs;
				btn.label((abs ? "■" : "□") + " Either side");
			}).tip("Test the reading's size, ignoring whether it's + or -"));
		}
		if (reading == SensorReading.ACCOUNT) {
			y = f.row("Figure", ROW);
			f.add(new HcDropdown<>(f.left + 58, y, 150, ROW, Choices.metrics(), metric, m -> {
				keepNumbers();
				metric = m;
				relayout();
			}));
		}
		y = f.row("Test", ROW);
		f.add(new HcDropdown<>(f.left + 58, y, 150, ROW, Choices.sensorTests(reading.event()), test, t -> {
			keepNumbers();
			test = t;
			relayout();
		}));
		aBox = bBox = windowBox = null;
		if (test.aLabel != null) {
			y = f.row(test.aLabel, 14);
			aBox = numField(f, f.left + 58, y, 90, test.aLabel, a, step());
			if (!reading.unit.isEmpty()) aBox.suffix(reading.unit);
			if (reading == SensorReading.PRICE) {
				f.add(new HcButton(f.left + 154, y, 50, 14, "= now", btn -> {
					ClientState.Ticker t = ClientState.ticker(coinBox.getValue());
					if (t != null) aBox.setValue(Fmt.size(Double.parseDouble(String.format(java.util.Locale.ROOT, "%.6g", t.mid))));
				}).tip("Use the current price"));
			}
		}
		if (test.bLabel != null) {
			y = f.row(test.bLabel, 14);
			bBox = numField(f, f.left + 58, y, 90, test.bLabel + " ", b, step());
			if (!reading.unit.isEmpty()) bBox.suffix(reading.unit);
		}
		if (reading == SensorReading.CHANGE || test.windowed()) {
			y = f.row("Window", 14);
			windowBox = numField(f, f.left + 58, y, 90, "Window", window, 30);
			windowBox.suffix("s");
		}
		String extra = reading == SensorReading.SIGNAL ? " Right-click the sensor with a Signal Card. Set a coin to read that coin's weight instead of gross leverage."
				: reading == SensorReading.NYSE_BELL || reading == SensorReading.NYSE_OPEN ? " Wire it to a bell for the real opening and closing bell."
				: reading.card ? " Reads the Watch or Trade Card in the sensor." : "";
		f.text(test.help + "." + extra + " Print card makes a Signal Card of the reading.", Ui.DIM);
		previewY = f.reserve(10);
	}

	private double step() {
		return switch (reading) {
			case CHANGE, FUNDING -> 0.5;
			case SPREAD, OPEN_ORDERS, SIDE -> 1;
			case BOOK_TILT -> 0.05;
			default -> 0;
		};
	}

	@Override
	protected String summary() {
		return "Emits redstone: " + condition();
	}

	/** The sensor's condition with what's typed now, like the block's label. */
	private String condition() {
		String c = ClientState.display(coin());
		String subject = switch (reading) {
			case ACCOUNT -> metric.label + (metric.perCoin && !c.isEmpty() ? " " + c : "");
			case SIGNAL -> "Signal";
			case NYSE_OPEN, NYSE_BELL -> reading.label;
			default -> (c.isEmpty() ? "" : c + " ") + reading.label;
		};
		double av = aBox == null ? a : parse(aBox, a), bv = bBox == null ? b : parse(bBox, b);
		return subject + (abs ? " (either side)" : "") + switch (test) {
			case AT_LEAST -> " ≥ " + Fmt.size(av);
			case AT_MOST -> " ≤ " + Fmt.size(av);
			case ANALOG -> " " + Fmt.size(av) + "→" + Fmt.size(bv);
			case RISES -> " rises " + Fmt.size(Math.abs(av));
			case FALLS -> " falls " + Fmt.size(Math.abs(av));
			case PULSE -> " (pulse)";
		};
	}

	@Override
	protected void writeFields(JsonObject out) {
		keepNumbers();
		out.addProperty("reading", reading.name());
		out.addProperty("test", test.name());
		out.addProperty("a", a);
		out.addProperty("b", b);
		out.addProperty("window", window);
		out.addProperty("abs", abs);
		out.addProperty("metric", metric.name());
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		super.drawContent(g, mx, my);
		int left = PAD, y = previewY;
		boolean clientCan = !abs && (reading == SensorReading.PRICE || reading == SensorReading.FUNDING) && !test.windowed();
		if (!clientCan) {
			String now = data.has("readingNow") ? data.get("readingNow").getAsString() : "";
			boolean fresh = !now.isEmpty() && data.get("reading").getAsString().equals(reading.name());
			Ui.text(g, font, Ui.ellipsize(font, fresh ? "Right now: " + now : "Right now: shown after you save", pw - PAD * 2), left, y, fresh ? Ui.TEXT : Ui.DIM);
			return;
		}
		// live preview for readings the client knows itself
		ClientState.Ticker t = coinBox == null ? null : ClientState.ticker(coinBox.getValue());
		if (t == null) {
			Ui.text(g, font, "Right now: pick a market", left, y, Ui.DIM);
			return;
		}
		double v = reading == SensorReading.PRICE ? t.mid : t.fundingApr();
		int power = test.power(v, aBox == null ? a : parse(aBox, a), bBox == null ? b : parse(bBox, b), Double.NaN);
		Ui.text(g, font, "Right now:", left, y, Ui.DIM);
		int bx = left + font.width("Right now: ") + 2;
		for (int i = 0; i < 15; i++) g.fill(bx + i * 5, y, bx + i * 5 + 4, y + 8, i < power ? 0xFFFF3030 : 0xFF2A1515);
		Ui.text(g, font, power > 0 ? "ON (" + power + ")" : "OFF", bx + 80, y, power > 0 ? Ui.GREEN : Ui.DIM);
	}
}
