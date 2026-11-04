package dev.stonkcraft.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stonkcraft.client.ClientNet;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcDropdown;
import dev.stonkcraft.client.ui.HcField;
import dev.stonkcraft.client.ui.HcScreen;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.signal.SignalOp;
import dev.stonkcraft.trading.Fmt;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Signal Mixer: pick an op, fill in its parameters, weight the inputs (cards right-clicked into the block), watch
 * the live value, and print Signal Cards to feed other blocks. An empty mixer offers templates.
 */
public class SignalMixerScreen extends HcScreen {
	private static final int ACCENT = 0xFFD08CFF;
	public static JsonObject info = new JsonObject();
	private final long pos;
	private SignalOp op;
	private HcField nameBox, textBox, aBox, bBox, watchBox;
	private double aVal, bVal;
	private final List<HcField> weightBoxes = new ArrayList<>();
	private long lastAsk, seenResult;
	private int valueY, readingY;

	public SignalMixerScreen(JsonObject data) {
		super("Signal Mixer");
		info = data;
		pos = data.get("pos").getAsLong();
		op = SignalOp.parse(data.has("op") ? data.get("op").getAsString() : "BLEND");
		aVal = data.has("a") ? data.get("a").getAsDouble() : 0;
		bVal = data.has("b") ? data.get("b").getAsDouble() : 0;
		seenResult = ClientState.lastVaultAt;
	}

	private JsonArray inputs() {
		return info.has("inputs") ? info.getAsJsonArray("inputs") : new JsonArray();
	}

	@Override
	protected int panelWidth() {
		return 360;
	}

	@Override
	protected int accent() {
		return ACCENT;
	}

	@Override
	protected String headerRight() {
		// a mixer holds no account; its chip names the signal it outputs
		return "≈ " + (info.has("name") ? info.get("name").getAsString() : "Signal");
	}

	@Override
	protected int headerRightColor() {
		return ACCENT;
	}

	@Override
	protected String status() {
		JsonArray ins = inputs();
		if (ins.size() < op.minInputs) return "⚠ " + op.label + " needs " + op.minInputs + " input" + (op.minInputs == 1 ? "" : "s") + ": right-click the mixer with a card";
		return "Outputs: " + valueSummary();
	}

	@Override
	protected int statusColor() {
		return inputs().size() < op.minInputs ? Ui.YELLOW : Ui.TEXT;
	}

	private String valueSummary() {
		String kind = info.has("valueKind") ? info.get("valueKind").getAsString() : "none";
		return switch (kind) {
			case "scalar" -> "the number " + Fmt.size(info.get("value").getAsDouble());
			case "portfolio" -> String.format("a portfolio, gross %.2fx", info.get("gross").getAsDouble());
			default -> "nothing yet";
		};
	}

	private List<HcDropdown.Option<SignalOp>> ops() {
		List<HcDropdown.Option<SignalOp>> out = new ArrayList<>();
		for (SignalOp o : SignalOp.values()) {
			// an address comes in as a Watch Card now; mixers already following one keep the op
			if (o == SignalOp.ADDRESS && op != SignalOp.ADDRESS || o.tapOnly && op != o) continue;
			String group = o.maxInputs == 0 || o.readsAccount() ? "Sources" : o == SignalOp.BLEND || o == SignalOp.SCALE || o == SignalOp.GATE ? "Combine" : "Transform";
			out.add(new HcDropdown.Option<>(o, o.label, group, o.help + "."));
		}
		out.sort(java.util.Comparator.comparingInt(o -> List.of("Sources", "Combine", "Transform").indexOf(o.group())));
		return out;
	}

