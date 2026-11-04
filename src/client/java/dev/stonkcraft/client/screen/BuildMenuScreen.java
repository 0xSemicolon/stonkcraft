package dev.stonkcraft.client.screen;

import com.google.gson.JsonObject;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.client.ClientNet;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.StonkcraftClient;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcScreen;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.item.BlockCrafting;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * The H key: make Stonkcraft's blocks anywhere. Survival players craft from what they carry; creative players (and servers
 * with {@code buildMenu} on) get them free. No trading here: that happens at a Trading Terminal, which this makes.
 */
public class BuildMenuScreen extends HcScreen {
	private static final int ROW_H = 18;

	record BuildItem(String id, String name, String desc) {}

	static final BuildItem[] BUILD_ITEMS = {
			new BuildItem("account_vault", "Account Vault", "Holds an account. Deposit and withdraw coins."),
			new BuildItem("unit_bridge", "UNIT[XYZ]", "Bridge ores, gems and fuel in, and back out."),
			new BuildItem("signal_mixer", "Signal Mixer", "Combine accounts and signals: blend, fade, filter, gate..."),
			new BuildItem("copy_desk", "Copy Desk", "Copy a trader, an account or a signal into your account."),
			new BuildItem("trading_terminal", "Trading Terminal", "Right-click to open this terminal."),
			new BuildItem("ticker_display", "Ticker Display", "Glowing live price board."),
			new BuildItem("account_display", "Account Display", "Equity, PnL, margin... of a card's account."),
			new BuildItem("market_sensor", "Market Sensor", "Redstone signal from market conditions."),
			new BuildItem("trade_executor", "Trade Executor", "Any order on a pulse; faces can steer size, leverage..."),
			new BuildItem("leverage_dial", "Leverage Dial", "Signal strength sets leverage."),
			new BuildItem("kill_switch", "Kill Switch", "Pulse to cancel orders or close everything."),
			new BuildItem("account_card", "Blank Cards x4", "Print Trade or Watch Cards on them at a vault."),
	};

	/** Where the recipe pane sits, panel-relative; it stays put while the list scrolls. */
	private int paneX, paneY, paneW;
	private BuildItem selected = BUILD_ITEMS[0];
	private long seenResult = ClientState.lastVaultAt;

	public BuildMenuScreen() {
		super("Build");
	}

	@Override
	protected int panelWidth() {
		return 340;
	}

	@Override
	protected String status() {
		return ClientState.canBuild ? "Free in this mode. Trade at a Trading Terminal" : "Craft from what you carry. Trade at a Trading Terminal";
	}

	private List<Ingredient> ingredients(String id) {
		if (minecraft.level == null) return List.of();
		var holder = minecraft.level.getRecipeManager().byKey(Stonkcraft.id(id)).orElse(null);
		return holder == null ? List.of() : holder.value().getIngredients();
	}

	private int missing(String id) {
		return minecraft.player == null ? 0 : BlockCrafting.missing(minecraft.player.getInventory(), ingredients(id)).size();
	}

