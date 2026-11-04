package dev.stonkcraft.block;

import com.google.gson.JsonObject;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.registry.ModComponents;
import dev.stonkcraft.trading.AccountRegistry;
import dev.stonkcraft.trading.OrderResult;
import dev.stonkcraft.trading.TradingAccount;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.joml.Vector3f;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Base for configurable Stonkcraft blocks: owner tracking, an optional account-card slot, JSON config, client sync. */
public abstract class HcBlockEntity extends BlockEntity {
	@Nullable
	protected UUID owner;
	protected String ownerName = "";
	/** The inserted account card; trading blocks act on this card's account. */
	protected ItemStack card = ItemStack.EMPTY;
	/** This block's own reading as a signal, kept in the server's signal registry; see {@link #tap()}. */
	@Nullable
	protected UUID tapId;
	/** Last outcome, synced for the floating label: 1 ok, -1 failed, 0 never acted. */
	public int lastStatus;
	public String lastMessage = "";

	protected HcBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	/** Which client screen edits this block. */
	public abstract String screen();

	public abstract JsonObject configJson();

	public abstract void applyConfig(JsonObject json);

	protected abstract void writeConfig(CompoundTag tag, HolderLookup.Provider registries);

	protected abstract void readConfig(CompoundTag tag, HolderLookup.Provider registries);

	/** Whether this block takes an account card. */
	public boolean usesCard() {
		return false;
	}

	public void setOwner(UUID id, String name) {
		owner = id;
		ownerName = name;
		sync();
	}

	@Nullable
	public UUID owner() {
		return owner;
	}

	public String ownerName() {
		return ownerName;
	}

	/**
	 * Only the owner configures a block or swaps its card. Not even ops: a trading block acts as its owner, so
	 * letting someone else rewire it would let them trade with the owner's access.
	 */
	public boolean canEdit(ServerPlayer player) {
		return owner == null || owner.equals(player.getUUID());
	}

	public ItemStack card() {
		return card;
	}

	/** Blocks that act on the account take only a Trade Card; the rest take either kind. */
	public boolean needsTradeCard() {
		return false;
	}

	/** Swaps in a new card (or EMPTY) and returns the old one. */
	public ItemStack swapCard(ItemStack next) {
		ItemStack old = card;
		card = next;
		sync();
		return old;
	}

	@Nullable
	public ModComponents.AccountRef cardRef() {
		return card.isEmpty() ? null : card.get(ModComponents.ACCOUNT);
	}

	/** The Signal Card in the card slot, for blocks that read signals there. */
	@Nullable
	public ModComponents.SignalRef signalRef() {
		return card.isEmpty() ? null : card.get(ModComponents.SIGNAL);
	}

	/** The current value of the Signal Card in the card slot. */
	public dev.stonkcraft.signal.SignalValue signalValue() {
		return signalValue(signalRef());
	}

	protected static dev.stonkcraft.signal.SignalValue signalValue(@Nullable ModComponents.SignalRef ref) {
		dev.stonkcraft.signal.SignalRegistry reg = Stonkcraft.signals();
		return ref == null || reg == null ? dev.stonkcraft.signal.SignalValue.NONE : reg.value(ref.uuid());
	}

	/**
	 * Takes a Signal Card, returning the card it replaced (or EMPTY), or null if this block doesn't read signals.
	 * Sensors and displays hold it in the card slot; executors in a second slot.
	 */
	@Nullable
	public ItemStack acceptSignalCard(ItemStack one) {
		return null;
	}

	/** Whether this kind of block shares its reading, so its screen offers Print Signal Card. */
	public boolean hasTap() {
		return false;
	}

	/** Sets {@code n} up as this block's reading (op, market, parameters, the card as input, name). */
	protected void defineTap(dev.stonkcraft.signal.SignalNode n) {}

	/**
	 * This block's reading, created on first use and brought up to date with the block's config. It lives in the
	 * signal registry, so it keeps evaluating while the block is unloaded, and Signal Cards printed from it read it.
	 */
	@Nullable
	public dev.stonkcraft.signal.SignalNode tap() {
		dev.stonkcraft.signal.SignalRegistry reg = Stonkcraft.signals();
		if (!hasTap() || reg == null || level == null || level.isClientSide) return null;
		dev.stonkcraft.signal.SignalNode n = tapId == null ? null : reg.node(tapId);
		if (n == null) {
			n = reg.createNode(owner, ownerName, worldPosition, level.dimension().location().toString());
			n.tap = true;
			tapId = n.id;
			setChanged();
		}
		String before = n.shape();
		n.owner = owner;
		n.ownerName = ownerName;
		defineTap(n);
		if (!before.equals(n.shape())) reg.markDirty();
		return n;
	}

