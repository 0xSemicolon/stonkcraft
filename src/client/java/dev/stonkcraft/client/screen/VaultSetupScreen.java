package dev.stonkcraft.client.screen;

import com.google.gson.JsonObject;
import dev.stonkcraft.client.ClientNet;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcField;
import dev.stonkcraft.client.ui.HcScreen;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.trading.Fmt;

/** Shown for an empty vault: open a new paper account, watch any address read-only, or link one you already own. */
public class VaultSetupScreen extends HcScreen {
	private final JsonObject data;
	private final long pos;
	private HcField name;
	private HcField watchAddr;
	private long openedAt;

	public VaultSetupScreen(JsonObject data) {
		super("Open an Account");
		this.data = data;
		this.pos = data.get("pos").getAsLong();
	}

	/** Dollar value of the paper coins the player carries: what Open & deposit puts in. */
	private long carried() {
		if (minecraft == null || minecraft.player == null) return 0;
		var inv = minecraft.player.getInventory();
		long usd = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) usd += dev.stonkcraft.item.Coins.value(inv.getItem(i), "paper");
		return usd;
	}

	@Override
	protected String status() {
		if (ClientState.lastVaultAt > openedAt && !ClientState.lastVaultOk) return ClientState.lastVaultMsg;
		return "This vault is empty. Give it an account:";
	}

	@Override
	protected int statusColor() {
		return ClientState.lastVaultAt > openedAt && !ClientState.lastVaultOk ? Ui.RED : Ui.DIM;
	}

	@Override
	protected void layout(Flow f) {
		if (openedAt == 0) openedAt = System.currentTimeMillis();
		f.heading("New paper account");
		int y = f.row("Name", 14);
		name = f.add(field(f.left + 40, y, f.width - 40, "Name", "Trading Account"));
		name.setMaxLength(24);
		f.text("Paper money, live prices. It starts empty: Open & deposit puts the paper coins you carry straight in. "
				+ "You can always print more at + Fund.", Ui.DIM);
		y = f.row(null, ROW);
		int half = (f.width - 6) / 2;
		long carried = carried();
		f.add(new HcButton(f.left, y, half, ROW, carried > 0 ? "◆ Open & deposit " + Fmt.usdCompact(carried) : "◆ Open & deposit", HcButton.Style.PRIMARY,
				b -> create("paper", "", true)))
				.tip(carried > 0 ? "Opens the account and deposits the " + Fmt.usd(carried) + " in paper coins you carry" : "You carry no paper coins: it opens empty");
		f.add(new HcButton(f.left + half + 6, y, half, ROW, "Open empty", b -> create("paper", "", false))).tip("Opens the account with $0; deposit or fund it later");
		f.rule();
		f.heading("Watch an address");
		f.text("Read-only: mirrors any public trading account, for redstone and copy trading.", Ui.DIM);
		y = f.row(null, 14);
		watchAddr = f.add(field(f.left, y, f.width - 70, "Address", ""));
		watchAddr.setMaxLength(42);
		watchAddr.hint("0x… a trader's address");
		watchAddr.validate(s -> s.isBlank() || s.trim().matches("0x[0-9a-fA-F]{40}") ? null : "An address is 0x followed by 40 hex characters");
		f.add(new HcButton(f.left + f.width - 64, y, 64, 14, "Watch", b -> {
			if (watchAddr.getValue().isBlank() || watchAddr.error() != null) return;
			// keep the server's "Watch 0x12..ab" default unless the player typed a name
			create("watch", watchAddr.getValue().trim(), false);
		})).tip("Links this vault to the address, read-only");
		var mine = data.getAsJsonArray("mine");
		if (!mine.isEmpty()) {
			f.rule();
			f.heading("Or link one you already own");
			for (int i = 0; i < Math.min(5, mine.size()); i++) {
				JsonObject m = mine.get(i).getAsJsonObject();
				String id = m.get("id").getAsString();
				y = f.row(null, ROW);
				f.add(new HcButton(f.left, y, f.width, ROW, "↪ " + m.get("name").getAsString() + " · " + (m.has("tag") ? m.get("tag") : m.get("label")).getAsString(), b -> {
					JsonObject o = new JsonObject();
					o.addProperty("pos", pos);
					o.addProperty("id", id);
					ClientNet.send("vault_link", o);
				})).tip("Moves this account into this vault");
			}
		}
	}

	@Override
	protected void footer(Footer f) {
		f.right("Cancel", HcButton.Style.NORMAL, this::onClose);
	}

	private void create(String kind, String address, boolean deposit) {
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos);
		String n = kind.equals("watch") && name.getValue().equals("Trading Account") ? "" : name.getValue();
		o.addProperty("name", n);
		o.addProperty("kind", kind);
		o.addProperty("address", address);
		o.addProperty("deposit", deposit);
		ClientNet.send("vault_create", o);
	}
}
