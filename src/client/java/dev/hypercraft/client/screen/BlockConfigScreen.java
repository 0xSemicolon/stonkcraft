package dev.hypercraft.client.screen;

import com.google.gson.JsonObject;
import dev.hypercraft.client.ClientNet;
import dev.hypercraft.client.ClientState;
import dev.hypercraft.client.ui.HcButton;
import dev.hypercraft.client.ui.HcField;
import dev.hypercraft.client.ui.HcScreen;
import dev.hypercraft.client.ui.Ui;
import dev.hypercraft.trading.Fmt;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Shared frame for block config screens: header with the linked account, a status line saying what the block needs
 * or is doing, a coin field with suggestions, the block's own fields, and Save/Cancel. Saving sends the whole config.
 */
public abstract class BlockConfigScreen extends HcScreen {
	protected final long pos;
	protected final JsonObject data;
	protected final String owner;
	protected HcField coinBox;
	private int coinY;
	private final List<int[]> suggestionHits = new ArrayList<>();
	private final List<String> suggestionNames = new ArrayList<>();
	private String result;
	private boolean resultOk;

	protected BlockConfigScreen(String title, JsonObject data) {
		super(title);
		this.data = data;
		this.pos = data.get("pos").getAsLong();
		this.owner = data.has("owner") ? data.get("owner").getAsString() : "";
	}

	/** Whether the block trades or reads a market; false hides the coin row. */
	protected boolean usesCoin() {
		return true;
	}

	/** What a blank coin means for this block, shown inside the empty field. */
	protected String coinHint() {
		return "e.g. BTC";
	}

	/** The block's own rows, below the coin. */
	protected abstract void fields(Flow f);

	protected abstract void writeFields(JsonObject out);

	/** Extra footer buttons on the left, e.g. a test button. */
	protected void footerExtras(Footer f) {}

	/** One line on what the block does with its current settings; shown when nothing needs fixing. */
	protected String summary() {
		return null;
	}

	protected boolean usesCard() {
		return data.has("account");
	}

	protected String account() {
		return data.has("account") ? data.get("account").getAsString() : "";
	}

	@Override
	protected String headerRight() {
		// a Watch Card says so: the block may only look at the account
		if (usesCard()) return account().isEmpty() ? "no card" : "◈ " + account() + (data.has("watchCard") && data.get("watchCard").getAsBoolean() ? " (watch)" : "")
				+ (data.has("scope") ? " (scoped)" : "");
		String me = minecraft != null && minecraft.player != null ? minecraft.player.getGameProfile().getName() : "";
		return owner.isEmpty() || owner.equals(me) ? null : "owner " + owner;
	}

	@Override
	protected int headerRightColor() {
		if (!usesCard()) return Ui.DIM;
		return account().isEmpty() ? Ui.YELLOW : Ui.ACCENT;
	}

	@Override
	protected String status() {
		if (result != null) return (resultOk ? "✔ " : "✘ ") + result;
		if (usesCard() && account().isEmpty()) return data.has("needsTrade") && data.get("needsTrade").getAsBoolean() ? "⚠ Needs a Trade Card: right-click one in" : "⚠ Needs a Watch or Trade Card: right-click one in";
		if (usesCoin() && coinBox != null && !coinBox.getValue().isBlank() && ClientState.ticker(coinBox.getValue()) == null && ClientState.TICKERS.isEmpty())
			return "Loading markets…";
		String s = summary();
		if (data.has("scope") && (s == null || s.isEmpty())) return "Card limits: " + data.get("scope").getAsString();
		return s == null ? "" : s;
	}

	@Override
	protected int statusColor() {
		if (result != null) return resultOk ? Ui.GREEN : Ui.RED;
		if (usesCard() && account().isEmpty()) return Ui.YELLOW;
		return Ui.TEXT;
	}

	/** The outcome of this block's last action, from the server; replaces the status line. */
	public void onResult(long pos, boolean ok, String msg) {
		if (pos != this.pos) return;
		result = msg;
		resultOk = ok;
		toast((ok ? "✔ " : "✘ ") + msg, ok ? Ui.GREEN : Ui.RED);
	}

	@Override
	protected void layout(Flow f) {
		coinBox = null;
		if (usesCoin()) {
			coinY = f.row("Coin", 14);
			coinBox = f.add(field(f.left + 58, coinY, 80, "Coin", data.has("coin") ? data.get("coin").getAsString() : ""));
			coinBox.setMaxLength(16);
			coinBox.hint(coinHint());
			coinBox.validate(s -> s.isBlank() || ClientState.TICKERS.isEmpty() || ClientState.ticker(s) != null ? null : "No market called " + s.trim());
		}
		fields(f);
	}

