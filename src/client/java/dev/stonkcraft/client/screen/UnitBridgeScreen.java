package dev.stonkcraft.client.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stonkcraft.client.ClientNet;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcScreen;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.trading.Fmt;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * UNIT[XYZ], two tabs over one grid of goods. Deposit: lit goods are what you carry, and clicking one bridges it in, as a
 * spot balance or a 1x long. Withdraw: lit goods are what the account holds here, and clicking one picks it to take out.
 */
public class UnitBridgeScreen extends HcScreen {
	/** Last info from the server; refreshed every second while open. */
	public static JsonObject info = new JsonObject();
	private final long pos;
	private long lastAsk, seenResult;
	private final List<HcButton> cells = new ArrayList<>();
	private String builtFor = "";
	private boolean withdrawTab;
	/** The Withdraw tab's choice: which good, and which holding of it. */
	private String chosen = "";
	private String chosenFrom = "SPOT";
	/** Where the Withdraw tab's line about the chosen good goes; -1 when there isn't one. */
	private int detailY = -1;

	public UnitBridgeScreen(JsonObject data) {
		super("UNIT[XYZ]");
		info = data;
		this.pos = data.get("pos").getAsLong();
		this.seenResult = ClientState.lastVaultAt;
	}

	private List<JsonObject> tokens() {
		List<JsonObject> out = new ArrayList<>();
		if (info.has("tokens")) for (JsonElement el : info.getAsJsonArray("tokens")) out.add(el.getAsJsonObject());
		return out;
	}

	private String mode() {
		return info.has("mode") ? info.get("mode").getAsString() : "SPOT";
	}

	/** What changes the screen's shape: tokens, whether they have balances, withdraw rights. */
	private String shape() {
		StringBuilder sb = new StringBuilder(mode()).append(withdrawTab).append(chosen).append(chosenFrom).append(info.has("canWithdraw") && info.get("canWithdraw").getAsBoolean());
		// whether each withdraw row has anything decides its buttons
		for (JsonObject t : tokens()) sb.append(t.get("token").getAsString()).append(t.has("spot"))
				.append(t.has("spotOut") && t.get("spotOut").getAsDouble() > 0).append(t.has("longOut") && t.get("longOut").getAsDouble() > 0);
		return sb.toString();
	}

	@Override
	protected int panelWidth() {
		return 340;
	}

	@Override
	protected int accent() {
		return Ui.YELLOW;
	}

	@Override
	protected String titleText() {
		return "UNIT[XYZ]  goods ⇄ account";
	}

	@Override
	protected String headerRight() {
		return info.has("account") ? "◈ " + info.get("account").getAsString() : "no account";
	}

	@Override
	protected int headerRightColor() {
		return info.has("account") ? Ui.ACCENT : Ui.RED;
	}

	@Override
	protected String status() {
		if (!info.has("account")) return "No account: insert a card, or select one at a Trading Terminal";
		// what it's doing: where deposits go, and what the account holds here (the rows below give the amounts)
		StringBuilder held = new StringBuilder();
		double usd = 0;
		for (JsonObject t : tokens()) {
			if (!t.has("spotOut")) continue;
			double n = t.get("spotOut").getAsDouble() + t.get("longOut").getAsDouble();
			if (n <= 0) continue;
			String name = t.get("token").getAsString();
			held.append(held.isEmpty() ? "" : ", ").append(name.startsWith("U") ? name.substring(1).toLowerCase(java.util.Locale.ROOT) : name);
			usd += n * Math.max(0, t.get("price").getAsDouble());
		}
		return (info.has("viaCard") && info.get("viaCard").getAsBoolean() ? "Card account" : "Your selected account")
				+ " · " + (held.isEmpty() ? "nothing bridged here yet" : "holds " + Fmt.usd(usd) + ": " + held);
	}

	@Override
	protected int statusColor() {
		return info.has("account") ? Ui.TEXT : Ui.RED;
	}

	/** What comes out of a holding as goods: the fee's taken on top of what's delivered. */
	private static double takeable(double held) {
		double fee = info.has("fee") ? info.get("fee").getAsInt() / 10_000.0 : 0;
		return held / (1 + fee);
	}

