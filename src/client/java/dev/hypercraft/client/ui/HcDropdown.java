package dev.hypercraft.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Picks one of many options from a grid that opens over the screen, grouped under headings, with the hovered
 * option's help line at the bottom. Replaces click-to-cycle buttons. Shift-click and the scroll wheel still step
 * through the options without opening the grid. Its screen must route clicks to it first while it's open
 * ({@link HcScreen} does).
 */
public class HcDropdown<T> extends AbstractButton {
	public record Option<T>(T value, String label, String group, String help) {}

	private final List<Option<T>> options;
	private final Consumer<T> onChange;
	private int index;
	private boolean open;
	private final List<int[]> hits = new ArrayList<>();
	private int popX, popY, popW, popH;

	public HcDropdown(int x, int y, int w, int h, List<Option<T>> options, T value, Consumer<T> onChange) {
		super(x, y, w, h, Component.empty());
		this.options = options;
		this.onChange = onChange;
		select(value);
	}

	public void select(T value) {
		for (int i = 0; i < options.size(); i++) if (options.get(i).value.equals(value)) index = i;
		Option<T> o = options.get(index);
		setMessage(Component.literal(o.label + " ▾"));
		setTooltip(o.help.isEmpty() ? null : Tooltip.create(Component.literal(o.help)));
	}

	public T value() {
		return options.get(index).value;
	}

	public boolean isOpen() {
		return open;
	}

	public void close() {
		open = false;
	}

	private void set(int i) {
		index = (i + options.size()) % options.size();
		select(options.get(index).value);
		onChange.accept(value());
	}

