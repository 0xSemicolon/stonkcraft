package dev.stonkcraft.client.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/**
 * The frame every Stonkcraft panel shares: a header (accent bar, title, a chip on the right), an optional status line,
 * content laid out top to bottom by measured height ({@link Flow}), and a footer. Enter runs the primary footer
 * button; Esc closes, but asks first when {@link #snapshot()} says there are unsaved edits. The panel is as tall as its
 * content, so wrapped text can never run into the widgets below it.
 */
public abstract class HcScreen extends Screen {
	protected static final int PAD = 12;
	protected static final int ROW = 18;
	protected int px, py, pw, ph;
	private final List<Label> labels = new ArrayList<>();
	private final List<HcDropdown<?>> dropdowns = new ArrayList<>();
	private final List<HcButton> footerButtons = new ArrayList<>();
	private final List<HcButton> confirmButtons = new ArrayList<>();
	private HcButton primary;
	private String initialSnapshot;
	private boolean confirming;
	private String toast;
	private int toastColor;
	private long toastAt;
	private int contentTop;

	private record Label(int x, int y, List<FormattedCharSequence> lines, int color) {}

	protected HcScreen(String title) {
		super(Component.literal(title));
	}

	// ---- what subclasses provide

	protected int panelWidth() {
		return 300;
	}

	protected int accent() {
		return Ui.ACCENT;
	}

	/** The header's title; defaults to the screen title. */
	protected String titleText() {
		return title.getString().toUpperCase();
	}

	/** Text at the right of the header, e.g. the linked account; null for none. */
	protected String headerRight() {
		return null;
	}

	protected int headerRightColor() {
		return Ui.ACCENT;
	}

	/** One line under the title: what this block is doing right now. Null for no status line. */
	protected String status() {
		return null;
	}

	protected int statusColor() {
		return Ui.DIM;
	}

	/** Builds the content. Widgets are placed with coordinates relative to the panel's top-left corner. */
	protected abstract void layout(Flow f);

	/** Adds footer buttons; the default is Save (primary) and Cancel. */
	protected void footer(Footer f) {
		f.right("Cancel", HcButton.Style.NORMAL, this::onClose);
		f.primary("Save", this::save);
	}

	/** Saves and closes; screens with a Save button override it. */
	protected void save() {
		onClose();
	}

	/** A string that changes whenever there's something to save; null turns off the unsaved-changes check. */
	protected String snapshot() {
		return null;
	}

	/** Drawn after the labels, in panel-relative coordinates, for live content in areas reserved with {@link Flow#reserve}. */
	protected void drawContent(GuiGraphics g, int mx, int my) {}

	// ---- layout

	/** A cursor that moves down the panel. All coordinates are relative to the panel. */
	public final class Flow {
		public final int left = PAD, width;
		public int y;

		Flow(int top) {
			this.width = pw - 2 * PAD;
			this.y = top;
		}

		/** Wrapped text across the full width; returns its height. */
		public int text(String s, int color) {
			return text(s, color, left, width);
		}

		public int text(String s, int color, int x, int w) {
			List<FormattedCharSequence> lines = font.split(Component.literal(s), w);
			labels.add(new Label(x, y, lines, color));
			int h = lines.size() * 10;
			y += h + 4;
			return h;
		}

		/** Text on a row already made by {@link #row}, at x; the flow moves down only if the text wraps past the row. */
		public void beside(int ry, int rh, String s, int color, int x, int w) {
			List<FormattedCharSequence> lines = font.split(Component.literal(s), w);
			int h = lines.size() * 10;
			labels.add(new Label(x, lines.size() == 1 ? ry + (rh - 8) / 2 : ry, lines, color));
			y = Math.max(y, ry + Math.max(rh, h) + 4);
		}

		/** A label in the left column and room for widgets beside it; returns the row's y. */
		public int row(String label, int h) {
			return row(label, h, Ui.DIM);
		}

		public int row(String label, int h, int color) {
			int ry = y;
			if (label != null) labels.add(new Label(left, ry + (h - 8) / 2, List.of(Component.literal(label).getVisualOrderText()), color));
			y += h + 4;
			return ry;
		}