	/** What the account holds of a good, across its spot balance and long. */
	private static double held(JsonObject t) {
		return t.has("spotOut") ? t.get("spotOut").getAsDouble() + t.get("longOut").getAsDouble() : 0;
	}

	@Override
	protected void layout(Flow f) {
		builtFor = shape();
		List<JsonObject> toks = tokens();
		int kinds = 0;
		for (JsonObject t : toks) if (held(t) > 0) kinds++;
		int y = f.row(null, ROW);
		int tw = (f.width - 4) / 2;
		f.add(new HcButton(f.left, y, tw, ROW, "↓ Deposit", HcButton.Style.TAB, b -> setTab(false)).selected(!withdrawTab).color(Ui.YELLOW)
				.tip("Goods you carry, into the account"));
		f.add(new HcButton(f.left + tw + 4, y, tw, ROW, "↑ Withdraw" + (kinds > 0 ? " (" + kinds + ")" : ""), HcButton.Style.TAB, b -> setTab(true)).selected(withdrawTab).color(Ui.YELLOW)
				.tip("What the account holds here, back out as items"));
		detailY = -1;
		if (withdrawTab) layoutWithdraw(f, toks);
		else layoutDeposit(f, toks);
	}

	private void layoutDeposit(Flow f, List<JsonObject> toks) {
		int fee = info.has("fee") ? info.get("fee").getAsInt() : 0;
		f.text("Click what you carry to deposit it all, or right-click the block with it (sneak: everything). Hoppers feed the card's account. Fee "
				+ String.format("%.2f%%", fee / 100.0) + ".", 0xFF4E6E6A);
		int y = f.row("Deposits go", ROW);
		boolean lng = "LONG".equals(mode());
		f.add(new HcButton(f.left + 74, y, 90, ROW, "Spot balance", HcButton.Style.TAB, b -> setMode("SPOT")).selected(!lng).color(Ui.YELLOW)
				.tip("Held as a spot balance you can withdraw as items any time"));
		f.add(new HcButton(f.left + 168, y, 90, ROW, "Held as 1x long", HcButton.Style.TAB, b -> setMode("LONG")).selected(lng).color(Ui.YELLOW)
				.tip("Held as a 1x long perp on its market: same exposure, and it earns or pays funding"));
		f.heading("Takes · " + toks.size() + " goods, at live prices");
		grid(f, toks);
	}

	private void layoutWithdraw(Flow f, List<JsonObject> toks) {
		boolean canWithdraw = info.has("canWithdraw") && info.get("canWithdraw").getAsBoolean();
		int fee = info.has("fee") ? info.get("fee").getAsInt() : 0;
		if (!info.has("account")) {
			f.text("No account here: insert a card, or select one at a Trading Terminal.", Ui.RED);
			return;
		}
		// the choice stays while it's there to take; otherwise the first thing that is
		JsonObject pick = null;
		for (JsonObject t : toks) if (t.get("token").getAsString().equals(chosen) && held(t) > 0) pick = t;
		if (pick == null) for (JsonObject t : toks) {
			if (held(t) > 0) {
				pick = t;
				break;
			}
		}
		chosen = pick == null ? "" : pick.get("token").getAsString();
		if (pick == null) {
			f.text("Nothing held here yet. What you deposit shows up here, ready to take back out. You can also buy them in a terminal's Spot tab.", 0xFF4E6E6A);
			grid(f, toks);
			return;
		}
		double spot = pick.get("spotOut").getAsDouble(), lng = pick.get("longOut").getAsDouble();
		if (lng <= 0) chosenFrom = "SPOT";
		else if (spot <= 0) chosenFrom = "LONG";
		String unit = pick.get("unit").getAsString();
		if (spot > 0 && lng > 0) {
			int y = f.row("From", ROW);
			f.add(new HcButton(f.left + 74, y, 90, ROW, "Spot " + Fmt.size(spot), HcButton.Style.TAB, b -> setFrom("SPOT")).selected(chosenFrom.equals("SPOT")).color(Ui.YELLOW)
					.tip("Your spot balance of it"));
			f.add(new HcButton(f.left + 168, y, 90, ROW, "Long " + Fmt.size(lng), HcButton.Style.TAB, b -> setFrom("LONG")).selected(chosenFrom.equals("LONG")).color(Ui.YELLOW)
					.tip("Out of the long: closes the same share of it"));
		}
		detailY = f.reserve(10);
		String token = pick.get("token").getAsString();
		String from = chosenFrom;
		double avail = from.equals("SPOT") ? spot : lng, px0 = pick.get("price").getAsDouble();
		int y = f.row("Take out", 16);
		int bw = (f.width - 74 - 3 * 4) / 4;
		int x = f.left + 74;
		for (double q : new double[]{1, 9, 64, -1}) {
			String label = q < 0 ? "All" : String.valueOf((int) q);
			double units = q < 0 ? 1e12 : q;
			double n = q < 0 ? takeable(avail) : q;
			HcButton b = f.add(new HcButton(x, y, bw, 16, label, q < 0 ? HcButton.Style.PRIMARY : HcButton.Style.NORMAL, btn -> withdraw(token, from, units)));
			b.active = canWithdraw && (q < 0 || q <= takeable(avail) + 1e-9);
			b.tip(!canWithdraw ? "Only the account's owner can withdraw"
					: "Withdraw " + Fmt.size(n) + " " + unit + " as items" + (px0 > 0 ? " (≈ " + Fmt.usd(n * px0) + ")" : ""));
			x += bw + 4;
		}
		if (!canWithdraw) f.text("Only the account's owner can withdraw.", Ui.DIM);
		f.heading("Available · comes out as items, " + String.format("%.2f%%", fee / 100.0) + " fee");
		grid(f, toks);
	}

