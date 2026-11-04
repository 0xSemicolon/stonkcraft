package dev.hypercraft.client.screen;

import com.google.gson.JsonObject;
import dev.hypercraft.client.ClientState;
import dev.hypercraft.client.render.Logos;
import dev.hypercraft.client.ui.Ui;
import dev.hypercraft.trading.Fmt;
import net.minecraft.client.gui.GuiGraphics;

public class TickerScreen extends BlockConfigScreen {
	private int previewY;

	public TickerScreen(JsonObject data) {
		super("Ticker Display", data);
	}

	@Override
	protected int accent() {
		return Ui.ACCENT;
	}

	@Override
	protected String blankCoinMeaning() {
		return "pick a market";
	}

	@Override
	protected void fields(Flow f) {
		previewY = f.reserve(40);
		f.text("No card needed. Place a row of them for a trading floor.", Ui.DIM);
	}

	@Override
	protected String summary() {
		return coin().isEmpty() ? "Shows nothing until you pick a market" : "Shows " + ClientState.display(coin()) + " live on its face";
	}

	@Override
	protected void writeFields(JsonObject out) {}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		super.drawContent(g, mx, my);
		// a small copy of the board's face
		ClientState.Ticker t = ClientState.ticker(coinBox.getValue());
		int x = PAD, y = previewY, w = 120, h = 38;
		g.fill(x, y, x + w, y + h, 0xFF0A0E10);
		Ui.outline(g, x, y, w, h, 0xFFC9A227);
		if (t == null) {
			Ui.textCentered(g, font, "—", x + w / 2, y + 15, Ui.DIM);
			return;
		}
		Logos.draw(g, font, t.name, x + 4, y + 4, 10);
		Ui.text(g, font, ClientState.display(t.name), x + 18, y + 5, Ui.TEXT);
		double ch = t.dayChangePct();
		Ui.textRight(g, font, Fmt.pct(ch), x + w - 4, y + 5, Ui.signColor(ch));
		Ui.textScaled(g, font, "$" + Fmt.price(t.mid), x + 4, y + 20, 1.3f, Ui.signColor(ch));
	}
}