	@Override
	protected void layout(Flow f) {
		int y = f.row("Name", 14);
		nameBox = f.add(field(f.left + 40, y, 120, "Name", info.has("name") ? info.get("name").getAsString() : "Signal"));
		nameBox.setMaxLength(24);
		nameBox.tip("Printed on the Signal Cards this mixer makes");
		f.add(new HcDropdown<>(f.left + f.width - 150, y - 2, 150, ROW, ops(), op, o -> {
			op = o;
			relayout();
		}));
		f.text(op.help + ".", ACCENT & 0xFFFFFF | 0xC0000000);
		textBox = aBox = bBox = null;
		if (op.textLabel != null) {
			y = f.row(op.textLabel, 14);
			textBox = f.add(field(f.left + 98, y, f.width - 98, "text " + op.textLabel, info.has("text") ? info.get("text").getAsString() : ""));
			textBox.setMaxLength(200);
			textBox.hint(switch (op) {
				case ADDRESS -> "0x…";
				case BASKET -> "BTC:0.5, ETH:0.3";
				case FILTER -> "BTC ETH";
				case PICK -> "BTC, GROSS or NET";
				case METRIC -> "EQUITY, PNL_DAY, POSITION:BTC…";
				case OPEN_ORDERS -> "blank = every market";
				default -> "e.g. BTC";
			});
		}
		param(f, op.aLabel, true);
		param(f, op.bLabel, false);
		readingY = -1;
		if (op == SignalOp.REDSTONE) readingY = f.reserve(10);

		f.rule();
		JsonArray ins = inputs();
		f.heading("Inputs " + ins.size() + "/" + Math.max(op.maxInputs, 1) + (op.maxInputs == 0 ? " · none needed" : ""));
		List<String> prev = new ArrayList<>();
		for (HcField w : weightBoxes) prev.add(w.getValue());
		weightBoxes.clear();
		if (op.maxInputs == 0) {
			f.text("This op is a source: it makes a signal on its own.", Ui.DIM);
		} else if (ins.isEmpty()) {
			f.text("Right-click the mixer with a Trade, Watch or Signal Card to add an input, or watch an address below.", Ui.DIM);
		}
		for (int i = 0; i < ins.size(); i++) {
			int idx = i;
			JsonObject in = ins.get(i).getAsJsonObject();
			boolean acct = "ACCOUNT".equals(in.get("kind").getAsString());
			String label = (i + 1) + ". " + (acct ? "◈ " : "◇ ") + in.get("name").getAsString()
					+ (acct ? ("READ".equals(in.get("level").getAsString()) ? " (Watch Card)" : " (Trade Card)") : " (signal)");
			y = f.row(null, 14);
			f.beside(y, 14, Ui.ellipsize(font, label, f.width - 130), acct ? Ui.ACCENT : ACCENT, f.left, f.width - 130);
			if (op == SignalOp.BLEND) {
				HcField w = f.add(field(f.left + f.width - 104, y, 78, "weight " + i, Fmt.size(in.get("weight").getAsDouble())));
				w.numeric(0.1).suffix("×").tip("How much this input counts. Negative fades it");
				if (i < prev.size()) w.setValue(prev.get(i));
				weightBoxes.add(w);
			}
			f.add(new HcButton(f.left + f.width - 20, y, 20, 14, "✕", HcButton.Style.DANGER, b -> {
				JsonObject o = new JsonObject();
				o.addProperty("pos", pos);
				o.addProperty("i", idx);
				ClientNet.send("mixer_eject", o);
			})).tip("Take this card back out");
		}
		watchBox = null;
		if (op.maxInputs > 0 && ins.size() < op.maxInputs) {
			// any trader's address, as a Watch Card straight into the next input
			y = f.row("or watch", 14);
			watchBox = f.add(field(f.left + 58, y, f.width - 58 - 56, "address", ""));
			watchBox.setMaxLength(42);
			watchBox.hint("0x… a trader's address");
			watchBox.validate(s -> s.isBlank() || s.trim().matches("0x[0-9a-fA-F]{40}") ? null : "An address is 0x followed by 40 hex characters");
			watchBox.tip("Any public trader's address");
			f.add(new HcButton(f.left + f.width - 52, y - 1, 52, 16, "Watch", b -> watch())).color(ACCENT)
					.tip("Prints a Watch Card for this address and adds it as an input");
		}
		if (ins.isEmpty() && op.maxInputs > 0) templates(f);

		f.rule();
		f.heading("Value now");
		valueY = f.reserve(30);
	}

