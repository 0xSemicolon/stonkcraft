package dev.stonkcraft.client.screen;

import com.google.gson.JsonObject;
import dev.stonkcraft.client.ClientNet;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcField;
import dev.stonkcraft.client.ui.HcScreen;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.trading.Fmt;
import net.minecraft.client.gui.GuiGraphics;

/** An account's vault: balances, withdraw to coins, cards and sharing, rename, close, jump to the terminal. */
public class VaultScreen extends HcScreen {
	private static final long[] QUICK = {100, 1_000, 10_000};

	private final long pos;
	private final String id;
	private final boolean owner;
	private HcField amount;
	private HcField rename;
	private int statsY, sparkY, afterY = -1;
	private long seenResult, closeArmedAt;
	private boolean hadAccount;

	public VaultScreen(JsonObject data) {
		super("Account Vault");
		this.pos = data.get("pos").getAsLong();
		this.id = data.get("id").getAsString();
		this.owner = data.get("owner").getAsBoolean();
		this.seenResult = ClientState.lastVaultAt;
	}

	private JsonObject acct() {
		return ClientState.account(id);
	}

	/** A read-only view of someone's address: no coins in or out. */
	private boolean watch() {
		JsonObject a = acct();
		return a != null && a.has("watch");
	}

	@Override
	protected int panelWidth() {
		return 300;
	}

	@Override
	protected String titleText() {
		JsonObject a = acct();
		return a == null ? "ACCOUNT VAULT" : a.get("name").getAsString().toUpperCase();
	}

	@Override
	protected int accent() {
		JsonObject a = acct();
		return a == null ? Ui.ACCENT : Ui.tagColor(Ui.tag(a));
	}

	@Override
	protected String headerRight() {
		JsonObject a = acct();
		return a == null ? null : Ui.tag(a);
	}

	@Override
	protected int headerRightColor() {
		return accent();
	}

	@Override
	protected String status() {
		JsonObject a = acct();
		if (a == null) return "Loading account…";
		if (!a.get("available").getAsBoolean()) return "This account isn't available right now";
		StringBuilder sb = new StringBuilder();
		if (!a.get("owned").getAsBoolean()) sb.append(a.has("grant") ? a.get("grant").getAsString() : "via card").append(" · owner ").append(a.get("ownerName").getAsString());
		if (a.has("frozen") && a.get("frozen").getAsBoolean()) sb.insert(0, "FROZEN · ");
		if (sb.isEmpty()) sb.append(a.get("positions").getAsInt()).append(" open position").append(a.get("positions").getAsInt() == 1 ? "" : "s");
		return sb.toString();
	}

	@Override
	protected int statusColor() {
		JsonObject a = acct();
		return a != null && a.has("frozen") && a.get("frozen").getAsBoolean() ? 0xFF7FD3FF : Ui.DIM;
	}

	@Override
	protected void layout(Flow f) {
		JsonObject a = acct();
		hadAccount = a != null;
		if (a == null) return; // nothing to press until the account arrives
		statsY = f.reserve(24);
		sparkY = f.reserve(14);
		afterY = -1;
		if (watch()) {
			f.heading("Watch-only");
			f.text(a.get("address").getAsString(), Ui.ACCENT);
			f.text("Read-only: this vault follows a public account's address, with no keys. Print cards and put them in Market Sensors "
					+ "(account modes) and Account Displays to wire its PnL, equity and margin to redstone. Executors can't trade with it.", 0xFF4E6E6A);
		} else {
			f.heading("Withdraw & deposit");
			int y = f.row(null, 14);
			amount = f.add(field(f.left, y, 84, "Amount", "100"));
			amount.numeric(100).suffix("USD").validate(s -> {
				double v = amount.num(0);
				return v <= 0 ? "Enter an amount" : a.has("withdrawable") && v > a.get("withdrawable").getAsDouble() + 0.5 ? "More than the free balance" : null;
			});
			int qx = f.left + 90;
			for (long q : QUICK) {
				String label = q >= 1000 ? "$" + q / 1000 + "K" : "$" + q;
				f.add(new HcButton(qx, y, 36, 14, label, b -> amount.setValue(String.valueOf(q)))).tip("Fills in " + Fmt.usd(q));
				qx += 40;
			}
			f.add(new HcButton(qx, y, 36, 14, "Max", b -> {
				JsonObject acc = acct();
				if (acc != null && acc.has("withdrawable")) amount.setValue(String.valueOf((long) Math.floor(acc.get("withdrawable").getAsDouble())));
			}).tip("Everything not tied up as margin"));
			afterY = f.reserve(9);
			int half = (f.width - 6) / 2;
			y = f.row(null, ROW);
			f.add(new HcButton(f.left, y, half, ROW, "Withdraw as coins", b -> {
				if (amount.error() != null) return;
				JsonObject o = new JsonObject();
				o.addProperty("id", id);
				o.addProperty("pos", pos);
				o.addProperty("usd", (long) amount.num(0));
				ClientNet.send("vault_withdraw", o);
			})).tip("Paper coins appear in your inventory");
			f.add(new HcButton(f.left + half + 6, y, half, ROW, "Deposit all my coins", b -> {
				JsonObject o = new JsonObject();
				o.addProperty("id", id);
				ClientNet.send("deposit_coins", o);
			})).tip("Moves every paper coin you carry into this account");
			y = f.row(null, ROW);
			f.add(new HcButton(f.left, y, f.width, ROW, "+ Fund…", b -> minecraft.setScreen(new FundScreen(id, this)))).tip("Mint free paper money, or bridge mined metal");
		}
		f.rule();
		f.heading("Cards & sharing");
		f.text(owner ? "A card lets a block, or a friend holding it, use this account. Print one here, or right-click the vault with a blank card."
				: "Ask the owner for a card to use this account in blocks.", 0xFF4E6E6A);
		if (owner) {
			int half = (f.width - 6) / 2;
			int y = f.row(null, ROW);
			f.add(new HcButton(f.left, y, half, ROW, "Print card", b -> {
				JsonObject o = new JsonObject();
				o.addProperty("id", id);
				ClientNet.send("vault_card", o);
			})).tip("A Trade Card for this account goes into your inventory. Watch Cards: Access…");
			f.add(new HcButton(f.left + half + 6, y, half, ROW, "Access…", b -> minecraft.setScreen(new AccessScreen(id, this))))
					.tip("Who can use this account, and its cards");
			f.rule();
			f.heading("Account");
			y = f.row(null, 14);
			rename = f.add(field(f.left, y, 150, "Name", a.get("name").getAsString()));
			rename.setMaxLength(24);
			f.add(new HcButton(f.left + 156, y, 56, 14, "Rename", b -> {
				JsonObject o = new JsonObject();
				o.addProperty("id", id);
				o.addProperty("name", rename.getValue());
				ClientNet.send("vault_rename", o);
			}));
			f.add(new HcButton(f.left + 218, y, f.width - 218, 14, "Delete", HcButton.Style.DANGER, b -> {
				// two clicks: the first arms it
				if (System.currentTimeMillis() - closeArmedAt > 4000) {
					closeArmedAt = System.currentTimeMillis();
					b.label("Sure?");
					toast("Click Delete again to delete this account (it must be empty)", Ui.YELLOW);
					return;
				}
				JsonObject o = new JsonObject();
				o.addProperty("id", id);
				o.addProperty("pos", pos);
				ClientNet.send("vault_close", o);
			})).tip("Deletes this account. Only empty accounts can be closed");
		}
	}

