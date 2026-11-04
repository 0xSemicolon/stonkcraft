package dev.hypercraft.client.screen;

import com.google.gson.JsonObject;
import dev.hypercraft.client.ClientNet;
import dev.hypercraft.client.ClientState;
import dev.hypercraft.client.ui.HcButton;
import dev.hypercraft.client.ui.HcField;
import dev.hypercraft.client.ui.HcScreen;
import dev.hypercraft.client.ui.Ui;
import dev.hypercraft.trading.Fmt;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Copy Desk: who leads (a Trade or Watch Card; an address is watched into one), who follows (a Trade Card), how to size the copy and
 * when to stop. Start/Stop runs it; the desk keeps copying while its chunk is unloaded. Risk and timing settings
 * have working defaults and sit in a fold.
 */
public class CopyDeskScreen extends HcScreen {
	private static final int ACCENT = 0xFF7FD3FF;
	public static JsonObject info = new JsonObject();
	private final long pos;
	private HcField watchBox, ratio, maxLev, bandPct, bandMin, maxStep, interval, drawdown;
	private String sizing, onLost, redstone;
	private boolean more;
	private long lastAsk, seenResult;
	private int nowY;
	private String builtFor = "";

	public CopyDeskScreen(JsonObject data) {
		super("Copy Desk");
		info = data;
		pos = data.get("pos").getAsLong();
		sizing = str("sizing", "PROPORTIONAL");
		onLost = str("onLost", "HOLD");
		redstone = str("redstone", "IGNORE");
		more = num("drawdownPct") > 0 || !onLost.equals("HOLD") || !redstone.equals("IGNORE");
		seenResult = ClientState.lastVaultAt;
	}

	private String str(String k, String def) {
		return info.has(k) ? info.get(k).getAsString() : def;
	}

	private double num(String k) {
		return info.has(k) ? info.get(k).getAsDouble() : 0;
	}

	private boolean running() {
		return info.has("running") && info.get("running").getAsBoolean();
	}

	/** Copying an account onto itself does nothing; refuse it. */
	private boolean selfCopy() {
		return info.has("selfCopy") && info.get("selfCopy").getAsBoolean();
	}

	/** An address typed into desks from before Watch Cards; it still leads until a card replaces it. */
	private String legacyAddress() {
		return str("address", "");
	}

	private String problem() {
		if (str("follower", "").isEmpty()) return "Right-click your Trade Card in: it follows";
		if (str("leader", "").isEmpty() && legacyAddress().isBlank()) return "Pick a leader: a card, or watch an address";
		if (selfCopy()) return "Leader and follower are the same account: copying it onto itself does nothing";
		return null;
	}

	@Override
	protected int panelWidth() {
		return 340;
	}

	@Override
	protected int accent() {
		return ACCENT;
	}

	@Override
	protected String headerRight() {
		return running() ? "● COPYING" : "○ stopped";
	}

	@Override
	protected int headerRightColor() {
		return running() ? Ui.GREEN : Ui.DIM;
	}

	@Override
	protected String status() {
		String p = problem();
		if (p != null) return "⚠ " + p;
		String who = str("leader", "").isEmpty() ? shorten(legacyAddress()) : str("leader", "");
		return (running() ? "Copying " : "Ready to copy ") + who + " onto " + str("follower", "");
	}

	@Override
	protected int statusColor() {
		return problem() != null ? Ui.YELLOW : running() ? Ui.GREEN : Ui.TEXT;
	}

	private static String shorten(String addr) {
		return addr.length() > 12 ? addr.substring(0, 6) + "…" + addr.substring(addr.length() - 4) : addr;
	}

	private String shape() {
		return str("leader", "") + "|" + str("follower", "") + "|" + running();
	}