	/** The tap's current value (NONE until the registry has evaluated it). */
	public dev.stonkcraft.signal.SignalValue tapValue() {
		dev.stonkcraft.signal.SignalRegistry reg = Stonkcraft.signals();
		return reg == null || tapId == null ? dev.stonkcraft.signal.SignalValue.NONE : reg.value(tapId);
	}

	/** A Signal Card reading this block's tap, or EMPTY if it has none. */
	public ItemStack printTap() {
		dev.stonkcraft.signal.SignalNode n = tap();
		if (n == null) return ItemStack.EMPTY;
		ItemStack s = new ItemStack(dev.stonkcraft.registry.ModItems.SIGNAL_CARD);
		s.set(ModComponents.SIGNAL, new ModComponents.SignalRef(n.id.toString(), n.name));
		return s;
	}

	/** Called when the block is broken: its reading goes, and Signal Cards printed from it read nothing. */
	public void removeTap() {
		if (tapId != null && Stonkcraft.signals() != null) Stonkcraft.signals().removeNode(tapId);
		tapId = null;
	}

	/** Sets a tap's only input to this block's card (an account or Signal Card), or none. */
	protected void tapInputFromCard(dev.stonkcraft.signal.SignalNode n) {
		dev.stonkcraft.signal.SignalNode.Input in = card.isEmpty() ? null : dev.stonkcraft.signal.SignalRegistry.inputFrom(card);
		n.inputs.clear();
		if (in != null) n.inputs.add(in);
	}

	/**
	 * Takes an Order Ticket, returning what goes back to the player (the replaced ticket, a freshly written one, or
	 * EMPTY), or null if this block doesn't take tickets.
	 */
	@Nullable
	public ItemStack acceptTicket(ItemStack one) {
		return null;
	}

	/** What sneaking with an empty hand takes out before the card (an executor's ticket), or EMPTY. */
	public ItemStack ejectFirst() {
		return ItemStack.EMPTY;
	}

	/** Items besides the card slot to drop when the block is broken. */
	public java.util.List<ItemStack> extraDrops() {
		return java.util.List.of();
	}

	@Nullable
	private dev.stonkcraft.trading.Account cardAccount() {
		AccountRegistry accounts = Stonkcraft.accounts();
		ModComponents.AccountRef ref = cardRef();
		return accounts == null || ref == null ? null : accounts.get(ref.uuid());
	}

	/** The card's account for reading (sensors, displays, order status), or null without a valid card. */
	@Nullable
	protected TradingAccount account() {
		AccountRegistry accounts = Stonkcraft.accounts();
		dev.stonkcraft.trading.Account a = cardAccount();
		if (a == null || dev.stonkcraft.trading.AccessPolicy.checkBlockCard(a, cardRef(), false, owner, System.currentTimeMillis()) != null) return null;
		accounts.noteUse(a.id);
		return accounts.trading(a.id);
	}

	/**
	 * The card's account for trading, acting as this block's owner: every order is checked against the owner's
	 * access (and any grant limits) and logged. Null when the card can't trade; {@link #tradeReason()} says why.
	 */
	@Nullable
	protected TradingAccount tradingAccount() {
		AccountRegistry accounts = Stonkcraft.accounts();
		dev.stonkcraft.trading.Account a = cardAccount();
		if (a == null || dev.stonkcraft.trading.AccessPolicy.checkBlockCard(a, cardRef(), true, owner, System.currentTimeMillis()) != null) return null;
		accounts.noteUse(a.id);
		String who = ownerName.isEmpty() ? "unowned block" : ownerName;
		String via = screen().replace('_', ' ') + " @ " + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
		return accounts.guarded(a, new dev.stonkcraft.trading.GuardedAccount.Actor(owner, who, via, true, cardRef().cardId()));
	}

	/** Why this block can't trade right now, or null if it can try (the guard may still refuse a given order). */
	@Nullable
	protected OrderResult tradeReason() {
		if (cardRef() == null) return OrderResult.fail("", "No Trade Card inserted");
		dev.stonkcraft.trading.Account a = cardAccount();
		if (a == null) return OrderResult.fail("", "Card's account is unavailable");
		String card = dev.stonkcraft.trading.AccessPolicy.checkBlockCard(a, cardRef(), true, owner, System.currentTimeMillis());
		if (card != null) return OrderResult.fail("", card);
		return tradingAccount() == null ? OrderResult.fail("", a.name + " isn't available on this server") : null;
	}

	/** First line of the floating label over this block, or null for none (or a label the renderer builds itself). */
	@Nullable
	public String labelTop() {
		return null;
	}

	/** Second line of the floating label, shown when the block is looked at. */
	public String labelBottom() {
		return lastMessage;
	}

	/** Colour of the label's first line. */
	public int labelColor() {
		return 0xFF2FE6D0;
	}