	/** An amount for a cell or a line: two decimals, rounded down, so it never promises more than comes out. */
	private static String amount(double n) {
		return n >= 1000 ? Fmt.size(Math.floor(n)) : Fmt.size(Math.floor(n * 100) / 100);
	}

	/** The goods, two columns of cells: the same grid on both tabs. */
	private void grid(Flow f, List<JsonObject> toks) {
		cells.clear();
		int cw = (f.width - 4) / 2;
		for (int i = 0; i < toks.size(); i += 2) {
			int ry = f.row(null, 18);
			for (int k = i; k < Math.min(i + 2, toks.size()); k++) {
				JsonObject t = toks.get(k);
				HcButton cell = f.add(new HcButton(f.left + (k - i) * (cw + 4), ry, cw, 18, display(t), b -> {
					if (withdrawTab) choose(t);
					else deposit(t);
				})).color(Ui.YELLOW);
				cell.cell(icon(t), "");
				cells.add(cell);
			}
		}
		refreshCells();
	}

	private void setTab(boolean withdraw) {
		withdrawTab = withdraw;
		relayout();
	}

	private void setFrom(String from) {
		chosenFrom = from;
		relayout();
	}

	private void choose(JsonObject t) {
		if (held(t) <= 0) {
			toast("None of it held here", Ui.DIM);
			return;
		}
		chosen = t.get("token").getAsString();
		chosenFrom = t.get("spotOut").getAsDouble() > 0 ? "SPOT" : "LONG";
		relayout();
	}
	/** "UGLOWSTONE" -> "Glowstone". */
	private static String display(JsonObject t) {
		String n = t.get("token").getAsString();
		n = n.startsWith("U") ? n.substring(1) : n;
		if (n.equals("BLAZE")) return "Blaze rod";
		return n.charAt(0) + n.substring(1).toLowerCase(java.util.Locale.ROOT);
	}

	/** The item a token is pictured as, and priced by in its cell: the ingot, the gem, the dust. */
	private static String iconId(JsonObject t) {
		var w = t.getAsJsonArray("withdraw");
		return w.isEmpty() ? "minecraft:barrier" : w.get(Math.min(1, w.size() - 1)).getAsString();
	}