		/** A small heading in caps, as in the Fund screen's numbered sections. */
		public void heading(String s) {
			if (y > contentTop + 2) {
				gap(2);
			}
			labels.add(new Label(left, y, List.of(Component.literal(s.toUpperCase()).getVisualOrderText()), Ui.DIM));
			y += 12;
		}

		/** Space for content drawn live in {@link #drawContent}; returns its y. */
		public int reserve(int h) {
			int ry = y;
			y += h + 4;
			return ry;
		}

		public void gap(int h) {
			y += h;
		}

		/** A thin divider line. */
		public void rule() {
			int ry = y + 1;
			labels.add(new Label(-1, ry, List.of(), 0));
			y += 6;
		}

		public <W extends AbstractWidget> W add(W w) {
			if (w instanceof HcDropdown<?> d) dropdowns.add(d);
			return addRenderableWidget(w);
		}
	}

	/** Footer buttons: the primary one on the far right, others to its left, extras from the left edge. */
	public final class Footer {
		private int right, left;
		private final int y;

		Footer(int y) {
			this.y = y;
			this.right = pw - PAD;
			this.left = PAD;
		}

		private final List<HcButton> rights = new ArrayList<>();

		public HcButton primary(String label, Runnable r) {
			primary = right(label, HcButton.Style.PRIMARY, r);
			return primary;
		}

		/** Placed by {@link #place}: the primary button far right, the others to its left in the order they were added. */
		public HcButton right(String label, HcButton.Style style, Runnable r) {
			int w = Math.max(56, font.width(label) + 16);
			HcButton b = addRenderableWidget(new HcButton(0, y, w, ROW, label, style, x -> r.run()));
			rights.add(b);
			footerButtons.add(b);
			return b;
		}

		void place() {
			if (primary != null) {
				rights.remove(primary);
				rights.add(0, primary);
			}
			for (HcButton b : rights) {
				right -= b.getWidth();
				b.setX(right);
				right -= 6;
			}
		}

		public HcButton left(String label, HcButton.Style style, Runnable r) {
			int w = Math.max(56, font.width(label) + 16);
			HcButton b = addRenderableWidget(new HcButton(left, y, w, ROW, label, style, x -> r.run()));
			left += w + 6;
			footerButtons.add(b);
			return b;
		}
	}

	@Override
	protected void init() {
		labels.clear();
		dropdowns.clear();
		footerButtons.clear();
		confirmButtons.clear();
		primary = null;
		confirming = false;
		pw = Math.min(panelWidth(), width - 8);
		contentTop = status() != null ? 40 : 30;
		Flow f = new Flow(contentTop);
		int first = children().size();
		layout(f);
		content.clear();
		for (int i = first; i < children().size(); i++) if (children().get(i) instanceof AbstractWidget w) content.add(w);
		ph = f.y + 4 + ROW + 8;
		// taller than the window: the content scrolls between a fixed header and footer
		// clear of the ticker tape along the top edge when it's showing
		int topGap = dev.stonkcraft.client.ClientConfig.get().tape ? 13 : 4;
		if (ph > height - topGap - 4) ph = height - topGap - 4;
		viewBottom = ph - ROW - 12;
		scrollMax = Math.max(0, f.y - viewBottom);
		scroll = Math.min(scroll, scrollMax);
		Footer ft = new Footer(ph - ROW - 8);
		footer(ft);
		ft.place();
		// asked when Esc would throw away edits
		int cy = ph - ROW - 8;
		int x = pw - PAD;
		for (String[] spec : new String[][]{{"Save", "s"}, {"Discard", "d"}, {"Keep editing", "k"}}) {
			int w = Math.max(56, font.width(spec[0]) + 16);
			x -= w;
			HcButton b = addRenderableWidget(new HcButton(x, cy, w, ROW, spec[0], spec[1].equals("s") ? HcButton.Style.PRIMARY : spec[1].equals("d") ? HcButton.Style.DANGER : HcButton.Style.NORMAL, btn -> {
				switch (spec[1]) {
					case "s" -> save();
					case "d" -> super.onClose();
					default -> setConfirming(false);
				}
			}));
			x -= 6;
			b.visible = false;
			confirmButtons.add(b);
		}
		px = (width - pw) / 2;
		py = Math.max(topGap, (height - ph) / 2);
		for (GuiEventListener c : children()) {
			if (c instanceof AbstractWidget w) w.setPosition(w.getX() + px, w.getY() + py);
		}
		baseY.clear();
		scrolledAway.clear();
		for (AbstractWidget w : content) baseY.put(w, w.getY());
		applyScroll();
		if (initialSnapshot == null) initialSnapshot = snapshot();
	}

