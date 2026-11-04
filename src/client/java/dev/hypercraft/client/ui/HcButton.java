package dev.hypercraft.client.ui;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** A button in the terminal's chip style, for every HyperCraft screen. Primary buttons are filled with the accent. */
public class HcButton extends AbstractButton {
	public enum Style { NORMAL, PRIMARY, DANGER, TAB }

	private final Consumer<HcButton> onPress;
	private Style style;
	private boolean selected;
	private int color = Ui.ACCENT;
	/** For a list cell: an item drawn at the left, the label beside it, and a dim value at the right. */
	private net.minecraft.world.item.ItemStack icon = net.minecraft.world.item.ItemStack.EMPTY;
	private String right = "";

	public HcButton(int x, int y, int w, int h, String label, Style style, Consumer<HcButton> onPress) {
		super(x, y, w, h, Component.literal(label));
		this.style = style;
		this.onPress = onPress;
	}

	public HcButton(int x, int y, int w, int h, String label, Consumer<HcButton> onPress) {
		this(x, y, w, h, label, Style.NORMAL, onPress);
	}

	public HcButton tip(String text) {
		setTooltip(text == null || text.isEmpty() ? null : Tooltip.create(Component.literal(text)));
		return this;
	}

	public HcButton label(String text) {
		setMessage(Component.literal(text));
		return this;
	}

	public HcButton style(Style s) {
		style = s;
		return this;
	}

	/** For tabs and toggles: drawn filled while selected. */
	public HcButton selected(boolean on) {
		selected = on;
		return this;
	}

	public boolean selected() {
		return selected;
	}

	/** Lays the button out as a list cell: icon, label left-aligned, {@code right} right-aligned. */
	public HcButton cell(net.minecraft.world.item.ItemStack icon, String right) {
		this.icon = icon;
		this.right = right == null ? "" : right;
		return this;
	}

	public HcButton color(int c) {
		color = c;
		return this;
	}

	@Override
	public void onPress() {
		onPress.accept(this);
	}

	@Override
	protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		int c = switch (style) {
			case DANGER -> Ui.RED;
			default -> color;
		};
		boolean hot = isHoveredOrFocused();
		boolean filled = style == Style.PRIMARY || selected;
		int bg, border, text;
		if (!active) {
			bg = 0x18000000 | (Ui.DIM & 0x00FFFFFF);
			border = 0x60000000 | (Ui.DIM & 0x00FFFFFF);
			text = 0xFF4E6E6A;
		} else if (filled) {
			bg = (c & 0x00FFFFFF) | (hot ? 0xFF000000 : 0xD8000000);
			border = (c & 0x00FFFFFF) | 0xFF000000;
			text = 0xFF061012;
		} else {
			bg = (c & 0x00FFFFFF) | (hot ? 0x58000000 : 0x26000000);
			border = (c & 0x00FFFFFF) | (hot ? 0xFF000000 : 0x90000000);
			text = Ui.TEXT;
		}
		g.fill(x, y, x + w, y + h, bg);
		Ui.outline(g, x, y, w, h, border);
		if (isFocused() && !isHovered()) Ui.outline(g, x - 1, y - 1, w + 2, h + 2, 0x80FFFFFF);
		var font = Minecraft.getInstance().font;
		if (!icon.isEmpty() || !right.isEmpty()) {
			int lx = x + 4;
			if (!icon.isEmpty()) {
				g.renderItem(icon, x + 2, y + (h - 16) / 2);
				lx = x + 21;
			}
			int rw = right.isEmpty() ? 0 : font.width(right);
			if (rw > 0) g.drawString(font, right, x + w - 4 - rw, y + (h - 8) / 2, filled ? text : Ui.DIM, false);
			g.drawString(font, Ui.ellipsize(font, getMessage().getString(), x + w - 4 - rw - (rw > 0 ? 6 : 0) - lx), lx, y + (h - 8) / 2, text, false);
			return;
		}
		String label = Ui.ellipsize(font, getMessage().getString(), w - 6);
		g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, text, false);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		defaultButtonNarrationText(out);
	}
}