	private static ItemStack icon(JsonObject t) {
		return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(iconId(t))));
	}

	private static double per(JsonObject t, String itemId) {
		for (JsonElement el : t.getAsJsonArray("items")) {
			var row = el.getAsJsonArray();
			if (row.get(0).getAsString().equals(itemId)) return row.get(1).getAsDouble();
		}
		return 0;
	}

	/** Units of this token in the player's inventory. */
	private double carried(JsonObject t) {
		if (minecraft == null || minecraft.player == null) return 0;
		var inv = minecraft.player.getInventory();
		double units = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) units += s.getCount() * per(t, BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
		}
		return units;
	}

	/** Prices move and inventories change while the screen is open: cells follow without a relayout. */
	private void refreshCells() {
		List<JsonObject> toks = tokens();
		for (int i = 0; i < Math.min(cells.size(), toks.size()); i++) {
			JsonObject t = toks.get(i);
			HcButton cell = cells.get(i);
			double px = t.get("price").getAsDouble(), each = px * per(t, iconId(t)), carry = carried(t);
			String unit = t.get("unit").getAsString();
			if (withdrawTab) {
				// lit is the one chosen; what's held shows what it's worth, and what isn't says so
				double out = takeable(held(t));
				cell.cell(icon(t), out > 0 ? amount(out) + " " + unit : "none").selected(t.get("token").getAsString().equals(chosen));
				cell.tip(out > 0 ? amount(out) + " " + unit + " available to withdraw (≈ " + Fmt.usd(out * px) + ", after the fee)" : "None held here");
				continue;
			}
			String item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(iconId(t))).getDescription().getString();
			cell.cell(icon(t), px > 0 ? Fmt.usd(each) : "--").selected(carry > 0);
			String note = t.has("note") ? t.get("note").getAsString() : "";
			cell.tip((note.isEmpty() ? "" : note + "\n")
					+ "1 " + item + " ≈ " + (px > 0 ? Fmt.usd(each) : "--") + " · " + ClientState.display(t.get("market").getAsString())
					+ " at " + (px > 0 ? "$" + Fmt.price(px) : "--") + " / " + unit + "\n"
					+ (carry > 0 ? "You carry " + Fmt.size(carry) + " " + unit + " (≈ " + Fmt.usd(carry * px) + "): click to deposit it all" : "You carry none"));
		}
	}

	private void deposit(JsonObject t) {
		if (carried(t) <= 0) {
			toast("You carry no " + display(t).toLowerCase(java.util.Locale.ROOT), Ui.DIM);
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos);
		o.addProperty("token", t.get("token").getAsString());
		ClientNet.send("unit_deposit", o);
	}
	private void setMode(String m) {
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos);
		o.addProperty("mode", m);
		ClientNet.send("configure", o);
		info.addProperty("mode", m);
		relayout();
	}

	@Override
	protected void footer(Footer f) {
		f.primary("Done", this::onClose);
	}

	private void withdraw(String token, String from, double units) {
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos);
		o.addProperty("token", token);
		o.addProperty("from", from);
		o.addProperty("units", units);
		ClientNet.send("unit_withdraw", o);
	}

	@Override
	public void tick() {
		super.tick();
		long now = System.currentTimeMillis();
		if (now - lastAsk > 1000) {
			lastAsk = now;
			JsonObject o = new JsonObject();
			o.addProperty("pos", pos);
			ClientNet.send("unit_info", o);
		}
		if (!shape().equals(builtFor)) relayout();
		else refreshCells();
		if (ClientState.lastVaultAt > seenResult) {
			seenResult = ClientState.lastVaultAt;
			toast(ClientState.lastVaultMsg, ClientState.lastVaultOk ? Ui.GREEN : Ui.RED);
		}
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		if (detailY < 0) return;
		for (JsonObject t : tokens()) {
			if (!t.get("token").getAsString().equals(chosen)) continue;
			double n = takeable(t.get(chosenFrom.equals("SPOT") ? "spotOut" : "longOut").getAsDouble());
			String line = display(t) + (chosenFrom.equals("LONG") ? " (long)" : "") + ": " + amount(n) + " " + t.get("unit").getAsString()
					+ " available, ≈ " + Fmt.usd(n * Math.max(0, t.get("price").getAsDouble()));
			Ui.text(g, font, Ui.ellipsize(font, line, pw - 2 * PAD), PAD, detailY, Ui.TEXT);
		}
	}
}