	/** Comparator-friendly view of the last outcome. */
	public int comparatorOutput() {
		return lastStatus > 0 ? 15 : lastStatus < 0 ? 1 : 0;
	}

	/** The account to act on, or a failed result explaining why there isn't one. */
	@Nullable
	protected OrderResult noAccountReason() {
		if (account() != null) return null;
		return OrderResult.fail("", cardRef() == null ? "No Trade Card inserted" : "Card's account is unavailable");
	}

	/** Records an outcome, shows particles and sounds, and tells the owner. Call on the server thread. */
	protected void announce(ServerLevel level, OrderResult r) {
		if (isRemoved()) return;
		lastStatus = r.ok() ? 1 : -1;
		lastMessage = r.message();
		sync();
		level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
		double x = worldPosition.getX() + 0.5, y = worldPosition.getY() + 1.1, z = worldPosition.getZ() + 0.5;
		if (r.ok()) {
			boolean sell = r.size() < 0;
			Vector3f color = sell ? new Vector3f(1f, 0.33f, 0.4f) : new Vector3f(0.25f, 0.95f, 0.65f);
			level.sendParticles(new DustParticleOptions(color, 1.4f), x, y, z, 24, 0.35, 0.25, 0.35, 0.02);
			level.sendParticles(ParticleTypes.END_ROD, x, y, z, 5, 0.2, 0.3, 0.2, 0.03);
			level.playSound(null, worldPosition, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.7f, sell ? 0.9f : 1.4f);
		} else {
			level.sendParticles(ParticleTypes.SMOKE, x, y, z, 10, 0.25, 0.15, 0.25, 0.01);
			level.playSound(null, worldPosition, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 0.6f, 1.0f);
		}
		if (owner != null) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
			ModComponents.AccountRef ref = cardRef();
			if (p != null) {
				p.displayClientMessage(Component.literal("§b[" + (ref == null ? "--" : ref.name()) + "] " + (r.ok() ? "§a" : "§c") + r.message()), true);
				// an open config screen for this block shows the outcome in its status line
				JsonObject res = new JsonObject();
				res.addProperty("pos", worldPosition.asLong());
				res.addProperty("ok", r.ok());
				res.addProperty("msg", r.message());
				dev.stonkcraft.net.HcNet.send(p, "block_result", res);
			}
		}
		if (r.ok()) Stonkcraft.LOG.info("{} at {} ({}): {}", screen(), worldPosition.toShortString(), ownerName, r.message());
	}

	public JsonObject screenJson() {
		JsonObject o = configJson();
		o.addProperty("pos", worldPosition.asLong());
		o.addProperty("owner", ownerName);
		if (usesCard()) {
			ModComponents.AccountRef ref = cardRef();
			o.addProperty("account", ref == null ? "" : ref.name());
			o.addProperty("needsTrade", needsTradeCard());
			o.addProperty("watchCard", dev.stonkcraft.item.AccountCardItem.isWatch(card));
			// the account's record of the card's limits, not the item's copy
			dev.stonkcraft.trading.Account a = cardAccount();
			dev.stonkcraft.trading.Account.CardRecord rec = a == null || ref == null ? null : a.card(ref.cardId());
			if (rec != null && rec.scope != null && !rec.scope.empty()) o.addProperty("scope", rec.scope.summary());
		}
		if (hasTap()) o.addProperty("tap", true);
		return o;
	}

	public void sync() {
		setChanged();
		if (level != null && !level.isClientSide) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		if (owner != null) tag.putUUID("Owner", owner);
		tag.putString("OwnerName", ownerName);
		if (!card.isEmpty()) tag.put("Card", card.save(registries));
		tag.putInt("LastStatus", lastStatus);
		tag.putString("LastMessage", lastMessage);
		if (tapId != null) tag.putUUID("Tap", tapId);
		writeConfig(tag, registries);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
		ownerName = tag.getString("OwnerName");
		card = tag.contains("Card") ? ItemStack.parseOptional(registries, tag.getCompound("Card")) : ItemStack.EMPTY;
		// a read card printed before Watch Cards existed shows as what it is (an executor still won't trade with it)
		card = dev.stonkcraft.item.AccountCardItem.normalized(card);
		lastStatus = tag.getInt("LastStatus");
		lastMessage = tag.getString("LastMessage");
		tapId = tag.hasUUID("Tap") ? tag.getUUID("Tap") : null;
		readConfig(tag, registries);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Nullable
	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	protected static String str(JsonObject o, String key, String def) {
		return o.has(key) ? o.get(key).getAsString() : def;
	}

	protected static double num(JsonObject o, String key, double def) {
		try {
			return o.has(key) ? o.get(key).getAsDouble() : def;
		} catch (RuntimeException e) {
			return def;
		}
	}
}
