package dev.hypercraft.client.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hypercraft.client.ClientNet;
import dev.hypercraft.client.ClientState;
import dev.hypercraft.client.ui.HcButton;
import dev.hypercraft.client.ui.HcField;
import dev.hypercraft.client.ui.HcScreen;
import dev.hypercraft.client.ui.Ui;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * The owner's control panel for who can use an account: people (view or trade, with limits and expiry), printed
 * cards (read or trade, revocable), a freeze switch, and the activity log.
 */
public class AccessScreen extends HcScreen {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());
	private final String id;
	private final Screen parent;
	private int tab;
	private HcField who, maxOrder, maxLev, markets, hours, cardLabel;
	/** A printed card's own limits. */
	private HcField cardMarkets, cardMax, cardLev, cardHours;
	private boolean trade = true, reduceOnly, cardReduce;
	private int builtFor;
	private int previewY;
	private long freezeArmedAt, seenResult;

	public AccessScreen(String id, Screen parent) {
		super("Access");
		this.id = id;
		this.parent = parent;
		this.seenResult = ClientState.lastVaultAt;
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		ClientNet.send("access_get", o);
	}

	private JsonObject data() {
		JsonObject a = ClientState.access;
		return a != null && a.get("id").getAsString().equals(id) ? a : null;
	}

	private boolean frozen() {
		JsonObject d = data();
		return d != null && d.get("frozen").getAsBoolean();
	}

	@Override
	protected int panelWidth() {
		return 380;
	}

	@Override
	protected int accent() {
		return frozen() ? 0xFF7FD3FF : Ui.ACCENT;
	}

	@Override
	protected String headerRight() {
		JsonObject d = data();
		return d == null ? null : "◆ " + d.get("name").getAsString() + (frozen() ? "  ❄ frozen" : "");
	}

	@Override
	protected int headerRightColor() {
		return frozen() ? 0xFF7FD3FF : Ui.DIM;
	}

	@Override
	protected String status() {
		JsonObject d = data();
		if (d == null) return "Loading…";
		int n = d.getAsJsonArray("grants").size();
		return frozen() ? "Frozen: nobody, including card holders, can trade it until you unfreeze"
				: n == 0 ? "Only you can use this account" : n + (n == 1 ? " player has" : " players have") + " access";
	}

	@Override
	protected int statusColor() {
		return frozen() ? 0xFF7FD3FF : Ui.DIM;
	}

	@Override
	protected void layout(Flow f) {
		JsonObject d = data();
		builtFor = d == null ? 0 : d.toString().hashCode();
		int y = f.row(null, ROW);
		String[] tabs = {"People", "Cards", "Activity"};
		String[] tips = {"Players who can use this account, and their limits", "Cards printed for this account; revoke lost ones",
				"What other players and cards did with this account"};
		for (int i = 0; i < tabs.length; i++) {
			int t = i;
			f.add(new HcButton(f.left + i * 70, y, 66, ROW, tabs[i], HcButton.Style.TAB, b -> {
				tab = t;
				relayout();
			}).selected(tab == i).tip(tips[i]));
		}
		f.gap(2);
		if (d == null) return;
		switch (tab) {
			case 0 -> people(f, d);
			case 1 -> cards(f, d);
			default -> activity(f, d);
		}
	}

	private void people(Flow f, JsonObject d) {
		if (d.getAsJsonArray("grants").isEmpty()) {
			f.text("Nobody else yet. Give a friend access below: they trade this account from their own terminal and blocks, "
					+ "within the limits you set, and you can revoke it any time.", Ui.DIM);
		}
		for (JsonElement el : d.getAsJsonArray("grants")) {
			JsonObject gr = el.getAsJsonObject();
			boolean expired = gr.get("expired").getAsBoolean();
			String player = gr.get("player").getAsString();
			int y = f.row(null, 14);
			int c = gr.get("role").getAsString().equals("TRADE") ? Ui.YELLOW : Ui.ACCENT;
			f.beside(y, 14, Ui.ellipsize(font, gr.get("name").getAsString(), 80), expired ? Ui.DIM : Ui.TEXT, f.left, 80);
			f.beside(y, 14, Ui.ellipsize(font, gr.get("summary").getAsString() + (expired ? " (expired)" : ""), f.width - 150), expired ? Ui.DIM : c, f.left + 84, f.width - 150);
			f.add(new HcButton(f.left + f.width - 56, y, 56, 14, "Revoke", HcButton.Style.DANGER, b -> {
				JsonObject o = new JsonObject();
				o.addProperty("id", id);
				o.addProperty("player", player);
				ClientNet.send("grant_remove", o);
			}));
		}
		f.rule();
		f.heading("Give a player access");
		int y = f.row("Player", 14);
		who = f.add(field(f.left + 80, y, 100, "player", ""));
		who.hint("their name");
		f.add(new HcButton(f.left + 186, y, 50, 14, "Trade", HcButton.Style.TAB, b -> {
			trade = true;
			relayout();
		}).selected(trade).tip("Can place and cancel orders"));
		f.add(new HcButton(f.left + 240, y, 50, 14, "View", HcButton.Style.TAB, b -> {
			trade = false;
			relayout();
		}).selected(!trade).tip("Can see positions and use Watch Cards, nothing more"));
		maxOrder = maxLev = markets = null;
		if (trade) {
			y = f.row("Max order", 14);
			maxOrder = f.add(field(f.left + 80, y, 100, "maxOrder", ""));
			maxOrder.numeric(100).suffix("USD · 0 = none").hint("0").tip("Largest single order they may place. Blank or 0 = no limit");
			y = f.row("Max leverage", 14);
			maxLev = f.add(field(f.left + 80, y, 100, "maxLev", ""));
			maxLev.numeric(1).suffix("x · 0 = none").hint("0").tip("Highest leverage they may use. Blank or 0 = no limit");
			y = f.row("Expires after", 14);
			hours = f.add(field(f.left + 80, y, 100, "hours", ""));
			hours.numeric(1).suffix("hours").hint("never").tip("Access ends after this many hours. Blank or 0 = never");
			y = f.row("Markets", 14);
			markets = f.add(field(f.left + 80, y, 160, "markets", ""));
			markets.hint("all markets").tip("Only these markets, separated by spaces, e.g. BTC ETH. Blank = all");
			f.add(new HcButton(f.left + 246, y, f.width - 246, 14, (reduceOnly ? "■" : "□") + " Reduce-only", b -> {
				reduceOnly = !reduceOnly;
				b.label((reduceOnly ? "■" : "□") + " Reduce-only");
			})).tip("They can only shrink positions, never open new ones");
		} else {
			y = f.row("Expires after", 14);
			hours = f.add(field(f.left + 80, y, 100, "hours", ""));
			hours.numeric(1).suffix("hours").hint("never").tip("Access ends after this many hours. Blank or 0 = never");
		}
		previewY = f.reserve(10);
		f.text("They can't withdraw, move money, print cards or share it further.", 0xFF4E6E6A);
		y = f.row(null, ROW);
		f.add(new HcButton(f.left + f.width - 110, y, 110, ROW, "Give access", HcButton.Style.PRIMARY, b -> {
			if (who.getValue().isBlank()) {
				toast("Type the player's name first", Ui.YELLOW);
				return;
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("name", who.getValue().trim());
			o.addProperty("role", trade ? "TRADE" : "VIEW");
			o.addProperty("maxOrderUsd", maxOrder == null ? 0 : maxOrder.num(0));
			o.addProperty("maxLeverage", maxLev == null ? 0 : maxLev.num(0));
			o.addProperty("hours", hours.num(0));
			o.addProperty("markets", markets == null ? "" : markets.getValue());
			o.addProperty("reduceOnly", trade && reduceOnly);
			ClientNet.send("grant_set", o);
		})).tip("Grants it now, as the line above says; revoke it any time");
	}

	/** "Alex can trade BTC, ETH up to $500/order at ≤5x for 24h". */
	private String preview() {
		String name = who == null || who.getValue().isBlank() ? "They" : who.getValue().trim();
		if (!trade) return name + " can view this account" + expiry();
		String m = markets == null || markets.getValue().isBlank() ? "every market" : String.join(", ", markets.getValue().trim().toUpperCase().split("[ ,]+"));
		String lim = maxOrder != null && maxOrder.num(0) > 0 ? " up to $" + dev.hypercraft.trading.Fmt.size(maxOrder.num(0)) + "/order" : "";
		String lev = maxLev != null && maxLev.num(0) > 0 ? " at ≤" + (int) maxLev.num(0) + "x" : "";
		return name + " can " + (reduceOnly ? "reduce positions in " : "trade ") + m + lim + lev + expiry();
	}

	private String expiry() {
		return hours != null && hours.num(0) > 0 ? " for " + dev.hypercraft.trading.Fmt.size(hours.num(0)) + "h" : "";
	}

	private void cards(Flow f, JsonObject d) {
		if (d.getAsJsonArray("cards").isEmpty()) f.text("No cards printed yet. Print one below and hand it out or put it in a block.", Ui.DIM);
		for (JsonElement el : d.getAsJsonArray("cards")) {
			JsonObject c = el.getAsJsonObject();
			boolean revoked = c.get("revoked").getAsBoolean();
			String label = c.get("label").getAsString();
			int y = f.row(null, 14);
			// name, then the date, then Limit and Revoke at the right
			int nameW = f.width - 104 - 4 - 70 - 4;
			f.beside(y, 14, Ui.ellipsize(font, (c.get("level").getAsString().equals("TRADE") ? "Trade" : "Watch") + " Card " + c.get("id").getAsString().substring(0, 6)
					+ (label.isEmpty() ? "" : " · " + label), nameW), revoked ? Ui.DIM : Ui.TEXT, f.left, nameW);
			boolean expired = c.has("expired") && c.get("expired").getAsBoolean();
			f.beside(y, 14, Ui.ellipsize(font, revoked ? "revoked" : expired ? "expired" : TIME.format(Instant.ofEpochMilli(c.get("issued").getAsLong())), 70),
					revoked || expired ? Ui.RED : Ui.DIM, f.left + nameW + 4, 70);
			String scope = c.has("scope") ? c.get("scope").getAsString() : "";
			if (!revoked) {
				String cid = c.get("id").getAsString();
				f.add(new HcButton(f.left + f.width - 104, y, 44, 14, "Limit", b -> {
					JsonObject o = new JsonObject();
					o.addProperty("id", id);
					o.addProperty("card", cid);
					o.add("scope", scopeJson());
					ClientNet.send("card_scope", o);
				})).tip("Applies the limits typed under Print a card to this card. Limits only get tighter; to loosen them, print a new card");
				f.add(new HcButton(f.left + f.width - 56, y, 56, 14, "Revoke", HcButton.Style.DANGER, b -> {
					JsonObject o = new JsonObject();
					o.addProperty("id", id);
					o.addProperty("card", cid);
					ClientNet.send("card_revoke", o);
				})).tip("The card stops working everywhere, at once");
			}
			if (!scope.isEmpty()) f.text("   Limits: " + scope, revoked ? Ui.DIM : Ui.YELLOW);
		}
		f.rule();
		f.heading("Print a card");
		int y = f.row("Markets", 14);
		cardMarkets = f.add(field(f.left + 60, y, 120, "card markets", ""));
		cardMarkets.hint("all markets").tip("A Trade Card for only these markets, separated by spaces, e.g. BTC ETH. Blank = all");
		f.add(new HcButton(f.left + 186, y, f.width - 186, 14, (cardReduce ? "■" : "□") + " Reduce-only", b -> {
			cardReduce = !cardReduce;
			b.label((cardReduce ? "■" : "□") + " Reduce-only");
		})).tip("The card can only shrink positions, never open new ones");
		y = f.row("Max order", 14);
		cardMax = f.add(field(f.left + 60, y, 120, "card max order", ""));
		cardMax.numeric(100).suffix("$ · 0 = none").hint("0").tip("Largest single order the card may place. Blank or 0 = no limit");
		cardLev = f.add(field(f.left + 186, y, f.width - 186, "card max lev", ""));
		cardLev.numeric(1).suffix("x max").hint("any").tip("Highest leverage the card may set. Blank or 0 = any");
		y = f.row("Expires", 14);
		cardHours = f.add(field(f.left + 60, y, 120, "card hours", ""));
		cardHours.numeric(1).suffix("hours").hint("never").tip("The card stops working after this many hours. Blank or 0 = never. A Watch Card takes only this limit");
		y = f.row("Label", 14);
		cardLabel = f.add(field(f.left + 60, y, 120, "label", ""));
		cardLabel.hint("optional");
		f.add(new HcButton(f.left + 186, y - 2, (f.width - 192) / 2, ROW, "Watch Card", b -> print("READ"))).tip("Read-only: for sensors, displays, mixers and copy-desk leaders");
		f.add(new HcButton(f.left + 186 + (f.width - 192) / 2 + 6, y - 2, (f.width - 192) / 2, ROW, "Trade Card", b -> print("TRADE")))
				.tip("For executors and copy desks");
		f.text("Watch Cards only look: sensors, displays, mixers, copy-desk leaders. Trade Cards drive executors, kill switches, dials and UNIT.XYZ, but only in blocks owned by someone with trade access.", 0xFF4E6E6A);
	}

	private void print(String level) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("level", level);
		o.addProperty("label", cardLabel.getValue());
		o.add("scope", scopeJson());
		ClientNet.send("vault_card", o);
	}

	/** The limits typed under Print a card. */
	private JsonObject scopeJson() {
		JsonObject s = new JsonObject();
		if (cardMarkets != null) s.addProperty("markets", cardMarkets.getValue());
		if (cardMax != null) s.addProperty("maxOrderUsd", cardMax.num(0));
		if (cardLev != null) s.addProperty("maxLeverage", cardLev.num(0));
		if (cardHours != null) s.addProperty("hours", cardHours.num(0));
		s.addProperty("reduceOnly", cardReduce);
		return s;
	}

	private void activity(Flow f, JsonObject d) {
		if (d.getAsJsonArray("activity").isEmpty()) f.text("Nothing yet. Trades by people and cards with access show up here.", Ui.DIM);
		for (JsonElement el : d.getAsJsonArray("activity")) {
			JsonObject e = el.getAsJsonObject();
			String line = TIME.format(Instant.ofEpochMilli(e.get("t").getAsLong())) + "  " + e.get("actor").getAsString() + " (" + e.get("via").getAsString() + "): "
					+ e.get("text").getAsString();
			f.text(line, e.get("ok").getAsBoolean() ? Ui.TEXT : Ui.RED);
			f.y -= 2;
		}
	}

	@Override
	protected void footer(Footer f) {
		boolean fr = frozen();
		f.left(fr ? "❄ Unfreeze" : "Freeze account", fr ? HcButton.Style.NORMAL : HcButton.Style.DANGER, () -> {
			if (!fr && System.currentTimeMillis() - freezeArmedAt > 4000) {
				freezeArmedAt = System.currentTimeMillis();
				toast("Click Freeze again: nobody, including card holders, can trade until you unfreeze", Ui.YELLOW);
				return;
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("on", !fr);
			ClientNet.send("freeze", o);
		}).tip(fr ? "Lets people and cards use the account again" : "Stops all trading on this account, by anyone, until you unfreeze it");
		f.primary("Done", this::onClose);
	}

	@Override
	public void tick() {
		super.tick();
		JsonObject d = data();
		if ((d == null ? 0 : d.toString().hashCode()) != builtFor) relayout();
		if (ClientState.lastVaultAt > seenResult) {
			seenResult = ClientState.lastVaultAt;
			toast(ClientState.lastVaultMsg, ClientState.lastVaultOk ? Ui.GREEN : Ui.RED);
		}
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		if (tab == 0 && data() != null && who != null) Ui.text(g, font, Ui.ellipsize(font, preview(), pw - 2 * PAD), PAD, previewY, Ui.TEXT);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
