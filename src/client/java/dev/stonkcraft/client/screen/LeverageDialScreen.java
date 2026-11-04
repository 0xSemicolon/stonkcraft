package dev.stonkcraft.client.screen;

import com.google.gson.JsonObject;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcField;
import dev.stonkcraft.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;

public class LeverageDialScreen extends BlockConfigScreen {
	private boolean cross;
	private HcField minBox, maxBox;
	private HcButton crossB, isoB;
	private int barsY;

	public LeverageDialScreen(JsonObject data) {
		super("Leverage Dial", data);
		cross = !data.has("cross") || data.get("cross").getAsBoolean();
	}

	@Override
	protected int accent() {
		return Ui.YELLOW;
	}

	@Override
	protected void fields(Flow f) {
		int y = f.row("Range", 14);
		minBox = numField(f, f.left + 58, y, 44, "min", data.get("min").getAsDouble(), 1).suffix("x");
		maxBox = numField(f, f.left + 122, y, 44, "max", data.get("max").getAsDouble(), 1).suffix("x");
		minBox.validate(s -> minBox.num(0) < 1 ? "At least 1x" : null);
		maxBox.validate(s -> {
			ClientState.Ticker t = ClientState.ticker(coinBox.getValue());
			if (maxBox.num(0) < 1) return "At least 1x";
			return t != null && maxBox.num(0) > t.maxLeverage ? ClientState.display(t.name) + " allows at most " + t.maxLeverage + "x" : null;
		});
		crossB = f.add(new HcButton(f.left + 176, y - 2, 40, 18, "Cross", HcButton.Style.TAB, b -> setCross(true)).selected(cross)
				.tip("Cross margin: the whole account backs the position"));
		isoB = f.add(new HcButton(f.left + 218, y - 2, 52, 18, "Isolated", HcButton.Style.TAB, b -> setCross(false)).selected(!cross)
				.tip("Isolated margin: only the position's own margin is at risk"));
		barsY = f.reserve(40);
		f.text("Signal strength 1 sets the low end, 15 the high end. Signal 0 leaves leverage alone. Drive it with a comparator to make leverage part of your circuit.", Ui.DIM);
	}

	private void setCross(boolean on) {
		cross = on;
		crossB.selected(on);
		isoB.selected(!on);
	}

	@Override
	protected String summary() {
		String now = power() > 0 ? " · now signal " + power() + (data.has("applied") && data.get("applied").getAsInt() > 0 ? ", set " + data.get("applied").getAsInt() + "x" : "")
				: " · no signal now";
		return "Sets " + (coin().isEmpty() ? "the coin's" : ClientState.display(coin())) + " leverage " + lo() + "–" + hi() + "x " + (cross ? "cross" : "isolated") + now;
	}

	/** The redstone power at the dial when the screen opened. */
	private int power() {
		return data.has("power") ? data.get("power").getAsInt() : 0;
	}

	private int lo() {
		return (int) Math.min(parse(minBox, 1), parse(maxBox, 5));
	}

	private int hi() {
		return (int) Math.max(parse(minBox, 1), parse(maxBox, 5));
	}

	@Override
	protected void writeFields(JsonObject out) {
		out.addProperty("min", parse(minBox, 1));
		out.addProperty("max", parse(maxBox, 5));
		out.addProperty("cross", cross);
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		super.drawContent(g, mx, my);
		// preview of the signal → leverage mapping
		int lo = lo(), hi = hi();
		int w = pw - 2 * PAD, bw = w / 15, y = barsY;
		for (int p = 1; p <= 15; p++) {
			int lev = lo + Math.round((hi - lo) * (p - 1) / 14f);
			int x = PAD + (p - 1) * bw;
			int hgt = 4 + (int) (20.0 * (p - 1) / 14);
			boolean hov = Ui.inside(mx, my, x, y, bw, 30);
			g.fill(x + 1, y + 24 - hgt, x + bw - 1, y + 24, hov ? 0xFFFFD040 : 0xFFFF3030 & (0x55FFFFFF + p * 0x0A000000));
			if (p == power()) Ui.outline(g, x, y + 23 - hgt, bw, hgt + 2, Ui.ACCENT); // where the dial's input sits now
			if (hov) Ui.textCentered(g, font, "signal " + p + " → " + lev + "x", PAD + w / 2, y + 30, Ui.YELLOW);
			// the middle label only when it names a step of its own (a 1–2x range has none)
			else if (p == 1 || p == 15 || p == 8 && lev != lo() && lev != hi()) Ui.textCentered(g, font, lev + "x", x + bw / 2, y + 30, Ui.TEXT);
		}
	}
}
