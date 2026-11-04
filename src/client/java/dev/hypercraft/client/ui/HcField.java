package dev.hypercraft.client.ui;

import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * A text box in the terminal's colours. It can show a unit or "0 = off" note inside its right edge, flag a bad value
 * in red with the reason as its tooltip, and step a number with the scroll wheel.
 */
public class HcField extends EditBox {
	private String suffix = "";
	private double step;
	private Function<String, String> validator;
	private String error;
	private String tip = "";

	public HcField(Font font, int x, int y, int w, int h, String name) {
		super(font, x, y, w, h, Component.literal(name));
		setMaxLength(64);
	}

	/** Grey text at the right edge: a unit ("s", "$", "x") or what an empty or zero value means. */
	public HcField suffix(String s) {
		suffix = s;
		return this;
	}

	public HcField hint(String s) {
		setHint(Component.literal(s).withColor(0xFF4E6E6A));
		return this;
	}

	public HcField tip(String s) {
		tip = s;
		return this;
	}

	/** A number field: digits, one point, optional minus; the scroll wheel nudges it by {@code step}. */
	public HcField numeric(double step) {
		this.step = step;
		setFilter(s -> s.matches("-?[0-9]*\\.?[0-9]*"));
		return this;
	}

	/** Returns an error message for a bad value, or null when it's fine; checked as the player types. */
	public HcField validate(Function<String, String> v) {
		validator = v;
		return this;
	}

	public HcField value(String s) {
		setValue(s);
		return this;
	}

	/** The current problem with the value, or null. */
	public String error() {
		error = validator == null ? null : validator.apply(getValue());
		return error;
	}

	public double num(double def) {
		try {
			return Double.parseDouble(getValue());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (step <= 0 || !isMouseOver(mx, my) || dy == 0) return false;
		double v = num(0) + (dy > 0 ? step : -step);
		if (Math.abs(v) < 1e-9) v = 0;
		setValue(dev.hypercraft.trading.Fmt.size(v));
		return true;
	}

	@Override
	public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		Font font = Minecraft.getInstance().font;
		super.renderWidget(g, mx, my, pt);
		if (!isVisible()) return;
		String err = error();
		// recolour vanilla's grey frame: red with a problem, accent when focused, the panel border otherwise
		int frame = err != null ? Ui.RED : isFocused() ? Ui.ACCENT : isHovered() ? Ui.ACCENT_DIM : Ui.BORDER;
		Ui.outline(g, x - 1, y - 1, w + 2, h + 2, Ui.PANEL);
		Ui.outline(g, x, y, w, h, frame);
		if (!suffix.isEmpty() && font.width(getValue()) + font.width(suffix) + 12 < w) {
			g.drawString(font, suffix, x + w - 4 - font.width(suffix), y + (h - 8) / 2, 0xFF4E6E6A, false);
		}
		String want = err != null ? "⚠ " + err : tip;
		if (!want.equals(shownTip)) {
			shownTip = want;
			setTooltip(want.isEmpty() ? null : Tooltip.create(Component.literal(want)));
		}
	}

	private String shownTip = "";
}
