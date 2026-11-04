package dev.hypercraft.client.render;

import com.google.gson.JsonObject;
import dev.hypercraft.client.ClientConfig;
import dev.hypercraft.client.ClientState;
import dev.hypercraft.client.screen.TerminalScreen;
import dev.hypercraft.client.ui.Ui;
import dev.hypercraft.trading.Fmt;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** A scrolling stock-ticker strip across the top of the screen: your equity, then your watchlist. */
public final class TickerTapeHud {
	private TickerTapeHud() {}

	/** A run of tape text; {@code logo} is a market whose logo is drawn first, or null. */
	private record Seg(String text, int color, String logo) {
		Seg(String text, int color) {
			this(text, color, null);
		}

		int width(Font font) {
			return font.width(text) + (logo == null ? 0 : 10);
		}
	}

	private static final int SPEED = 28; // px per second

	public static void render(GuiGraphics g, DeltaTracker delta) {
		renderSpeedrun(g);
		renderTape(g);
	}

	/** Wall Street speedrun timer and net worth, top right. */
	private static void renderSpeedrun(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		JsonObject run = ClientState.speedrun;
		if (run == null || mc.options.hideGui) return;
		long ms = System.currentTimeMillis() - run.get("start").getAsLong();
		long s = ms / 1000;
		String t = s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d.%d", s / 60, s % 60, ms / 100 % 10);
		String nw = Fmt.usd(run.get("netWorth").getAsDouble());
		Font font = mc.font;
		int w = g.guiWidth();
		int bw = Math.max(font.width(t) * 2, font.width(nw)) + 12;
		int y = ClientConfig.get().tape ? 16 : 4;
		g.fill(w - bw - 4, y, w - 4, y + 32, 0xB0050B0C);
		g.fill(w - bw - 4, y, w - 4, y + 1, 0xFFF2C94C);
		g.pose().pushPose();
		g.pose().translate(w - 10 - font.width(t) * 2, y + 4, 0);
		g.pose().scale(2, 2, 1);
		g.drawString(font, t, 0, 0, 0xFFF2C94C, false);
		g.pose().popPose();
		g.drawString(font, nw, w - 10 - font.width(nw), y + 22, Ui.TEXT, false);
	}

	private static void renderTape(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (!ClientConfig.get().tape || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
		if (mc.screen instanceof TerminalScreen || ClientState.TICKERS.isEmpty()) return;
		Font font = mc.font;
		int w = g.guiWidth();

		List<Seg> segs = new ArrayList<>();
		JsonObject pf = ClientState.portfolio;
		if (pf != null && ClientState.hasAccount()) {
			double eq = pf.get("equity").getAsDouble();
			double upnl = pf.get("upnl").getAsDouble();
			segs.add(new Seg("◆ " + pf.get("accountName").getAsString() + " ", Ui.ACCENT));
			segs.add(new Seg(Fmt.usd(eq) + " ", Ui.TEXT));
			segs.add(new Seg("uPnL ", Ui.DIM));
			segs.add(new Seg(Fmt.usdSigned(upnl) + "     ", Ui.signColor(upnl)));
		}
		List<String> coins = new ArrayList<>(ClientConfig.get().watchlist);
		if (coins.isEmpty()) ClientState.TICKERS.keySet().stream().limit(10).forEach(coins::add);
		long now = System.currentTimeMillis();
		// splits your account from the markets, and says what the % moves cover
		segs.add(new Seg("│ 24h  ", Ui.DIM));
		for (String c : coins) {
			ClientState.Ticker t = ClientState.ticker(c);
			if (t == null) continue;
			double ch = t.dayChangePct();
			segs.add(new Seg(ClientState.display(t.name) + " ", Ui.DIM, t.name));
			int flash = now - t.lastMoveAt < 700 ? (t.lastMove > 0 ? Ui.GREEN : Ui.RED) : Ui.TEXT;
			segs.add(new Seg(Fmt.price(t.mid) + " ", flash));
			segs.add(new Seg((ch >= 0 ? "▲" : "▼") + String.format("%.2f%%", Math.abs(ch)) + "     ", Ui.signColor(ch)));
		}
		if (segs.isEmpty()) return;

		int total = 0;
		for (Seg s : segs) total += s.width(font);
		if (total <= 0) return;

		g.fill(0, 0, w, 11, 0xB0050B0C);
		g.fill(0, 11, w, 12, 0x6040E0B0);
		int offset = (int) ((now * SPEED / 1000) % total);
		int x = -offset;
		while (x < w) {
			for (Seg s : segs) {
				int sw = s.width(font);
				if (x + sw > 0 && x < w) {
					int tx = x;
					if (s.logo != null) {
						dev.hypercraft.client.render.Logos.draw(g, font, s.logo, x, 1, 9);
						tx += 10;
					}
					g.drawString(font, s.text, tx, 2, s.color, false);
				}
				x += sw;
			}
		}
	}
}