	@Override
	protected void layout(Flow f) {
		builtFor = shape();
		int fx = f.left + 96, fw = f.width - 96;
		String leader = str("leader", "");
		int y = f.row("Leader", 14);
		// the server already marks the leader ◈ account or ◇ signal
		String shown = !leader.isEmpty() ? leader : !legacyAddress().isBlank() ? "◇ " + shorten(legacyAddress()) + " (address)" : "a Trade or Watch Card";
		f.beside(y, 14, Ui.ellipsize(font, shown, fw - 26), leader.isEmpty() && legacyAddress().isBlank() ? Ui.DIM : Ui.TEXT, fx, fw - 26);
		if (!leader.isEmpty()) f.add(new HcButton(f.left + f.width - 20, y, 20, 14, "✕", HcButton.Style.DANGER, b -> eject("leader"))).tip("Take the leader's card out");
		String follower = str("follower", "");
		y = f.row("Follower", 14);
		f.beside(y, 14, Ui.ellipsize(font, follower.isEmpty() ? "⚠ right-click your Trade Card in" : "◈ " + follower, fw - 26), follower.isEmpty() ? Ui.YELLOW : Ui.ACCENT, fx, fw - 26);
		if (!follower.isEmpty()) f.add(new HcButton(f.left + f.width - 20, y, 20, 14, "✕", HcButton.Style.DANGER, b -> eject("follower"))).tip("Take the follower's card out");
		// any trader's address becomes a Watch Card, the one way a block reads an account
		y = f.row("or watch", 14);
		watchBox = f.add(field(fx, y, fw - 56, "address", ""));
		watchBox.setMaxLength(42);
		watchBox.hint("0x… any Hyperliquid address");
		watchBox.validate(s -> s.isBlank() || s.trim().matches("0x[0-9a-fA-F]{40}") ? null : "An address is 0x followed by 40 hex characters");
		watchBox.tip("Any Hyperliquid trader's address");
		f.add(new HcButton(fx + fw - 52, y - 1, 52, 16, "Watch", b -> watch())).color(ACCENT)
				.tip("Prints a Watch Card for this address and puts it in as the leader");

		f.rule();
		y = f.row("Sizing", ROW);
		int half = (fw - 4) / 2;
		f.add(new HcButton(fx, y, half, ROW, "Proportional", HcButton.Style.TAB, b -> {
			sizing = "PROPORTIONAL";
			relayout();
		}).selected(!sizing.equals("MIRROR")).color(ACCENT).tip("Same weights as the leader, scaled to your equity × ratio"));
		f.add(new HcButton(fx + half + 4, y, half, ROW, "Mirror", HcButton.Style.TAB, b -> {
			sizing = "MIRROR";
			relayout();
		}).selected(sizing.equals("MIRROR")).color(ACCENT).tip("The leader's position sizes × ratio, whatever your equity"));
		y = f.row("Ratio", 14);
		ratio = num(f, fx, y, 60, "ratio", "ratio", 0.1, "×");
		f.beside(y, 14, sizing.equals("MIRROR") ? "e.g. 0.1 = a tenth of the leader's sizes" : "e.g. 1 = the leader's leverage on your equity", 0xFF4E6E6A, fx + 66, fw - 66);

		f.add(new HcButton(f.left, f.row(null, ROW), f.width, ROW, (more ? "▾" : "▸") + " Risk & timing" + (more ? "" : "  (defaults work)"), b -> {
			more = !more;
			relayout();
		})).tip("Leverage cap, drawdown stop, how closely and how often it copies, and what redstone does");
		ratio.tip("Multiplies the copy");
		maxLev = bandPct = bandMin = maxStep = interval = drawdown = null;
		if (more) {
			y = f.row("Max leverage", 14);
			maxLev = num(f, fx, y, 60, "maxLeverage", "maxLev", 1, "x");
			maxLev.tip("Never exceeds this gross leverage. 0 = no cap");
			f.beside(y, 14, "0 = no cap", 0xFF4E6E6A, fx + 66, fw - 66);
			y = f.row("Drawdown stop", 14);
			drawdown = num(f, fx, y, 60, "drawdownPct", "drawdown", 1, "%");
			drawdown.hint("off").tip("Stops copying after losing this % of equity. 0 = off");
			f.beside(y, 14, "0 = off", 0xFF4E6E6A, fx + 66, fw - 66);
			y = f.row("Trade if off by", 14);
			bandPct = num(f, fx, y, 60, "bandPct", "bandPct", 1, "%");
			bandPct.tip("Only trades a market once it's this far from target, in % of the target");
			bandMin = num(f, fx + 66, y, 66, "bandMin", "bandMin", 10, "$ min");
			bandMin.tip("…and at least this many dollars off target");
			y = f.row("Step size", 14);
			maxStep = num(f, fx, y, 60, "maxStep", "maxStep", 500, "$");
			maxStep.tip("Largest single order it places");
			interval = num(f, fx + 66, y, 66, "interval", "interval", 5, "s");
			interval.tip("How often it checks the leader");
			y = f.row("Signal lost", ROW);
			f.add(new HcButton(fx, y, half, ROW, "Hold positions", HcButton.Style.TAB, b -> {
				onLost = "HOLD";
				relayout();
			}).selected(onLost.equals("HOLD")).color(ACCENT).tip("If the leader can't be read, keep what you have"));
			f.add(new HcButton(fx + half + 4, y, half, ROW, "Flatten", HcButton.Style.TAB, b -> {
				onLost = "FLATTEN";
				relayout();
			}).selected(onLost.equals("FLATTEN")).color(ACCENT).tip("If the leader can't be read, close everything"));
			y = f.row("Redstone", ROW);
			String[] rs = {"IGNORE", "PAUSE", "RUN"};
			String[] rl = {"Ignore", "Pause if on", "Run if on"};
			String[] rt = {"Redstone does nothing", "Pauses while powered", "Only copies while powered"};
			int w3 = (fw - 8) / 3;
			for (int i = 0; i < 3; i++) {
				String r = rs[i];
				f.add(new HcButton(fx + i * (w3 + 4), y, w3, ROW, rl[i], HcButton.Style.TAB, b -> {
					redstone = r;
					relayout();
				}).selected(redstone.equals(r)).color(ACCENT).tip(rt[i]));
			}
		}
		f.rule();
		f.heading("Now");
		nowY = f.reserve(30);
	}

