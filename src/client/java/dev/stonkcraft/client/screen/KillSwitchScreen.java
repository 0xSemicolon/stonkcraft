package dev.stonkcraft.client.screen;

import com.google.gson.JsonObject;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcField;
import dev.stonkcraft.client.ui.Ui;

public class KillSwitchScreen extends BlockConfigScreen {
	private static final String[] MODES = {"CANCEL", "PANIC", "DEADMAN"};
	private static final String[] LABELS = {"Cancel all", "Close all", "Dead man"};
	private static final String[] TIPS = {
			"A rising edge cancels every resting order.",
			"A rising edge cancels every resting order and closes positions at market.",
			"Cancels every resting order once the redstone power has been off for the whole window."};
	private int mode;
	/** 0 redstone, 1 redstone and Signal Card events, 2 events only. */
	private int fireOn;
	private HcField windowBox;

	public KillSwitchScreen(JsonObject data) {
		super("Kill Switch", data);
		String m = data.has("mode") ? data.get("mode").getAsString() : "CANCEL";
		mode = Math.max(0, java.util.Arrays.asList(MODES).indexOf(m));
		fireOn = !data.has("onEvent") || !data.get("onEvent").getAsBoolean() ? 0 : data.get("eventOnly").getAsBoolean() ? 2 : 1;
	}

	@Override
	protected int accent() {
		return Ui.RED;
	}

	@Override
	protected String coinHint() {
		return "blank = all";
	}

	@Override
	protected String blankCoinMeaning() {
		return "every market";
	}

	@Override
	protected void fields(Flow f) {
		int y = f.row("Mode", ROW);
		int x = f.left + 58, w = (f.width - 58 - 8) / 3;
		for (int i = 0; i < MODES.length; i++) {
			int idx = i;
			f.add(new HcButton(x + i * (w + 4), y, w, ROW, LABELS[i], HcButton.Style.TAB, b -> {
				mode = idx;
				relayout();
			}).color(Ui.RED).selected(mode == i).tip(TIPS[i]));
		}
		windowBox = null;
		if (mode == 2) {
			y = f.row("Window", 14);
			windowBox = numField(f, f.left + 58, y, 60, "Window", data.has("window") ? data.get("window").getAsDouble() : 60, 10);
			windowBox.suffix("s").validate(s -> windowBox.num(0) < 10 ? "At least 10 seconds" : null);
			f.text("While powered it keeps pushing back a cancel-all deadline. When the power stops, or the server goes down, "
					+ "every open order is cancelled once the window runs out.", Ui.DIM);
		} else {
			y = f.row("Fires on", ROW);
			String[] fl = {"Redstone", "+ Events", "Events only"};
			String[] ft = {"A rising redstone edge fires it", "Also fires once per event from its Signal Card (right-click one in): a leader's fill, an executor's order, a bell",
					"Only the Signal Card's events fire it; redstone is ignored"};
			int fw = (f.width - 58 - 8) / 3;
			for (int i = 0; i < fl.length; i++) {
				int idx = i;
				f.add(new HcButton(f.left + 58 + i * (fw + 4), y, fw, ROW, fl[i], HcButton.Style.TAB, b -> {
					fireOn = idx;
					relayout();
				}).color(Ui.YELLOW).selected(fireOn == i).tip(ft[i]));
			}
			if (fireOn > 0) {
				String sig = data.has("signal") ? data.get("signal").getAsString() : "";
				f.text(sig.isEmpty() ? "⚠ Right-click the switch with a Signal Card whose events should fire it." : "Events from: " + sig, sig.isEmpty() ? Ui.YELLOW : Ui.DIM);
			}
			f.text(mode == 1
					? "Also closes positions at market. Wire it to a PnL or Dumping sensor for a circuit breaker."
					: "Pair it with a sensor to pull your resting orders when the market turns.", Ui.DIM);
		}
	}

	@Override
	protected void footerExtras(Footer f) {
		if (mode == 2) return;
		HcButton[] fire = new HcButton[1];
		fire[0] = f.left("Fire now", HcButton.Style.DANGER, () -> {
			// two clicks: it cancels real orders (and closes positions), so the first click only arms it
			if (System.currentTimeMillis() - armedAt > 4000) {
				armedAt = System.currentTimeMillis();
				fire[0].label("Sure?");
				toast(mode == 1 ? "Click again to cancel every order and close every position" : "Click again to cancel every resting order", Ui.RED);
				return;
			}
			armedAt = 0;
			fire[0].label("Fire now");
			fireNow();
		});
		fire[0].tip("Saves, then fires once, as a redstone pulse would. Click twice");
	}

	private long armedAt;

	@Override
	public void tick() {
		super.tick();
		if (armedAt > 0 && System.currentTimeMillis() - armedAt > 4000) {
			armedAt = 0;
			relayout();
		}
	}

	@Override
	protected String summary() {
		String where = coin().isEmpty() ? "every market" : ClientState.display(coin());
		if (!data.has("atRiskOrders")) {
			return switch (mode) {
				case 1 -> "On a pulse: cancel orders and close positions, " + where;
				case 2 -> "Cancels " + where + " orders " + (windowBox == null ? 60 : (int) windowBox.num(60)) + "s after power stops";
				default -> "On a pulse: cancel resting orders, " + where;
			};
		}
		// a dry run: what a pulse would hit on the card's account right now
		int orders = atRisk();
		String o = orders + (orders == 1 ? " order" : " orders");
		return switch (mode) {
			case 1 -> "A pulse now would cancel " + o + " and close " + positionsAtRisk() + (positionsAtRisk() == 1 ? " position" : " positions") + " (" + where + ")";
			case 2 -> "Would cancel " + o + " (" + where + ") " + (windowBox == null ? 60 : (int) windowBox.num(60)) + "s after power stops";
			default -> "A pulse now would cancel " + o + " (" + where + ")";
		};
	}

	/** Resting orders the switch covers, from the counts the server sent when the screen opened. */
	private int atRisk() {
		JsonObject m = data.getAsJsonObject("atRiskOrders");
		return m.entrySet().stream().filter(e -> covers(e.getKey())).mapToInt(e -> e.getValue().getAsInt()).sum();
	}

	private int positionsAtRisk() {
		int n = 0;
		for (var el : data.getAsJsonArray("atRiskPositions")) if (covers(el.getAsString())) n++;
		return n;
	}

	/** Whether the market field (blank = all; "AAPL" or "xyz:AAPL") covers this market. */
	private boolean covers(String market) {
		return coin().isBlank() || ClientState.display(market).equalsIgnoreCase(ClientState.display(coin().trim()));
	}

	@Override
	protected void writeFields(JsonObject out) {
		out.addProperty("mode", MODES[mode]);
		out.addProperty("onEvent", fireOn > 0);
		out.addProperty("eventOnly", fireOn == 2);
		out.addProperty("window", windowBox == null ? (data.has("window") ? data.get("window").getAsDouble() : 60) : parse(windowBox, 60));
	}
}