	@Override
	public void onPress() {
		if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) set(index - 1);
		else open = !open;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (!open && isMouseOver(mx, my) && dy != 0) {
			set(index + (dy < 0 ? 1 : -1));
			return true;
		}
		return false;
	}

	/** While open, every click lands here: on an option it picks it, anywhere else it closes the grid. */
	public boolean clickPopup(double mx, double my) {
		for (int[] r : hits) {
			if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
				open = false;
				set(r[4]);
				playDownSound(Minecraft.getInstance().getSoundManager());
				return true;
			}
		}
		open = false;
		return true;
	}

	@Override
	public boolean keyPressed(int key, int scan, int mods) {
		if (open && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
			open = false;
			return true;
		}
		if (isFocused() && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN || key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT)) {
			set(index + 1);
			return true;
		}
		if (isFocused() && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP || key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT)) {
			set(index - 1);
			return true;
		}
		return super.keyPressed(key, scan, mods);
	}

	@Override
	protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
		Font font = Minecraft.getInstance().font;
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		boolean hot = isHoveredOrFocused() || open;
		g.fill(x, y, x + w, y + h, (Ui.ACCENT & 0x00FFFFFF) | (hot ? 0x58000000 : 0x26000000));
		Ui.outline(g, x, y, w, h, (Ui.ACCENT & 0x00FFFFFF) | (hot ? 0xFF000000 : 0x90000000));
		String label = Ui.ellipsize(font, getMessage().getString(), w - 6);
		g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, Ui.TEXT, false);
	}

	/** Draws the open grid above everything else; call after the screen's widgets. */
	public void renderPopup(GuiGraphics g, int mx, int my, int screenW, int screenH) {
		hits.clear();
		if (!open) return;
		Font font = Minecraft.getInstance().font;
		int cellW = 0;
		for (Option<T> o : options) cellW = Math.max(cellW, font.width(o.label) + 10);
		int lines = 0;
		String grp = null;
		for (Option<T> o : options) {
			if (!o.group.equals(grp)) {
				grp = o.group;
				if (!grp.isEmpty()) lines++;
			}
			lines++;
		}
		// the help line under the grid, sized for the longest help so it never clips
		int helpLines = 1;
		for (Option<T> o : options) helpLines = Math.max(helpLines, Math.min(3, font.split(Component.literal(o.help), Math.max(160, cellW * 2) - 10).size()));
		int helpH = helpLines * 9 + 8;
		// as few columns as fit the screen's height, balanced so they end near the same row
		int maxRows = Math.max(4, (screenH - 16 - helpH) / 12);
		List<int[]> cells = new ArrayList<>(); // x, y, option index or -1 for a heading
		List<String> heads = new ArrayList<>();
		int col = -1;
		for (int cols = 1; cols <= 8 && col < 0; cols++) {
			// start from an even split; headings that can't sit at a column's foot push it a row or two longer
			for (int rows = Math.max(4, (lines + cols - 1) / cols); rows <= maxRows; rows++) {
				if (pack(rows, cells, heads) < cols) {
					col = cols - 1;
					break;
				}
			}
		}
		if (col < 0) col = pack(maxRows, cells, heads);
		int usedRows = 0;
		for (int[] c : cells) usedRows = Math.max(usedRows, c[1] + 1);
		popW = Math.max((col + 1) * (cellW + 2) + 6, Math.max(160, cellW * 2));
		popH = usedRows * 12 + 8 + helpH;
		popX = Math.max(4, Math.min(getX(), screenW - popW - 4));
		popY = getY() + getHeight() + 2;
		if (popY + popH > screenH - 4) popY = Math.max(4, Math.min(getY() - popH - 2, screenH - popH - 4));
		g.pose().pushPose();
		g.pose().translate(0, 0, 300);
		g.fill(popX, popY, popX + popW, popY + popH, 0xF8081214);
		Ui.outline(g, popX, popY, popW, popH, Ui.ACCENT_DIM);
		String help = options.get(index).help;
		int h = 0;
		for (int[] c : cells) {
			int cx = popX + 4 + c[0] * (cellW + 2), cy = popY + 4 + c[1] * 12;
			if (c[2] < 0) {
				g.drawString(font, heads.get(h++).toUpperCase(), cx + 2, cy + 2, Ui.DIM, false);
				continue;
			}
			Option<T> o = options.get(c[2]);
			boolean hov = Ui.inside(mx, my, cx, cy, cellW, 11);
			if (hov) help = o.help;
			if (c[2] == index) g.fill(cx, cy, cx + cellW, cy + 11, (Ui.ACCENT & 0x00FFFFFF) | 0xFF000000);
			else if (hov) g.fill(cx, cy, cx + cellW, cy + 11, Ui.PANEL_HI);
			g.drawString(font, o.label, cx + 5, cy + 2, c[2] == index ? 0xFF061012 : Ui.TEXT, false);
			hits.add(new int[]{cx, cy, cellW, 11, c[2]});
		}
		g.fill(popX + 4, popY + popH - helpH, popX + popW - 4, popY + popH - helpH + 1, Ui.BORDER);
		var lines2 = font.split(Component.literal(help), popW - 10);
		for (int i = 0; i < Math.min(helpLines, lines2.size()); i++) g.drawString(font, lines2.get(i), popX + 5, popY + popH - helpH + 4 + i * 9, Ui.DIM, false);
		g.pose().popPose();
	}

	/** Lays the options into columns of at most {@code rows}, a heading wherever the group changes; returns the last column. */
	private int pack(int rows, List<int[]> cells, List<String> heads) {
		cells.clear();
		heads.clear();
		int col = 0, row = 0;
		String grp = null;
		for (int i = 0; i < options.size(); i++) {
			Option<T> o = options.get(i);
			if (!o.group.equals(grp)) {
				grp = o.group;
				if (!grp.isEmpty()) {
					if (row >= rows - 1) { // never leave a heading alone at the bottom of a column
						col++;
						row = 0;
					}
					cells.add(new int[]{col, row++, -1});
					heads.add(grp);
				}
			}
			if (row >= rows) {
				col++;
				row = 0;
			}
			cells.add(new int[]{col, row++, i});
		}
		return col;
	}
	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		defaultButtonNarrationText(out);
	}
}