	@Override
	protected void layout(Flow f) {
		int listW = Math.min(170, f.width * 11 / 20);
		paneX = f.left + listW + 8;
		paneY = f.y;
		paneW = f.width - listW - 8;
		for (BuildItem item : BUILD_ITEMS) {
			int y = f.reserve(ROW_H - 4);
			int miss = missing(item.id());
			String right = ClientState.canBuild ? "" : miss == 0 ? "✔" : miss + " short";
			f.add(new HcButton(f.left, y, listW, ROW_H - 2, item.name(), HcButton.Style.TAB, b -> {
				selected = item;
				relayout();
			}).selected(selected == item).cell(new ItemStack(BuiltInRegistries.ITEM.get(Stonkcraft.id(item.id()))), right).tip(item.desc()));
		}
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		int y = paneY + scrollOffset();
		drawRecipe(g, font, minecraft, selected.id(), paneX, y, paneW);
		int ty = y + 90;
		if (ClientState.canBuild) return;
		List<Ingredient> gaps = minecraft.player == null ? List.of() : BlockCrafting.missing(minecraft.player.getInventory(), ingredients(selected.id()));
		if (gaps.isEmpty()) {
			Ui.text(g, font, Ui.ellipsize(font, "You have everything", paneW), paneX, ty, Ui.GREEN);
			return;
		}
		Ui.text(g, font, "You still need:", paneX, ty, Ui.DIM);
		long tick = System.currentTimeMillis() / 1000;
		java.util.Map<net.minecraft.world.item.Item, int[]> counts = new java.util.LinkedHashMap<>();
		java.util.Map<net.minecraft.world.item.Item, ItemStack[]> shown = new java.util.HashMap<>();
		for (Ingredient ing : gaps) {
			ItemStack[] options = ing.getItems();
			if (options.length == 0) continue;
			counts.computeIfAbsent(options[0].getItem(), k -> new int[1])[0]++;
			shown.put(options[0].getItem(), options);
		}
		int ix = paneX, iy = ty + 11;
		for (var e : counts.entrySet()) {
			if (ix + 16 > paneX + paneW) {
				ix = paneX;
				iy += 18;
			}
			ItemStack[] options = shown.get(e.getKey());
			ItemStack stack = options[(int) (tick % options.length)].copyWithCount(e.getValue()[0]);
			g.renderItem(stack, ix, iy);
			g.renderItemDecorations(font, stack, ix, iy);
			ix += 18;
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (ClientState.lastVaultAt != seenResult) {
			seenResult = ClientState.lastVaultAt;
			toast(ClientState.lastVaultMsg, ClientState.lastVaultOk ? Ui.GREEN : Ui.RED);
			relayout();
		}
	}

	/** A crafting grid for one of Stonkcraft's recipes, read from the recipes the server sent (so a datapack's change shows). */
	static void drawRecipe(GuiGraphics g, net.minecraft.client.gui.Font font, net.minecraft.client.Minecraft minecraft, String id, int x, int y, int w) {
		if (minecraft.level == null) return;
		var holder = minecraft.level.getRecipeManager().byKey(Stonkcraft.id(id)).orElse(null);
		if (holder == null) return;
		var recipe = holder.value();
		int cols = recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe s ? s.getWidth() : 3;
		var ings = recipe.getIngredients();
		int ph = 3 * 18 + 30;
		g.pose().pushPose();
		g.pose().translate(0, 0, 300);
		g.fill(x, y, x + w, y + ph, 0xF5081214);
		Ui.outline(g, x, y, w, ph, Ui.ACCENT);
		ItemStack out = recipe.getResultItem(minecraft.level.registryAccess());
		Ui.text(g, font, Ui.ellipsize(font, "Craft: " + out.getHoverName().getString() + (out.getCount() > 1 ? " ×" + out.getCount() : ""), w - 12), x + 6, y + 5, Ui.ACCENT);
		int gx = x + 8, gy = y + 18;
		long tick = System.currentTimeMillis() / 1000;
		for (int i = 0; i < 9; i++) {
			int sx = gx + (i % 3) * 18, sy = gy + (i / 3) * 18;
			g.fill(sx, sy, sx + 17, sy + 17, 0xFF1A2A2C);
			int col = i % 3, row = i / 3, idx = row * cols + col;
			if (col >= cols || idx >= ings.size()) continue;
			ItemStack[] options = ings.get(idx).getItems();
			// a tag ingredient cycles through what fits
			if (options.length > 0) g.renderItem(options[(int) (tick % options.length)], sx, sy);
		}
		int rx = gx + 3 * 18 + 14;
		Ui.text(g, font, "→", rx, gy + 22, Ui.DIM);
		g.renderItem(out, rx + 14, gy + 18);
		g.renderItemDecorations(font, out, rx + 14, gy + 18);
		g.pose().popPose();
	}

	private void make() {
		JsonObject o = new JsonObject();
		o.addProperty("item", selected.id());
		ClientNet.send(ClientState.canBuild ? "give" : "craft", o);
		chime();
		// a free block sends no answer: the count on the row is all there is to change
		if (ClientState.canBuild) toast(selected.name() + " in your inventory", Ui.GREEN);
	}

	@Override
	protected void footer(Footer f) {
		f.right("Close", HcButton.Style.NORMAL, this::onClose);
		// one click for a first bot, for those who get blocks free: vault, sensor, executor, cards, a lever and dust
		if (ClientState.canBuild) f.right("★ Starter kit", HcButton.Style.NORMAL, () -> ClientNet.send("starter_kit", new JsonObject()));
		HcButton b = f.primary((ClientState.canBuild ? "Get " : "Craft ") + selected.name(), this::make);
		if (!ClientState.canBuild && missing(selected.id()) > 0) b.active = false;
	}

	@Override
	public boolean keyPressed(int key, int scan, int mods) {
		if (StonkcraftClient.openBuild.matches(key, scan)) {
			onClose();
			return true;
		}
		return super.keyPressed(key, scan, mods);
	}
}