	/** One numeric parameter, shown as a choice where the op uses 0/1/2 flags. */
	private void param(Flow f, String label, boolean isA) {
		if (label == null) return;
		double v = isA ? aVal : bVal;
		int y = f.row(label.replaceAll(" [0-9/]+$", "").replace("?", ""), ROW);
		String[] choices = label.endsWith("0/1/2") ? new String[]{"Both sides", "Longs only", "Shorts only"}
				: label.startsWith("Drop?") ? new String[]{"Keep listed", "Drop listed"}
				: label.startsWith("≤?") ? new String[]{"≥ threshold", "≤ threshold"} : null;
		if (choices != null) {
			int w = Math.min(90, (f.width - 98 - 4 * (choices.length - 1)) / choices.length);
			for (int i = 0; i < choices.length; i++) {
				int idx = i;
				f.add(new HcButton(f.left + 98 + i * (w + 4), y, w, ROW, choices[i], HcButton.Style.TAB, b -> {
					if (isA) aVal = idx;
					else bVal = idx;
					relayout();
				}).selected((int) v == i).color(ACCENT));
			}
			return;
		}
		HcField box = f.add(field(f.left + 98, y + 2, 90, "param " + label, Fmt.size(v)));
		box.numeric(label.endsWith(" s") || label.startsWith("Seconds") || label.startsWith("Half") ? 30 : label.equals("N") ? 1 : 0.1);
		if (label.endsWith(" s") || label.equals("Seconds") || label.startsWith("Half")) box.suffix("s");
		if (isA) aBox = box;
		else bBox = box;
	}

	private void watch() {
		String a = watchBox.getValue().trim();
		if (!a.matches("0x[0-9a-fA-F]{40}")) {
			toast("⚠ An address is 0x followed by 40 hex characters", Ui.YELLOW);
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("address", a);
		o.addProperty("pos", pos);
		ClientNet.send("watch_address", o);
		watchBox.setValue("");
	}

	private void templates(Flow f) {
		f.gap(2);
		f.heading("Or start from a template");
		String[][] t = {
				{"Follow a trader", "BLEND", "", "Paste their address into \"or watch\" and press Watch: their portfolio becomes the input"},
				{"Fade a trader", "INVERT", "", "Insert a trader's card: this outputs the opposite portfolio"},
				{"Gold 5-min momentum", "CHANGE", "xyz:GOLD", "Gold's % change over 5 minutes, for sensors and dials"},
				{"Average two traders", "BLEND", "", "Insert two cards and weight them 0.5 each"},
		};
		int half = (f.width - 6) / 2;
		for (int i = 0; i < t.length; i += 2) {
			int y = f.row(null, ROW);
			for (int j = 0; j < 2; j++) {
				String[] tp = t[i + j];
				f.add(new HcButton(f.left + j * (half + 6), y, half, ROW, tp[0], b -> {
					op = SignalOp.parse(tp[1]);
					if (op == SignalOp.CHANGE) bVal = 0;
					if (op == SignalOp.CHANGE) aVal = 300;
					relayout();
					if (textBox != null) textBox.setValue(tp[2]);
					if (op == SignalOp.CHANGE && aBox != null) aBox.setValue("300");
					if (nameBox.getValue().equals("Signal")) nameBox.setValue(tp[0]);
				}).color(ACCENT)).tip(tp[3]);
			}
		}
	}

	private JsonObject config() {
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos);
		o.addProperty("name", nameBox.getValue());
		o.addProperty("op", op.name());
		o.addProperty("text", textBox == null ? (info.has("text") ? info.get("text").getAsString() : "") : textBox.getValue());
		o.addProperty("a", aBox == null ? aVal : aBox.num(0));
		o.addProperty("b", bBox == null ? bVal : bBox.num(0));
		JsonArray w = new JsonArray();
		JsonArray ins = inputs();
		for (int i = 0; i < ins.size(); i++) w.add(i < weightBoxes.size() ? weightBoxes.get(i).num(0) : ins.get(i).getAsJsonObject().get("weight").getAsDouble());
		o.add("weights", w);
		return o;
	}

