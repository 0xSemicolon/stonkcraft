package dev.stonkcraft.client.screen;

import com.google.gson.JsonObject;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcDropdown;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.trading.AccountMetric;
import net.minecraft.client.gui.GuiGraphics;

public class AccountDisplayScreen extends BlockConfigScreen {
	private AccountMetric metric;
	private boolean spark;
	private int previewY = -1;

	public AccountDisplayScreen(JsonObject data) {
		super("Account Display", data);
		metric = AccountMetric.parse(data.get("metric").getAsString());
		spark = !data.has("spark") || data.get("spark").getAsBoolean();
	}

	@Override
	protected int accent() {
		return Ui.YELLOW;
	}

	/** Only per-coin figures and order counts read the coin. */
	@Override
	protected boolean usesCoin() {
		return metric.perCoin || metric == AccountMetric.ORDERS;
	}

	@Override
	protected String blankCoinMeaning() {
		return metric == AccountMetric.ORDERS ? "every market" : "pick a market";
	}

	@Override
	protected String coinHint() {
		return metric == AccountMetric.ORDERS ? "all markets" : "e.g. GOLD";
	}

	@Override
	protected void fields(Flow f) {
		int y = f.row("Shows", ROW);
		f.add(new HcDropdown<>(f.left + 58, y, 150, ROW, Choices.metrics(), metric, m -> {
			metric = m;
			relayout();
		}));
		if (!metric.perCoin) {
			y = f.row("Chart", ROW);
			f.add(new HcButton(f.left + 58, y, 150, ROW, (spark ? "■" : "□") + " Equity sparkline", b -> {
				spark = !spark;
				b.label((spark ? "■" : "□") + " Equity sparkline");
			}).tip("Draws the account's recent equity under the figure"));
		}
		previewY = account().isEmpty() ? -1 : f.reserve(40);
		f.text(account().isEmpty()
				? "Insert a Watch or Trade Card (right-click the block with it) to pick the account. Place a row of them for a scoreboard."
				: "The figure updates live on the block's face. Right-click with another card to swap accounts; a row of displays makes a scoreboard.", Ui.DIM);
	}

	@Override
	protected String summary() {
		return "Shows " + metric.label + (account().isEmpty() ? "" : " of " + account());
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		super.drawContent(g, mx, my);
		if (previewY < 0) return;
		// a small copy of the block's face, as it reads now
		int x = PAD, y = previewY, w = 140, h = 38;
		g.fill(x, y, x + w, y + h, 0xFF0A0E10);
		Ui.outline(g, x, y, w, h, 0xFFF2C94C);
		Ui.text(g, font, Ui.ellipsize(font, account() + " · " + metric.label.toUpperCase(), w - 8), x + 4, y + 4, 0xFFF2C94C);
		String value = data.has("faceValue") ? data.get("faceValue").getAsString() : "";
		boolean same = data.has("faceMetric") && data.get("faceMetric").getAsString().equals(metric.name());
		if (!same || value.isEmpty()) {
			Ui.text(g, font, "Save to show " + metric.label, x + 4, y + 20, Ui.DIM);
			return;
		}
		int tone = data.get("faceTone").getAsInt();
		Ui.textScaled(g, font, Ui.ellipsize(font, value, (int) ((w - 8) / 1.3f)), x + 4, y + 16, 1.3f, tone > 0 ? Ui.GREEN : tone < 0 ? Ui.RED : Ui.TEXT);
		String sub = data.get("faceSub").getAsString();
		if (!sub.isEmpty()) Ui.text(g, font, Ui.ellipsize(font, sub, w - 8), x + 4, y + 28, Ui.DIM);
	}

	@Override
	protected void writeFields(JsonObject out) {
		out.addProperty("metric", metric.name());
		out.addProperty("spark", spark);
	}
}
