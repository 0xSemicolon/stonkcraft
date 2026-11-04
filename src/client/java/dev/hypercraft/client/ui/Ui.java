package dev.hypercraft.client.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Palette and small drawing primitives for the terminal look. */
public final class Ui {
	private Ui() {}

	public static final int BG = 0xE8071012;
	public static final int PANEL = 0xF00C1A1C;
	public static final int PANEL_HI = 0xFF12272A;
	public static final int BORDER = 0xFF1D3B3D;
	public static final int GRID = 0x301D8F86;
	public static final int ACCENT = 0xFF5EF0C3;
	public static final int ACCENT_DIM = 0xFF2B8C77;
	public static final int TEXT = 0xFFE4F2EF;
	public static final int DIM = 0xFF7F9E9A;
	public static final int GREEN = 0xFF3FE0A6;
	public static final int RED = 0xFFFF5C6C;
	public static final int YELLOW = 0xFFF2C94C;

	/** Colour for an account tag: yellow for watch-only, accent for paper. */
	public static int tagColor(String tag) {
		if (tag.startsWith("WATCH")) return YELLOW;
		return ACCENT;
	}

	/** An account's tag from the server's account list: PAPER or WATCH. */
	public static String tag(JsonObject a) {
		return a.has("tag") ? a.get("tag").getAsString() : a.get("label").getAsString();
	}

	/** Extra marker next to an account name; nothing now that every account is paper or a mainnet watch. */
	public static String netMark(JsonObject a) {
		return "";
	}

	public static int signColor(double v) {
		return v > 0 ? GREEN : v < 0 ? RED : DIM;
	}

	public static void panel(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL);
		outline(g, x, y, w, h, BORDER);
		if (UiLint.recording) UiLint.panel(g.pose().last().pose(), x, y, w, h);
	}

	public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y, x + 1, y + h, color);
		g.fill(x + w - 1, y, x + w, y + h, color);
	}

	/** 1px line via Bresenham; fine for the few hundred segments a chart needs. */
	public static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
		int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
		int err = dx + dy;
		while (true) {
			g.fill(x0, y0, x0 + 1, y0 + 1, color);
			if (x0 == x1 && y0 == y1) break;
			int e2 = 2 * err;
			if (e2 >= dy) {
				err += dy;
				x0 += sx;
			}
			if (e2 <= dx) {
				err += dx;
				y0 += sy;
			}
		}
	}

	/** Area sparkline: a line plus a soft fill below it. */
	public static void sparkline(GuiGraphics g, double[] data, int x, int y, int w, int h, int color) {
		if (data.length < 2) return;
		double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (double d : data) {
			min = Math.min(min, d);
			max = Math.max(max, d);
		}
		double range = max - min;
		if (range <= 0) range = Math.abs(max) * 1e-4 + 1e-9;
		int fill = (color & 0x00FFFFFF) | 0x28000000;
		int px = -1, py = -1;
		for (int i = 0; i < data.length; i++) {
			int cx = x + (int) Math.round((double) i / (data.length - 1) * (w - 1));
			int cy = y + h - 1 - (int) Math.round((data[i] - min) / range * (h - 1));
			if (px >= 0) {
				for (int fx = px; fx <= cx; fx++) {
					double t = cx == px ? 0 : (double) (fx - px) / (cx - px);
					int fy = (int) Math.round(py + (cy - py) * t);
					g.fill(fx, fy, fx + 1, y + h, fill);
				}
				line(g, px, py, cx, cy, color);
			}
			px = cx;
			py = cy;
		}
	}

	public static void textRight(GuiGraphics g, Font font, String s, int right, int y, int color) {
		g.drawString(font, s, right - font.width(s), y, color, false);
	}

	public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
		g.drawString(font, s, x, y, color, false);
	}

	public static void textCentered(GuiGraphics g, Font font, String s, int cx, int y, int color) {
		g.drawString(font, s, cx - font.width(s) / 2, y, color, false);
	}

	/** Draws text scaled around its top-left corner. */
	public static void textScaled(GuiGraphics g, Font font, String s, int x, int y, float scale, int color) {
		g.pose().pushPose();
		g.pose().translate(x, y, 0);
		g.pose().scale(scale, scale, 1);
		g.drawString(font, s, 0, 0, color, false);
		g.pose().popPose();
	}

	/** A flat clickable chip; returns true if the mouse is over it (caller handles the click). */
	public static boolean chip(GuiGraphics g, Font font, String label, int x, int y, int w, int h, int color, boolean active, int mx, int my) {
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
		int base = active ? (color & 0x00FFFFFF) | 0xFF000000 : (color & 0x00FFFFFF) | (hover ? 0x60000000 : 0x30000000);
		g.fill(x, y, x + w, y + h, base);
		outline(g, x, y, w, h, (color & 0x00FFFFFF) | (hover || active ? 0xFF000000 : 0x90000000));
		int tc = active ? 0xFF061012 : TEXT;
		g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, tc, false);
		return hover;
	}

	public static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public static String ellipsize(Font font, String s, int maxW) {
		if (font.width(s) <= maxW) return s;
		while (s.length() > 1 && font.width(s + "…") > maxW) s = s.substring(0, s.length() - 1);
		return s + "…";
	}
}