	private final List<AbstractWidget> content = new ArrayList<>();
	private final java.util.Map<AbstractWidget, Integer> baseY = new java.util.IdentityHashMap<>();
	private int scroll, scrollMax, viewBottom;

	/** How far the content has scrolled, for live content in {@link #drawContent} that should stay put. */
	protected int scrollOffset() {
		return scroll;
	}

	/** Content widgets this class hid because they're scrolled out of view, as opposed to hidden by the screen. */
	private final java.util.Set<AbstractWidget> scrolledAway = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	private void applyScroll() {
		for (AbstractWidget w : content) {
			w.setY(baseY.get(w) - scroll);
			if (scrollMax <= 0) continue;
			// a widget partly under the header or footer would take Tab focus and show its tooltip there: hide it until it's fully in view
			boolean in = w.getY() >= py + contentTop - 2 && w.getY() + w.getHeight() <= py + viewBottom + 2;
			if (!in && w.visible) {
				w.visible = false;
				scrolledAway.add(w);
			} else if (in && scrolledAway.remove(w)) {
				w.visible = true;
			}
		}
	}

	private boolean inView(double my) {
		return my >= py + contentTop - 2 && my < py + viewBottom + 2;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (super.mouseScrolled(mx, my, dx, dy)) return true;
		if (scrollMax <= 0 || dy == 0) return false;
		scroll = Math.max(0, Math.min(scrollMax, scroll - (int) Math.signum(dy) * 20));
		applyScroll();
		return true;
	}

	/** Text typed into named fields, carried across {@link #relayout}. */
	private final java.util.Map<String, String> kept = new java.util.HashMap<>();

	/** A field whose value survives {@link #relayout}; {@code name} must be unique on the screen. */
	protected HcField field(int x, int y, int w, String name, String initial) {
		HcField box = new HcField(font, x, y, w, 14, name);
		box.setValue(kept.getOrDefault(name, initial));
		return box;
	}

	/** Lays the screen out again, e.g. after a mode change shows different fields. Keep values in fields, not widgets. */
	protected void relayout() {
		GuiEventListener focused = getFocused();
		String focusName = focused instanceof AbstractWidget w ? w.getMessage().getString() : null;
		for (GuiEventListener c : children()) {
			if (c instanceof HcField h) kept.put(h.getMessage().getString(), h.getValue());
		}
		rebuildWidgets();
		if (focusName != null) {
			for (GuiEventListener c : children()) {
				if (c instanceof EditBox e && e.getMessage().getString().equals(focusName)) setFocused(e);
			}
		}
	}

	private void setConfirming(boolean on) {
		confirming = on;
		for (HcButton b : footerButtons) b.visible = !on;
		for (HcButton b : confirmButtons) b.visible = on;
	}

	protected boolean dirty() {
		String now = snapshot();
		return now != null && initialSnapshot != null && !now.equals(initialSnapshot);
	}

	/** Treats the current values as saved, e.g. after Apply. */
	protected void markSaved() {
		initialSnapshot = snapshot();
	}

	/** A short message in the status line's place for a few seconds. */
	public void toast(String text, int color) {
		toast = text;
		toastColor = color;
		toastAt = System.currentTimeMillis();
	}