	@Override
	protected void footer(Footer f) {
		footerExtras(f);
		if (data.has("tap")) f.left("Print card", HcButton.Style.NORMAL, this::printCard).tip(printTip());
		f.right("Cancel", HcButton.Style.NORMAL, this::onClose);
		f.primary("Save", this::save);
	}

	protected HcField numField(Flow f, int x, int y, int w, String name, double value, double step) {
		HcField box = field(x, y, w, name, Fmt.size(value));
		box.setMaxLength(16);
		box.numeric(step);
		return f.add(box);
	}

	protected static double parse(EditBox box, double def) {
		// status() can run before layout() has made the fields
		if (box == null) return def;
		try {
			return Double.parseDouble(box.getValue());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	protected String coin() {
		if (coinBox == null) return data.has("coin") ? data.get("coin").getAsString() : "";
		ClientState.Ticker t = ClientState.ticker(coinBox.getValue());
		return t == null ? coinBox.getValue().trim() : t.name;
	}

	private JsonObject config() {
		JsonObject out = new JsonObject();
		out.addProperty("pos", pos);
		out.addProperty("coin", coin());
		writeFields(out);
		return out;
	}

	@Override
	protected String snapshot() {
		return coinBox == null && usesCoin() ? null : config().toString();
	}

	@Override
	protected void save() {
		ClientNet.send("configure", config());
		chime();
		if (minecraft != null && minecraft.player != null) minecraft.gui.setOverlayMessage(Component.literal("✔ " + title.getString() + " saved").withColor(Ui.GREEN), false);
		markSaved();
		onClose();
	}

	/** What a Signal Card printed from this block reads, for the Print card button's tooltip. */
	protected String printTip() {
		return "Saves, then prints a Signal Card of this block's reading. Anyone holding the card can read it"
				+ (usesCard() && !account().isEmpty() ? ", including " + account() + "'s figures" : "") + ". Needs a Blank Card unless you can build.";
	}

	/** Saves without closing, then prints a Signal Card of the block's reading into the inventory. */
	protected void printCard() {
		ClientNet.send("configure", config());
		markSaved();
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos);
		ClientNet.send("print_signal", o);
	}

	/** Saves without closing, then fires the block once, as a rising redstone edge would. */
	protected void fireNow() {
		ClientNet.send("configure", config());
		markSaved();
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos);
		ClientNet.send("fire_now", o);
		toast("Firing…", Ui.DIM);
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		if (!usesCoin() || coinBox == null) return;
		ClientState.Ticker t = ClientState.ticker(coinBox.getValue());
		int y = coinY + 3;
		if (t != null) {
			double ch = t.dayChangePct();
			Ui.text(g, font, "$" + Fmt.price(t.mid), PAD + 148, y, Ui.TEXT);
			Ui.textRight(g, font, Fmt.pct(ch), pw - PAD, y, Ui.signColor(ch));
		} else if (coinBox.getValue().isBlank()) {
			Ui.text(g, font, Ui.ellipsize(font, blankCoinMeaning(), pw - PAD * 2 - 140), PAD + 148, y, Ui.DIM);
		}
	}

	/** What leaving the coin empty does, shown beside the field. */
	protected String blankCoinMeaning() {
		return "";
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		super.render(g, mx, my, pt);
		// coin suggestions float above other fields
		suggestionHits.clear();
		suggestionNames.clear();
		if (coinBox != null && coinBox.isFocused()) {
			List<ClientState.Ticker> matches = ClientState.search(coinBox.getValue(), 6);
			if (!(matches.size() == 1 && matches.get(0).name.equalsIgnoreCase(coinBox.getValue()))) {
				g.pose().pushPose();
				g.pose().translate(0, 0, 200);
				int sx = coinBox.getX(), sy = coinBox.getY() + coinBox.getHeight() + 1;
				for (ClientState.Ticker m : matches) {
					boolean hov = Ui.inside(mx, my, sx, sy, 120, 11);
					g.fill(sx, sy, sx + 120, sy + 11, hov ? Ui.PANEL_HI : 0xF5081214);
					Ui.text(g, font, ClientState.display(m.name), sx + 3, sy + 2, Ui.ACCENT);
					Ui.textRight(g, font, "$" + Fmt.price(m.mid), sx + 117, sy + 2, Ui.DIM);
					suggestionHits.add(new int[]{sx, sy, 120, 11});
					suggestionNames.add(m.name);
					sy += 11;
				}
				g.pose().popPose();
			}
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		for (int i = 0; i < suggestionHits.size(); i++) {
			int[] r = suggestionHits.get(i);
			if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
				coinBox.setValue(ClientState.display(suggestionNames.get(i)));
				setFocused(null);
				coinBox.setFocused(false);
				return true;
			}
		}
		return super.mouseClicked(mx, my, button);
	}
}