	@Override
	protected String snapshot() {
		return nameBox == null ? null : config().toString();
	}

	private void apply() {
		ClientNet.send("configure", config());
		markSaved();
	}

	@Override
	protected void save() {
		apply();
		chime();
		onClose();
	}

	@Override
	protected void footer(Footer f) {
		f.left("Print card", HcButton.Style.NORMAL, () -> {
			apply();
			JsonObject o = new JsonObject();
			o.addProperty("pos", pos);
			ClientNet.send("mixer_print", o);
		}).color(ACCENT).tip("Applies, then prints a Signal Card of this output into your inventory");
		f.right("Apply", HcButton.Style.NORMAL, () -> {
			apply();
			toast("✔ Applied", Ui.GREEN);
		}).tip("Saves without closing, so you can watch the value change");
		f.right("Cancel", HcButton.Style.NORMAL, this::onClose);
		f.primary("Save", this::save);
	}

	@Override
	public void tick() {
		super.tick();
		long now = System.currentTimeMillis();
		if (now - lastAsk > 1000) {
			lastAsk = now;
			JsonObject o = new JsonObject();
			o.addProperty("pos", pos);
			ClientNet.send("mixer_info", o);
		}
		if (ClientState.lastVaultAt > seenResult) {
			seenResult = ClientState.lastVaultAt;
			toast(ClientState.lastVaultMsg, ClientState.lastVaultOk ? Ui.GREEN : Ui.RED);
		}
	}

	/** Called when fresh info arrives; lays out again if the inputs changed. */
	public void onInfo(JsonObject data) {
		int before = inputs().size();
		info = data;
		if (inputs().size() != before) relayout();
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		if (readingY >= 0) Ui.text(g, font, "Reading " + (info.has("redstone") ? info.get("redstone").getAsInt() : 0) + " now", PAD, readingY, Ui.TEXT);
		int vy = valueY;
		String kind = info.has("valueKind") ? info.get("valueKind").getAsString() : "none";
		switch (kind) {
			case "scalar" -> Ui.textScaled(g, font, Fmt.size(info.get("value").getAsDouble()), PAD, vy + 2, 1.5f, Ui.TEXT);
			case "portfolio" -> {
				Ui.text(g, font, String.format("Portfolio · gross %.2fx · from %s equity", info.get("gross").getAsDouble(), Fmt.usdCompact(info.get("equity").getAsDouble())),
						PAD, vy, Ui.TEXT);
				// one bar per market: green long, red short, width by weight
				JsonArray ws = info.getAsJsonArray("weights");
				double max = 0;
				for (JsonElement el : ws) max = Math.max(max, Math.abs(el.getAsJsonArray().get(1).getAsDouble()));
				int cx = PAD, cy = vy + 11;
				for (JsonElement el : ws) {
					JsonArray r = el.getAsJsonArray();
					double w = r.get(1).getAsDouble();
					String s = ClientState.display(r.get(0).getAsString()) + String.format(" %+.2f×", w);
					int bar = max > 0 ? (int) Math.max(2, Math.abs(w) / max * 20) : 2;
					if (cx + font.width(s) + bar + 2 > pw - PAD) {
						cx = PAD;
						cy += 10;
						if (cy > vy + 22) break;
					}
					g.fill(cx, cy + 2, cx + bar, cy + 6, Ui.signColor(w));
					Ui.text(g, font, s, cx + bar + 2, cy, Ui.signColor(w));
					cx += font.width(s) + bar + 10;
				}
				if (ws.isEmpty()) Ui.text(g, font, "flat (no positions)", PAD, cy, Ui.DIM);
			}
			default -> Ui.text(g, font, "Nothing yet: an input is missing, revoked or has no price", PAD, vy + 2, Ui.YELLOW);
		}
	}
}