	protected void chime() {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.6f, 0.5f));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (dirty() && !confirming) {
			setConfirming(true);
			return;
		}
		super.onClose();
	}

	@Override
	public boolean keyPressed(int key, int scan, int mods) {
		for (HcDropdown<?> d : dropdowns) {
			if (d.isOpen() && key == GLFW.GLFW_KEY_ESCAPE) {
				d.close();
				return true;
			}
		}
		if (confirming && key == GLFW.GLFW_KEY_ESCAPE) {
			setConfirming(false);
			return true;
		}
		if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && primary != null && primary.active && !confirming) {
			primary.onPress();
			return true;
		}
		return super.keyPressed(key, scan, mods);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		for (HcDropdown<?> d : dropdowns) {
			if (d.isOpen()) return d.clickPopup(mx, my);
		}
		if (scrollMax > 0 && !inView(my)) {
			// scrolled-away content is clipped, so only the fixed footer can take this click
			for (GuiEventListener c : children()) {
				if (c instanceof AbstractWidget w && !content.contains(w) && w.mouseClicked(mx, my, button)) {
					setFocused(w);
					return true;
				}
			}
			return false;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
		g.fill(0, 0, width, height, 0xA0000000);
		Ui.panel(g, px, py, pw, ph);
		g.fill(px, py, px + pw, py + 2, accent());
		String right = headerRight();
		int titleMax = pw - 2 * PAD - (right == null ? 0 : Math.min(font.width(right), pw / 2) + 8);
		String t = titleText();
		if (font.width(t) * 1.25f <= titleMax) Ui.textScaled(g, font, t, px + PAD, py + 9, 1.25f, accent());
		else Ui.text(g, font, Ui.ellipsize(font, t, titleMax), px + PAD, py + 10, accent());
		if (right != null) Ui.textRight(g, font, Ui.ellipsize(font, right, pw / 2), px + pw - 10, py + 11, headerRightColor());
		long since = System.currentTimeMillis() - toastAt;
		boolean showToast = toast != null && since < 3500;
		String st = showToast ? toast : status();
		if (st != null) {
			int c = showToast ? toastColor : statusColor();
			if (showToast && since > 3000) c = (c & 0x00FFFFFF) | ((int) (255 * (3500 - since) / 500.0) << 24);
			Ui.text(g, font, Ui.ellipsize(font, st, pw - 2 * PAD), px + PAD, py + 25, c);
		}
		if (confirming) Ui.text(g, font, "Unsaved changes", px + PAD, py + ph - ROW - 8 + 5, Ui.YELLOW);
		if (scrollMax > 0) g.enableScissor(px, py + contentTop - 2, px + pw, py + viewBottom + 2);
		for (Label l : labels) {
			int ly = py + l.y - scroll;
			if (l.x < 0) {
				g.fill(px + PAD, ly, px + pw - PAD, ly + 1, Ui.BORDER);
				continue;
			}
			for (int i = 0; i < l.lines.size(); i++) g.drawString(font, l.lines.get(i), px + l.x, ly + i * 10, l.color, false);
		}
		g.pose().pushPose();
		g.pose().translate(px, py - scroll, 0);
		drawContent(g, mx - px, my - py + scroll);
		g.pose().popPose();
		if (scrollMax > 0) g.disableScissor();
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		renderBackground(g, mx, my, pt);
		boolean clip = scrollMax > 0;
		if (clip) g.enableScissor(px, py + contentTop - 2, px + pw, py + viewBottom + 2);
		int cmx = clip && !inView(my) ? -1 : mx;
		for (AbstractWidget w : content) w.render(g, cmx, my, pt);
		if (clip) {
			g.disableScissor();
			// scrollbar along the right edge of the scrolling area
			int top = py + contentTop, h = viewBottom - contentTop, total = h + scrollMax;
			int bh = Math.max(12, h * h / total), by = top + (h - bh) * scroll / scrollMax;
			g.fill(px + pw - 4, top, px + pw - 2, top + h, 0x30FFFFFF);
			g.fill(px + pw - 4, by, px + pw - 2, by + bh, Ui.ACCENT_DIM);
			// content fades out where more is below
			if (scroll < scrollMax) g.fillGradient(px + 1, py + viewBottom - 8, px + pw - 5, py + viewBottom + 2, 0x000C1A1C, 0xF00C1A1C);
		}
		for (GuiEventListener c : children()) {
			if (c instanceof AbstractWidget w && !content.contains(w)) w.render(g, mx, my, pt);
		}
		for (HcDropdown<?> d : dropdowns) d.renderPopup(g, mx, my, width, height);
	}
}
