package dev.stonkcraft.block;

import com.google.gson.JsonObject;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.market.MarketData;
import dev.stonkcraft.registry.ModBlocks;
import dev.stonkcraft.registry.ModComponents;
import dev.stonkcraft.trading.AccessPolicy;
import dev.stonkcraft.trading.Account;
import dev.stonkcraft.trading.AccountRegistry;
import dev.stonkcraft.trading.Fmt;
import dev.stonkcraft.trading.PaperAccount;
import dev.stonkcraft.unit.UnitBridge;
import dev.stonkcraft.unit.UnitConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.InsertionOnlyStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * UNIT[XYZ]: a bridge between metal and an account. Right-click with metal, gems, redstone or fuel to deposit it (sneak to deposit
 * all you carry), empty-handed to open its screen and withdraw. Acts on the inserted card's account, else the
 * player's selected one. Hoppers feed the card's account. The comparator pulses 15 on a deposit, 8 on a withdrawal.
 */
public class UnitBridgeBlockEntity extends HcBlockEntity {
	public UnitBridge.Mode mode = UnitBridge.Mode.SPOT;
	/** Synced for the floating label: "GOLD $4,122/oz · COPPER $6.55/lb". */
	public String prices = "";
	private long flashUntil;
	private int flashLevel;
	public final MetalInput metalInput = new MetalInput();