	private HcField num(Flow f, int x, int y, int w, String key, String name, double step, String suffix) {
		HcField b = f.add(field(x, y, w, name, Fmt.size(num(key))));
		b.setMaxLength(16);
		b.numeric(step).suffix(suffix);
		return b;
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

	private void eject(String which) {
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos);
		o.addProperty("which", which);
		ClientNet.send("desk_eject", o);
	}

	private double val(HcField box, String key) {
		return box == null ? num(key) : box.num(0);
	}

	private JsonObject config(boolean run) {
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos);
		o.addProperty("address", legacyAddress());
		o.addProperty("running", run);
		o.addProperty("sizing", sizing);
		o.addProperty("ratio", val(ratio, "ratio"));
		o.addProperty("maxLeverage", val(maxLev, "maxLeverage"));
		o.addProperty("bandPct", val(bandPct, "bandPct"));
		o.addProperty("bandMin", val(bandMin, "bandMin"));
		o.addProperty("maxStep", val(maxStep, "maxStep"));
		o.addProperty("interval", val(interval, "interval"));
		o.addProperty("drawdownPct", val(drawdown, "drawdownPct"));
		o.addProperty("onLost", onLost);
		o.addProperty("redstone", redstone);
		return o;
	}

	@Override
	protected String snapshot() {
		if (ratio == null) return null;
		JsonObject c = config(false);
		c.remove("running");
		return c.toString();
	}

	@Override
	protected void save() {
		ClientNet.send("configure", config(running()));
		markSaved();
		chime();
		onClose();
	}

	@Override
	protected void footer(Footer f) {
		boolean run = running();
		HcButton start = f.left(run ? "■ Stop copying" : "▶ Start copying", run ? HcButton.Style.DANGER : HcButton.Style.NORMAL, () -> {
			if (!run && problem() != null) {
				toast("⚠ " + problem(), Ui.YELLOW);
				return;
			}
			ClientNet.send("configure", config(!run));
			markSaved();
			info.addProperty("running", !run);
			relayout();
			toast(!run ? "▶ Copying started" : "■ Stopped", !run ? Ui.GREEN : Ui.DIM);
		});
		start.color(Ui.GREEN).tip(run ? "Stops placing copy orders; positions stay as they are" : "Saves and starts matching the leader");
		f.left("Print card", HcButton.Style.NORMAL, () -> {
			JsonObject o = new JsonObject();
			o.addProperty("pos", pos);
			ClientNet.send("print_signal", o);
		}).tip("Prints a Signal Card of this desk's tracking error (0 to 1 of the follower's equity), e.g. for a sensor that warns when copying drifts. Needs a Blank Card unless you can build.");
		f.right("Apply", HcButton.Style.NORMAL, () -> {
			ClientNet.send("configure", config(running()));
			markSaved();
			toast("✔ Applied", Ui.GREEN);
		});
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
			ClientNet.send("desk_info", o);
		}
		if (!shape().equals(builtFor)) relayout();
		if (ClientState.lastVaultAt > seenResult) {
			seenResult = ClientState.lastVaultAt;
			toast(ClientState.lastVaultMsg, ClientState.lastVaultOk ? Ui.GREEN : Ui.RED);
		}
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		int y = nowY;
		Ui.text(g, font, Ui.ellipsize(font, "Leader holds: " + str("leaderValue", "--"), pw - 2 * PAD), PAD, y, Ui.TEXT);
		Ui.text(g, font, Ui.ellipsize(font, "Desk: " + (str("status", "").isEmpty() ? "idle" : str("status", "")), pw - 2 * PAD), PAD, y + 11, Ui.DIM);
		double te = num("tracking");
		String gap = String.format("%.1f%%", te * 100);
		if (problem() != null) Ui.text(g, font, "Nothing to match until it has a leader and a follower", PAD, y + 22, Ui.DIM);
		else Ui.text(g, font, running() ? "Off target by " + gap + " of equity" : "Start would first trade about " + gap + " of your equity to match",
				PAD, y + 22, te < 0.05 ? Ui.GREEN : Ui.YELLOW);
	}
}
