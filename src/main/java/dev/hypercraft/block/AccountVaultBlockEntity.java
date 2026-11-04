package dev.hypercraft.block;

import com.google.gson.JsonObject;
import dev.hypercraft.HyperCraft;
import dev.hypercraft.item.Coins;
import dev.hypercraft.registry.ModBlocks;
import dev.hypercraft.registry.ModComponents;
import dev.hypercraft.trading.Account;
import dev.hypercraft.trading.AccountRegistry;
import dev.hypercraft.trading.TradingAccount;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.InsertionOnlyStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Holds an account in the world. The account id travels with the item when the vault is broken. */
public class AccountVaultBlockEntity extends HcBlockEntity {
	@Nullable
	public UUID accountId;
	// display cache, synced to clients for the front-face renderer
	public String displayName = "";
	public String displayLabel = "";
	public double displayEquity = Double.NaN;

	public final CoinInput coinInput = new CoinInput();

	public AccountVaultBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.VAULT_BE, pos, state);
	}

	@Override
	public String screen() {
		return "vault";
	}

	@Nullable
	public Account accountData() {
		AccountRegistry reg = HyperCraft.accounts();
		return reg == null ? null : reg.get(accountId);
	}

	public void bind(@Nullable Account a) {
		accountId = a == null ? null : a.id;
		refreshDisplay(true);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, AccountVaultBlockEntity be) {
		if (level.getGameTime() % 40 == 0) be.refreshDisplay(false);
	}

	private void refreshDisplay(boolean force) {
		Account a = accountData();
		String name = a == null ? "" : a.name;
		AccountRegistry reg = HyperCraft.accounts();
		String label = a == null || reg == null ? "" : reg.shortTag(a);
		TradingAccount t = reg == null || a == null ? null : reg.trading(a.id);
		double eq = t == null ? Double.NaN : t.equity();
		boolean changed = force || !name.equals(displayName) || !label.equals(displayLabel)
				|| Double.isNaN(eq) != Double.isNaN(displayEquity) || Math.abs(eq - displayEquity) >= 0.005;
		displayName = name;
		displayLabel = label;
		displayEquity = eq;
		if (changed) sync();
	}

	@Override
	public JsonObject configJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", accountId == null ? "" : accountId.toString());
		return o;
	}

	@Override
	public void applyConfig(JsonObject json) {}

	@Override
	protected void writeConfig(CompoundTag tag, HolderLookup.Provider registries) {
		if (accountId != null) tag.putUUID("Account", accountId);
		tag.putString("DisplayName", displayName);
		tag.putString("DisplayLabel", displayLabel);
		if (!Double.isNaN(displayEquity)) tag.putDouble("DisplayEquity", displayEquity);
	}

	@Override
	protected void readConfig(CompoundTag tag, HolderLookup.Provider registries) {
		accountId = tag.hasUUID("Account") ? tag.getUUID("Account") : null;
		displayName = tag.getString("DisplayName");
		displayLabel = tag.getString("DisplayLabel");
		displayEquity = tag.contains("DisplayEquity") ? tag.getDouble("DisplayEquity") : Double.NaN;
	}

	// The vault item carries its account: picked up from / written to the item's components.

	@Override
	protected void applyImplicitComponents(DataComponentInput input) {
		super.applyImplicitComponents(input);
		ModComponents.AccountRef ref = input.get(ModComponents.ACCOUNT);
		if (ref != null) {
			accountId = ref.uuid();
			displayName = ref.name();
		}
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder builder) {
		super.collectImplicitComponents(builder);
		if (accountId != null) {
			Account a = accountData();
			builder.set(ModComponents.ACCOUNT, new ModComponents.AccountRef(accountId.toString(), a == null ? displayName : a.name, "", "", ""));
		}
	}

	@Override
	public void removeComponentsFromTag(CompoundTag tag) {
		tag.remove("Account");
	}

	/** Hopper/pipe input: paper coins inserted from any side are deposited when the transaction commits. */
	public final class CoinInput extends SnapshotParticipant<Long> implements InsertionOnlyStorage<ItemVariant> {
		private long pendingUsd;

		@Override
		public long insert(ItemVariant resource, long maxAmount, TransactionContext tx) {
			Account a = accountData();
			if (a == null || a.kind != Account.Kind.PAPER) return 0;
			long each = Coins.value(resource.toStack(1), "paper");
			if (each == 0) return 0;
			updateSnapshots(tx);
			pendingUsd += each * maxAmount;
			return maxAmount;
		}

		@Override
		protected Long createSnapshot() {
			return pendingUsd;
		}

		@Override
		protected void readSnapshot(Long snapshot) {
			pendingUsd = snapshot;
		}

		@Override
		protected void onFinalCommit() {
			long usd = pendingUsd;
			pendingUsd = 0;
			Account a = accountData();
			AccountRegistry reg = HyperCraft.accounts();
			if (usd > 0 && a != null && reg != null) {
				reg.deposit(a, usd);
				refreshDisplay(true);
			}
		}
	}
}