	public UnitBridgeBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.UNIT_BRIDGE_BE, pos, state);
	}

	@Override
	public String screen() {
		return "unit_bridge";
	}

	@Override
	public boolean usesCard() {
		return true;
	}

	@Override
	public boolean needsTradeCard() {
		return true;
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, UnitBridgeBlockEntity be) {
		long t = level.getGameTime();
		if (t % 40 == 0) {
			String p = be.priceLine();
			if (!p.equals(be.prices)) {
				be.prices = p;
				be.sync();
			}
		}
		if (be.flashLevel != 0 && System.currentTimeMillis() > be.flashUntil) {
			be.flashLevel = 0;
			level.updateNeighbourForOutputSignal(pos, state.getBlock());
		}
	}

	private String priceLine() {
		MarketData md = Stonkcraft.market();
		StringBuilder sb = new StringBuilder();
		var tokens = UnitConfig.get().tokens;
		// the label is one line: the first three, then a count of the rest (the screen lists them all)
		for (int i = 0; i < tokens.size(); i++) {
			if (i == 3) {
				sb.append(" · +").append(tokens.size() - 3).append(" more");
				break;
			}
			UnitConfig.Token tok = tokens.get(i);
			double px = UnitBridge.price(md, tok);
			if (sb.length() > 0) sb.append(" · ");
			sb.append(tok.token.substring(1)).append(' ').append(Double.isNaN(px) ? "--" : Fmt.price(px)).append('/').append(tok.unit);
		}
		return sb.toString();
	}

	@Override
	public String labelTop() {
		ModComponents.AccountRef ref = cardRef();
		AccountVaultBlockEntity vault = ref == null ? BlockLinks.vault(level, worldPosition) : null;
		return "UNIT[XYZ] · " + mode.label + (ref != null ? " · " + ref.name() : vault != null ? " · " + vault.displayName : "");
	}

	@Override
	public String labelBottom() {
		return prices.isEmpty() ? "bridge goods for their live price" : prices;
	}

	@Override
	public int comparatorOutput() {
		return flashLevel;
	}

	private void flash(int level) {
		flashLevel = level;
		flashUntil = System.currentTimeMillis() + 1000;
		if (this.level != null) this.level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
	}

	/**
	 * The account this bridge acts on: the inserted card's, else the Account Vault it's linked to by touching, else the
	 * player's selected one. Someone else's vault doesn't open for a player; a hopper (no player) feeds a linked vault
	 * only if it belongs to whoever placed this bridge.
	 */
	@Nullable
	public Account target(@Nullable ServerPlayer player) {
		AccountRegistry reg = Stonkcraft.accounts();
		if (reg == null) return null;
		ModComponents.AccountRef ref = cardRef();
		if (ref != null) {
			Account a = reg.get(ref.uuid());
			// deposits and hopper feeds move money: a Trade Card, never a Watch Card
			return a != null && AccessPolicy.checkCard(a, ref, true) == null ? a : null;
		}
		Account linked = BlockLinks.account(level, worldPosition);
		if (player == null) return linked != null && linked.owner.equals(owner) ? linked : null;
		return linked != null && reg.canView(player, linked) ? linked : reg.selected(player);
	}

	/** Deposits the held stack, or with {@code all} every bridgeable item the player carries. */
	public void depositFrom(ServerPlayer player, boolean all) {
		depositFrom(player, all, null);
	}

	/**
	 * Deposits the held stack, or with {@code all} every bridgeable item the player carries; with {@code token} only
	 * that token's items (the screen's "deposit what you carry of this"), answering on the screen too.
	 */
	public void depositFrom(ServerPlayer player, boolean all, @Nullable String token) {
		AccountRegistry reg = Stonkcraft.accounts();
		Account a = target(player);
		if (reg == null || a == null) {
			player.displayClientMessage(Component.literal("§cNo account: insert a Trade Card, or open one at an Account Vault"), true);
			return;
		}
		PaperAccount p = reg.paper(a.id);
		if (p == null) {
			player.displayClientMessage(Component.literal("§c" + a.name + " is watch-only: it can't hold metal"), true);
			return;
		}
		Inventory inv = player.getInventory();
		Map<String, Long> taken = new LinkedHashMap<>();
		if (all) {
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (!UnitBridge.bridgeable(s)) continue;
				if (token != null && !UnitConfig.get().tokenFor(UnitBridge.itemId(s)).token.equals(token)) continue;
				taken.merge(UnitBridge.itemId(s), (long) s.getCount(), Long::sum);
				inv.setItem(i, ItemStack.EMPTY);
			}
		} else {
			ItemStack s = player.getMainHandItem();
			if (UnitBridge.bridgeable(s)) {
				taken.put(UnitBridge.itemId(s), (long) s.getCount());
				player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			}
		}
		if (taken.isEmpty()) {
			UnitConfig.Token t = token == null ? null : UnitConfig.get().token(token);
			if (t != null) result(player, false, "You carry no " + UnitConfig.name(t));
			else player.displayClientMessage(Component.literal("§7Nothing to bridge: right-click the UNIT[XYZ] empty-handed to see what it takes"), true);
			return;
		}
		double usd = 0;
		String last = "";
		for (Map.Entry<String, Long> e : taken.entrySet()) {
			UnitBridge.Result r = UnitBridge.deposit(p, Stonkcraft.market(), e.getKey(), e.getValue(), mode);
			if (!r.ok()) {
				giveBack(player, UnitBridge.item(e.getKey()), e.getValue());
				last = "§c" + r.message();
				continue;
			}
			// a lava bucket's bucket comes back
			net.minecraft.world.item.Item rest = UnitBridge.item(e.getKey()).getCraftingRemainingItem();
			if (rest != null) giveBack(player, rest, e.getValue());
			usd += r.usd();
			last = "§a" + r.message();
			a.log(player.getGameProfile().getName(), "unit.xyz", r.message(), true);
		}
		reg.save();
		String msg = taken.size() > 1 && usd > 0 ? "§aBridged " + Fmt.usd(usd) + " into " + a.name : last;
		player.displayClientMessage(Component.literal(msg), true);
		if (token != null) result(player, usd > 0, msg.substring(2));
		if (usd > 0) {
			dev.stonkcraft.game.Speedruns.bridged(player.getUUID(), usd, true);
			celebrate(true);
			dev.stonkcraft.game.Advancements.award(player, "gold_rush");
		}
	}

	private static void giveBack(ServerPlayer player, net.minecraft.world.item.Item item, long n) {
		ItemStack back = new ItemStack(item);
		while (n > 0) {
			int take = (int) Math.min(back.getMaxStackSize(), n);
			ItemStack give = back.copyWithCount(take);
			if (!player.getInventory().add(give)) player.drop(give, false);
			n -= take;
		}
	}

	/** Withdraws {@code units} of a token as metal into the player's inventory. Only the account's owner (or an op on paper). */
	public void withdrawTo(ServerPlayer player, String token, UnitBridge.Mode from, double units) {
		AccountRegistry reg = Stonkcraft.accounts();
		Account a = target(player);
		UnitConfig.Token t = UnitConfig.get().token(token);
		if (reg == null || a == null || t == null) return;
		if (!reg.canManage(player, a)) {
			result(player, false, "Only " + a.ownerName + " can withdraw from " + a.name);
			return;
		}
		PaperAccount p = reg.paper(a.id);
		if (p == null) return;
		UnitBridge.Result r = UnitBridge.withdraw(p, Stonkcraft.market(), t, from, units);
		if (!r.ok()) {
			result(player, false, r.message());
			return;
		}
		for (ItemStack s : r.items()) {
			if (!player.getInventory().add(s)) player.drop(s, false);
		}
		reg.logEvent(a, player, r.message());
		dev.stonkcraft.game.Speedruns.bridged(player.getUUID(), r.usd(), false);
		result(player, true, r.message());
		celebrate(false);
		dev.stonkcraft.game.Advancements.award(player, "cash_out");
	}

	private static void result(ServerPlayer player, boolean ok, String msg) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", ok);
		o.addProperty("msg", msg);
		dev.stonkcraft.net.HcNet.send(player, "vault_result", o);
	}

	private void celebrate(boolean in) {
		flash(in ? 15 : 8);
		if (!(level instanceof ServerLevel sl)) return;
		double x = worldPosition.getX() + 0.5, y = worldPosition.getY() + 1.1, z = worldPosition.getZ() + 0.5;
		sl.sendParticles(new DustParticleOptions(new Vector3f(1f, 0.82f, 0.25f), 1.3f), x, y, z, 30, 0.35, 0.3, 0.35, 0.02);
		sl.playSound(null, worldPosition, in ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 1f, in ? 0.8f : 1.2f);
		sl.playSound(null, worldPosition, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.25f, 1.9f);
	}

	/** Screen data: the target account and one row per token. */
	public JsonObject info(ServerPlayer player) {
		JsonObject o = new JsonObject();
		o.addProperty("pos", worldPosition.asLong());
		o.addProperty("mode", mode.name());
		o.addProperty("fee", UnitConfig.get().feeBps);
		AccountRegistry reg = Stonkcraft.accounts();
		Account a = target(player);
		PaperAccount p = reg == null || a == null ? null : reg.paper(a.id);
		if (a != null) {
			o.addProperty("account", a.name);
			o.addProperty("canWithdraw", reg.canManage(player, a));
			o.addProperty("viaCard", cardRef() != null);
			o.addProperty("paper", p != null);
			if (p != null) o.addProperty("equity", p.equity());
		}
		o.add("tokens", UnitBridge.rows(p, Stonkcraft.market()));
		return o;
	}

	@Override
	public JsonObject configJson() {
		JsonObject o = new JsonObject();
		o.addProperty("mode", mode.name());
		return o;
	}

	@Override
	public void applyConfig(JsonObject json) {
		mode = UnitBridge.Mode.parse(str(json, "mode", mode.name()));
		sync();
	}

	@Override
	protected void writeConfig(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putString("Mode", mode.name());
		tag.putString("Prices", prices);
	}

	@Override
	protected void readConfig(CompoundTag tag, HolderLookup.Provider registries) {
		mode = UnitBridge.Mode.parse(tag.getString("Mode"));
		prices = tag.getString("Prices");
	}

	/** Hopper/pipe input: bridgeable metal goes into the inserted card's account when the transaction commits. */
	public final class MetalInput extends SnapshotParticipant<Map<String, Long>> implements InsertionOnlyStorage<ItemVariant> {
		private Map<String, Long> pending = new LinkedHashMap<>();

		@Override
		public long insert(ItemVariant resource, long maxAmount, TransactionContext tx) {
			if (cardRef() == null || !UnitBridge.bridgeable(resource.toStack(1))) return 0;
			// a hopper has nowhere to put a lava bucket's empty bucket
			if (resource.getItem().getCraftingRemainingItem() != null) return 0;
			Account a = target(null);
			AccountRegistry reg = Stonkcraft.accounts();
			if (a == null || reg == null || reg.paper(a.id) == null) return 0;
			if (Double.isNaN(UnitBridge.price(Stonkcraft.market(), UnitConfig.get().tokenFor(UnitBridge.itemId(resource.toStack(1)))))) return 0;
			updateSnapshots(tx);
			pending.merge(UnitBridge.itemId(resource.toStack(1)), maxAmount, Long::sum);
			return maxAmount;
		}

		@Override
		protected Map<String, Long> createSnapshot() {
			return new LinkedHashMap<>(pending);
		}

		@Override
		protected void readSnapshot(Map<String, Long> snapshot) {
			pending = snapshot;
		}

		@Override
		protected void onFinalCommit() {
			Map<String, Long> batch = pending;
			pending = new LinkedHashMap<>();
			AccountRegistry reg = Stonkcraft.accounts();
			Account a = target(null);
			PaperAccount p = reg == null || a == null ? null : reg.paper(a.id);
			if (p == null) return;
			boolean any = false;
			for (Map.Entry<String, Long> e : batch.entrySet()) {
				UnitBridge.Result r = UnitBridge.deposit(p, Stonkcraft.market(), e.getKey(), e.getValue(), mode);
				if (r.ok()) {
					any = true;
					a.log(ownerName.isEmpty() ? "hopper" : ownerName, "UNIT[XYZ] hopper @ " + worldPosition.toShortString(), r.message(), true);
				}
			}
			if (any) {
				reg.markDirty();
				celebrate(true);
			}
		}
	}
}