	@Override
	protected void footer(Footer f) {
		f.right("Done", HcButton.Style.NORMAL, this::onClose);
		HcButton go = f.primary("Use at terminals", () -> {
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			ClientNet.send("select_account", o);
			toast("✔ Trading Terminals now open on this account", Ui.GREEN);
		});
		go.active = acct() != null;
		go.tip("Makes this the account a Trading Terminal opens on. Trade at a Trading Terminal block");
	}

	@Override
	public void tick() {
		super.tick();
		if ((acct() != null) != hadAccount) relayout();
		if (ClientState.lastVaultAt > seenResult) {
			seenResult = ClientState.lastVaultAt;
			toast(ClientState.lastVaultMsg, ClientState.lastVaultOk ? Ui.GREEN : Ui.RED);
			if (ClientState.lastVaultOk) chime();
		}
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		JsonObject a = acct();
		if (a == null || !a.get("available").getAsBoolean()) return;
		double eq = a.get("equity").getAsDouble();
		double free = a.get("withdrawable").getAsDouble();
		double upnl = a.get("upnl").getAsDouble();
		int cols = (pw - 2 * PAD) / 3;
		stat(g, "Equity", Fmt.usd(eq), Ui.TEXT, PAD, statsY);
		stat(g, "Free", Fmt.usd(free), Ui.ACCENT, PAD + cols, statsY);
		stat(g, "uPnL", Fmt.usdSigned(upnl), Ui.signColor(upnl), PAD + cols * 2, statsY);
		// equity over time, when the terminal is following this account
		JsonObject pf = ClientState.portfolio;
		if (pf != null && pf.has("accountId") && pf.get("accountId").getAsString().equals(id) && pf.has("equityHistory") && pf.getAsJsonArray("equityHistory").size() > 1) {
			var hist = pf.getAsJsonArray("equityHistory");
			double[] d = new double[hist.size()];
			for (int i = 0; i < d.length; i++) d[i] = hist.get(i).getAsDouble();
			Ui.sparkline(g, d, PAD, sparkY, pw - 2 * PAD, 12, Ui.signColor(d[d.length - 1] - d[0]) | 0xFF000000);
		} else {
			Ui.text(g, font, "Equity chart: open this account in the terminal", PAD, sparkY + 2, 0xFF4E6E6A);
		}
		// what the typed amount leaves behind
		if (afterY >= 0 && amount != null && amount.error() == null && amount.num(0) > 0) {
			Ui.text(g, font, Ui.ellipsize(font, "Withdrawing " + Fmt.usd(amount.num(0)) + " leaves " + Fmt.usd(free - amount.num(0)) + " free to trade", pw - 2 * PAD),
					PAD, afterY, 0xFF4E6E6A);
		}
	}

	private void stat(GuiGraphics g, String label, String value, int color, int x, int y) {
		Ui.text(g, font, label, x, y, Ui.DIM);
		int col = (pw - 2 * PAD) / 3 - 4;
		Ui.textScaled(g, font, value, x, y + 11, Math.min(1.25f, (float) col / Math.max(1, font.width(value))), color);
	}
}
