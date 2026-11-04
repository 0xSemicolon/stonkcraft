package dev.hypercraft.client.screen;

import com.google.gson.JsonObject;
import dev.hypercraft.client.ClientConfig;
import dev.hypercraft.client.ClientNet;
import dev.hypercraft.client.ClientState;
import dev.hypercraft.client.ui.HcButton;
import dev.hypercraft.client.ui.HcScreen;
import dev.hypercraft.client.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/** Shown once per player, on the first world joined: paper money only, and three steps to a first trade. */
public class WelcomeScreen extends HcScreen {
	private static final String[][] STEPS = {
			{"1", "Open an account", "Press H for the build menu: craft an Account Vault there from iron, gold, a chest and redstone, place it and open an account."},
			{"2", "Fund it", "Money is earned: craft a UNIT.XYZ and bridge gold, iron, diamonds, coal and more into the account at live prices."},
			{"3", "Trade", "Craft a Trading Terminal and right-click it to trade. Then build bots: a card links a Market Sensor and a "
					+ "Trade Executor to your account, and redstone wires them."},
	};
	private final List<int[]> stepY = new ArrayList<>();
	private int blocksY, stepCol;

	public WelcomeScreen() {
		super("Welcome to Stonkcraft");
	}

	@Override
	protected int panelWidth() {
		return 330;
	}

	@Override
	protected void layout(Flow f) {
		f.text("Everything here is paper trading. Prices are live from Hyperliquid and trade.xyz, but no order reaches an exchange, "
				+ "the mod holds no keys, and no money is real. Not financial advice. Not affiliated with Hyperliquid, trade.xyz or the NYSE.", Ui.YELLOW);
		f.gap(2);
		stepY.clear();
		// the step titles get a column as wide as the longest one
		stepCol = 0;
		for (String[] s : STEPS) stepCol = Math.max(stepCol, font.width(s[1]) + 24);
		for (String[] s : STEPS) {
			int y = f.y;
			f.text(s[2], Ui.DIM, f.left + stepCol, f.width - stepCol);
			stepY.add(new int[]{y});
		}
		blocksY = f.reserve(18);
	}

	@Override
	protected void footer(Footer f) {
		if (ClientState.canBuild) {
			f.left("Get starter kit", HcButton.Style.NORMAL, () -> {
				ClientNet.send("starter_kit", new JsonObject());
				toast("Vault, sensor, executor, 4 cards, a lever and redstone: in your inventory", Ui.GREEN);
				chime();
			}).tip("Everything for steps 1 to 3 in one go");
		}
		f.primary("Let's go", this::onClose).tip("H opens the build menu anywhere. Recipes are in your recipe book too");
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		for (int i = 0; i < STEPS.length; i++) {
			int y = stepY.get(i)[0];
			Ui.text(g, font, STEPS[i][0], PAD + 2, y, Ui.ACCENT);
			Ui.text(g, font, STEPS[i][1], PAD + 14, y, Ui.TEXT);
		}
		// the blocks you'll meet, bobbing gently
		String[] ids = {"account_vault", "market_sensor", "trade_executor", "ticker_display", "signal_mixer", "copy_desk", "kill_switch"};
		long t = System.currentTimeMillis();
		int w = ids.length * 22, x0 = (pw - w) / 2;
		for (int i = 0; i < ids.length; i++) {
			var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(dev.hypercraft.HyperCraft.id(ids[i]));
			int bob = (int) Math.round(Math.sin((t / 400.0) + i * 0.9) * 1.5);
			g.renderItem(new net.minecraft.world.item.ItemStack(item), x0 + i * 22 + 3, blocksY + 1 + bob);
			// hover names each block
			if (Ui.inside(mx, my, x0 + i * 22, blocksY, 22, 18)) setTooltipForNextRenderPass(item.getDescription());
		}
	}

	@Override
	public void onClose() {
		ClientConfig.get().welcomed = true;
		ClientConfig.save();
		super.onClose();
	}
}
