package dev.stonkcraft.client.screen;

import com.google.gson.JsonObject;
import dev.stonkcraft.client.ClientNet;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcScreen;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.item.Coins;
import dev.stonkcraft.trading.Fmt;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * How money gets into a paper account: bridge goods at a UNIT[XYZ], or deposit coins withdrawn from an account (and
 * the paper mint, where a server turns it on). Watch accounts mirror an address and can't be funded.
 */
public class FundScreen extends HcScreen {
	private final String id;
	@Nullable
	private final Screen parent;
	private long openedAt, seenResult, lastCarried = -1;
	private int carriedY;
	private boolean hadAccount;

	public FundScreen(String id, @Nullable Screen parent) {
		super("Fund account");
		this.id = id;
		this.parent = parent;
	}

	private JsonObject acct() {
		return ClientState.account(id);
	}

	private boolean paper() {
		JsonObject a = acct();
		return a == null || a.get("label").getAsString().equals("PAPER");
	}

	/** Dollar value of the coins in the player's inventory that this account accepts. */
	private long carried() {
		JsonObject a = acct();
		if (a == null || !a.has("backing") || minecraft == null || minecraft.player == null) return 0;
		String backing = a.get("backing").getAsString();
		var inv = minecraft.player.getInventory();
		long usd = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) usd += Coins.value(inv.getItem(i), backing);
		return usd;
	}

	@Override
	protected int panelWidth() {
		return 320;
	}

	@Override
	protected String titleText() {
		JsonObject a = acct();
		return "FUND ACCOUNT";
	}

	@Override
	protected int accent() {
		JsonObject a = acct();
		return a == null ? Ui.ACCENT : Ui.tagColor(Ui.tag(a));
	}

	@Override
	protected String headerRight() {
		JsonObject a = acct();
		return a == null ? null : "◈ " + a.get("name").getAsString() + "  " + Ui.tag(a);
	}

	@Override
	protected int headerRightColor() {
		return accent();
	}

	@Override
	protected String status() {
		JsonObject a = acct();
		if (a == null) return "Loading account…";
		return a.has("equity") ? "In the account: " + Fmt.usd(a.get("equity").getAsDouble()) : "";
	}

	@Override
	protected void init() {
		if (openedAt == 0) {
			openedAt = System.currentTimeMillis();
			seenResult = ClientState.lastVaultAt;
		}
		super.init();
	}

	@Override
	protected void layout(Flow f) {
		JsonObject a = acct();
		hadAccount = a != null;
		if (a == null) return;
		if (!paper()) {
			f.heading("Watch-only");
			f.text("This account mirrors " + shorten(a.get("address").getAsString()) + ", read-only. It can't hold coins; "
					+ "use its card in sensors, displays, signals and copy desks.", Ui.DIM);
			return;
		}
		// money is earned: goods in at a UNIT[XYZ] is the way in, coins only move money that's already there
		f.heading("1 · Bridge goods at a UNIT[XYZ]");
		f.text("Gold, copper, iron, diamonds, emeralds, redstone, quartz, glowstone, coal, lava and blaze rods are money: right-click a UNIT[XYZ] "
				+ "holding this account's Trade Card with them, or click them on its screen, to deposit them at a live price.", Ui.TEXT);
		f.rule();
		f.heading("2 · Coins you carry");
		carriedY = f.reserve(14);
		f.add(new HcButton(pw - PAD - 90, carriedY - 2, 90, ROW, "Deposit all", HcButton.Style.PRIMARY, b -> deposit()))
				.tip("Moves every paper coin in your inventory into this account");
		f.text("Coins are money withdrawn from an account: pay a friend, or hopper them into another account's vault.", Ui.DIM);
		if (ClientState.faucet) {
			f.rule();
			f.heading("3 · The paper mint");
			f.text("This server prints paper money for free. Print some, then deposit it.", Ui.TEXT);
			long max = (long) ClientState.faucetUsd;
			int y = f.row(null, ROW);
			int bx = f.left;
			long both = Math.min(10_000, max);
			HcButton md = f.add(new HcButton(bx, y, 104, ROW, "Mint + deposit " + (both >= 1000 ? "$" + both / 1000 + "K" : "$" + both), b -> {
				mint(both);
				deposit();
			}));
			JsonObject acc = acct();
			double inAcct = acc != null && acc.has("equity") ? acc.get("equity").getAsDouble() : 0;
			md.tip("Prints " + Fmt.usd(both) + " and deposits it with your coins: the account would hold about " + Fmt.usd(inAcct + both + carried()));
			bx += 108;
			for (long usd : new long[]{1_000, 10_000, 100_000}) {
				if (usd > max) continue;
				f.add(new HcButton(bx, y, 58, ROW, "Mint $" + usd / 1000 + "K", b -> mint(usd))).tip("Prints coins into your inventory: you'd carry " + Fmt.usd(carried() + usd));
				bx += 62;
			}
		}
	}

	@Override
	protected void footer(Footer f) {
		f.primary("Done", this::onClose);
	}

	private void deposit() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		ClientNet.send("deposit_coins", o);
	}

	private void mint(long usd) {
		JsonObject o = new JsonObject();
		o.addProperty("usd", usd);
		ClientNet.send("paper_mint", o);
	}

	@Override
	public void tick() {
		super.tick();
		// the mint tips quote what you'd carry, so they follow the coins in your inventory
		long c = carried();
		if ((acct() != null) != hadAccount || c != lastCarried) {
			lastCarried = c;
			relayout();
		}
		if (ClientState.lastVaultAt > seenResult) {
			seenResult = ClientState.lastVaultAt;
			toast(ClientState.lastVaultMsg, ClientState.lastVaultOk ? Ui.GREEN : Ui.RED);
			if (ClientState.lastVaultOk) chime();
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		if (acct() == null || !paper()) return;
		long carried = carried();
		String amt = Fmt.usd(carried);
		Ui.textScaled(g, font, amt, PAD, carriedY, 1.25f, carried > 0 ? Ui.GREEN : Ui.DIM);
		Ui.text(g, font, "in paper coins", PAD + 4 + (int) (font.width(amt) * 1.25f), carriedY + 3, Ui.DIM);
	}

	private static String shorten(String addr) {
		return addr.length() > 12 ? addr.substring(0, 6) + "…" + addr.substring(addr.length() - 4) : addr;
	}
}
