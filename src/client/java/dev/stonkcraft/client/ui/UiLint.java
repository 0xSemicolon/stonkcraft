package dev.stonkcraft.client.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Dev-only layout lint ({@code -Dstonkcraft.uilint=true}, on during the UI tour). While a screen renders, every
 * string drawn (via {@code GuiGraphicsMixin}), every {@link Ui#panel} and every visible widget is recorded in GUI
 * coordinates. {@link #check} then reports text that overlaps other text or straddles a widget's edge, widgets that
 * overlap, and anything that leaves its panel or the screen. Content drawn above z 100 (tooltips, dropdowns) is an
 * overlay and is ignored, and text is clipped to the active scissor first.
 */
public final class UiLint {
	public static final boolean ENABLED = Boolean.getBoolean("stonkcraft.uilint") || Boolean.getBoolean("stonkcraft.uitour");
	public static boolean recording;
	public static boolean highlight;

	private record Box(int x0, int y0, int x1, int y1) {
		boolean empty() {
			return x1 <= x0 || y1 <= y0;
		}

		Box clip(Box o) {
			return new Box(Math.max(x0, o.x0), Math.max(y0, o.y0), Math.min(x1, o.x1), Math.min(y1, o.y1));
		}

		boolean hits(Box o) {
			return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1;
		}

		boolean contains(Box o) {
			return o.x0 >= x0 && o.y0 >= y0 && o.x1 <= x1 && o.y1 <= y1;
		}

		@Override
		public String toString() {
			return "[" + x0 + "," + y0 + " " + (x1 - x0) + "x" + (y1 - y0) + "]";
		}
	}

	private record Text(String s, Box box) {}

	private record Widget(String name, boolean vanilla, boolean tipless, Box box) {}

	public record Issue(String kind, String what, int x0, int y0, int x1, int y1) {}

	private static final List<Text> texts = new ArrayList<>();
	private static final List<Box> panels = new ArrayList<>();
	private static final List<Widget> widgets = new ArrayList<>();
	private static final Deque<Box> scissor = new ArrayDeque<>();
	private static int screenW, screenH;
	private static List<Issue> lastIssues = List.of();
	private static final JsonObject report = new JsonObject();

	private UiLint() {}

	public static void install() {
		if (!ENABLED) return;
		ScreenEvents.BEFORE_INIT.register((mc, screen, w, h) -> {
			ScreenEvents.beforeRender(screen).register((s, g, mx, my, pt) -> begin(s));
			ScreenEvents.afterRender(screen).register((s, g, mx, my, pt) -> end(s, g));
		});
	}

	private static void begin(Screen s) {
		texts.clear();
		panels.clear();
		widgets.clear();
		scissor.clear();
		screenW = s.width;
		screenH = s.height;
		recording = true;
	}

	private static void end(Screen s, GuiGraphics g) {
		recording = false;
		for (var c : s.children()) {
			if (c instanceof AbstractWidget w && w.visible && w.getWidth() > 0 && w.getHeight() > 0) {
				String name = w.getMessage().getString();
				if (name.isEmpty()) name = w.getClass().getSimpleName();
				// our buttons and dropdowns should explain themselves; fields carry their meaning in hints and suffixes
				boolean tipless = (w instanceof HcButton || w instanceof HcDropdown<?>) && w.getTooltip() == null && name.length() > 1
						&& !java.util.Set.of("Save", "Cancel", "Done", "Apply", "Later", "Discard", "Keep editing", "Rename").contains(name);
				widgets.add(new Widget(name, w.getClass().getName().startsWith("net.minecraft."), tipless, new Box(w.getX(), w.getY(), w.getX() + w.getWidth(), w.getY() + w.getHeight())));
			}
		}
		if (highlight) {
			g.pose().pushPose();
			g.pose().translate(0, 0, 500);
			for (Issue i : lastIssues) Ui.outline(g, i.x0 - 1, i.y0 - 1, i.x1 - i.x0 + 2, i.y1 - i.y0 + 2, 0xFFFF2020);
			g.pose().popPose();
		}
	}

	private static Box transform(Matrix4f m, float x, float y, float w, float h) {
		Vector3f a = m.transformPosition(new Vector3f(x, y, 0)), b = m.transformPosition(new Vector3f(x + w, y + h, 0));
		return new Box(Math.round(Math.min(a.x, b.x)), Math.round(Math.min(a.y, b.y)), Math.round(Math.max(a.x, b.x)), Math.round(Math.max(a.y, b.y)));
	}

	private static boolean overlay(Matrix4f m) {
		return m.m32() >= 100;
	}

	/** Called for every string drawn while recording; width includes the font's 1px trailing gap. */
	public static void text(Matrix4f m, int x, int y, int width, String s) {
		if (s.isBlank() || width <= 1 || overlay(m)) return;
		// glyphs sit in the top 8 rows of a 9px line; drop the trailing gap
		Box b = transform(m, x, y, width - 1, 8);
		if (!scissor.isEmpty()) b = b.clip(scissor.peek());
		if (!b.empty()) texts.add(new Text(s.strip(), b));
	}

	public static void panel(Matrix4f m, int x, int y, int w, int h) {
		if (recording && !overlay(m)) panels.add(transform(m, x, y, w, h));
	}

	public static void pushScissor(int x0, int y0, int x1, int y1) {
		if (!recording) return;
		Box b = new Box(x0, y0, x1, y1);
		scissor.push(scissor.isEmpty() ? b : b.clip(scissor.peek()));
	}

	public static void popScissor() {
		if (recording && !scissor.isEmpty()) scissor.pop();
	}

	/** Lints the last frame drawn, stores it under {@code name} in ui_lint.json and returns the problems found. */
	public static List<Issue> check(String name) {
		List<Issue> out = new ArrayList<>();
		Box screen = new Box(0, 0, screenW, screenH);
		for (int i = 0; i < texts.size(); i++) {
			Text a = texts.get(i);
			if (!screen.contains(a.box)) out.add(issue("offscreen", '"' + a.s + '"', a.box));
			else if (!panels.isEmpty() && panels.stream().noneMatch(p -> p.contains(a.box)) && panels.stream().anyMatch(p -> p.hits(a.box)))
				out.add(issue("leaves panel", '"' + a.s + '"', a.box));
			for (int j = i + 1; j < texts.size(); j++) {
				Text b = texts.get(j);
				if (!a.box.hits(b.box) || (a.box.equals(b.box) && a.s.equals(b.s))) continue;
				if (widgets.stream().anyMatch(w -> w.box.contains(a.box) && w.box.contains(b.box))) continue;
				Box both = new Box(Math.min(a.box.x0, b.box.x0), Math.min(a.box.y0, b.box.y0), Math.max(a.box.x1, b.box.x1), Math.max(a.box.y1, b.box.y1));
				out.add(issue("text overlap", '"' + a.s + "\" / \"" + b.s + '"', both));
			}
			for (Widget w : widgets) {
				if (w.box.hits(a.box) && !w.box.contains(a.box)) out.add(issue("text under widget", '"' + a.s + "\" / [" + w.name + "]", a.box));
			}
		}
		for (int i = 0; i < widgets.size(); i++) {
			Widget a = widgets.get(i);
			if (!screen.contains(a.box)) out.add(issue("offscreen", "[" + a.name + "]", a.box));
			else if (!panels.isEmpty() && panels.stream().noneMatch(p -> p.contains(a.box))) out.add(issue("leaves panel", "[" + a.name + "]", a.box));
			for (int j = i + 1; j < widgets.size(); j++) {
				Widget b = widgets.get(j);
				if (a.box.hits(b.box)) out.add(issue("widget overlap", "[" + a.name + "] / [" + b.name + "]", a.box));
			}
		}
		lastIssues = out;
		JsonObject entry = new JsonObject();
		entry.addProperty("gui", screenW + "x" + screenH);
		entry.addProperty("vanillaWidgets", widgets.stream().filter(Widget::vanilla).count());
		JsonArray tipless = new JsonArray();
		for (Widget w : widgets) if (w.tipless) tipless.add(w.name);
		entry.add("noTooltip", tipless);
		JsonArray arr = new JsonArray();
		for (Issue i : out) {
			JsonObject o = new JsonObject();
			o.addProperty("kind", i.kind);
			o.addProperty("what", i.what);
			o.addProperty("at", i.x0 + "," + i.y0 + " " + (i.x1 - i.x0) + "x" + (i.y1 - i.y0));
			arr.add(o);
		}
		entry.add("issues", arr);
		report.add(name, entry);
		return out;
	}

	private static Issue issue(String kind, String what, Box b) {
		return new Issue(kind, what, b.x0, b.y0, b.x1, b.y1);
	}

	public static void write(String file) {
		Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		try {
			Path p = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve(file);
			Files.createDirectories(p.getParent());
			Files.writeString(p, gson.toJson(report));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
