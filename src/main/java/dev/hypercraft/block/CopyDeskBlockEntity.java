package dev.hypercraft.block;

import com.google.gson.JsonObject;
import dev.hypercraft.HyperCraft;
import dev.hypercraft.registry.ModBlocks;
import dev.hypercraft.registry.ModComponents;
import dev.hypercraft.registry.ModItems;
import dev.hypercraft.signal.CopyDesk;
import dev.hypercraft.signal.SignalEngine;
import dev.hypercraft.signal.SignalNode;
import dev.hypercraft.signal.SignalRegistry;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * A Copy Desk: copies a leader (a Signal, Watch or Trade Card; desks from before Watch Cards may hold a typed address) into the follower, the
 * trade card in its card slot. The desk itself runs in the server's {@link SignalRegistry}, so it keeps copying while
 * unloaded. The comparator reads tracking error: 0 on target, 15 at 15% of equity or more.
 */
public class CopyDeskBlockEntity extends HcBlockEntity {
	@Nullable
	public UUID deskId;
	/** The leader's card (account or Signal Card); the follower's trade card is {@link #card}. */
	public ItemStack leaderCard = ItemStack.EMPTY;
	public String label = "";
	public String status = "";
	public int tracking;

	public CopyDeskBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.COPY_DESK_BE, pos, state);
	}

	@Override
	public String screen() {
		return "copy_desk";
	}

	@Override
	public boolean usesCard() {
		return true;
	}

	@Override
	public boolean hasTap() {
		return true;
	}

	/** The desk's tracking error, 0..1 of the follower's equity (nothing while it's stopped). */
	@Override
	protected void defineTap(SignalNode n) {
		n.op = dev.hypercraft.signal.SignalOp.DESK;
		n.text = deskId == null ? "" : deskId.toString();
		n.inputs.clear();
		ModComponents.AccountRef ref = cardRef();
		n.name = "Desk" + (ref == null ? "" : " → " + ref.name()) + " · tracking";
	}

	@Nullable
	public CopyDesk desk() {
		SignalRegistry reg = HyperCraft.signals();
		if (reg == null || level == null || level.isClientSide) return null;
		CopyDesk d = reg.desk(deskId);
		if (d == null) {
			d = reg.createDesk(owner, ownerName, worldPosition, level.dimension().location().toString());
			deskId = d.id;
			setChanged();
		}
		return d;
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, CopyDeskBlockEntity be) {
		if (level.getGameTime() % 20 != 0) return;
		CopyDesk d = be.desk();
		SignalRegistry reg = HyperCraft.signals();
		if (d == null || reg == null) return;
		be.tap();
		if (be.owner != null && d.owner == null) {
			d.owner = be.owner;
			d.ownerName = be.ownerName;
		}
		d.follower = be.card.isEmpty() ? null : SignalRegistry.inputFrom(be.card);
		d.leader = be.leaderCard.isEmpty() ? null : SignalRegistry.inputFrom(be.leaderCard);
		d.powered = level.hasNeighborSignal(pos);
		String l = reg.describe(d);
		int tr = Mth.clamp((int) Math.round(d.tracking * 100), 0, 15);
		if (!l.equals(be.label) || !d.status.equals(be.status) || tr != be.tracking) {
			be.label = l;
			be.status = d.status;
			if (tr != be.tracking) {
				be.tracking = tr;
				level.updateNeighbourForOutputSignal(pos, state.getBlock());
			}
			be.sync();
		}
	}

	@Override
	public int comparatorOutput() {
		return tracking;
	}

	@Override
	public String labelTop() {
		ModComponents.AccountRef ref = cardRef();
		return "⧉ " + (label.isEmpty() ? "Copy Desk" : label) + (ref == null ? "" : " → " + ref.name());
	}

	@Override
	public String labelBottom() {
		return card.isEmpty() ? "⚠ insert the follower's Trade Card" : status.isEmpty() ? "idle" : status;
	}

	@Override
	public int labelColor() {
		return 0xFF7FD3FF;
	}

	/**
	 * Puts a card in: Signal and Watch Cards always lead; a Trade Card becomes the follower if that slot is empty,
	 * else the leader. Only a Trade Card can follow, since following trades. Returns the card it replaced.
	 */
	public ItemStack insert(ItemStack stack) {
		ItemStack one = dev.hypercraft.item.AccountCardItem.normalized(stack.split(1));
		if (one.is(ModItems.ACCOUNT_CARD) && one.has(ModComponents.ACCOUNT) && card.isEmpty()) return swapCard(one);
		ItemStack old = leaderCard;
		leaderCard = one;
		sync();
		return old;
	}

	/** Makes a Watch Card the leader, replacing a typed address (the card now carries it). Returns the old leader. */
	public ItemStack watchLeader(ItemStack watchCard) {
		CopyDesk d = desk();
		if (d != null) d.address = "";
		if (HyperCraft.signals() != null) HyperCraft.signals().markDirty();
		ItemStack old = leaderCard;
		leaderCard = watchCard;
		sync();
		return old;
	}

	public ItemStack ejectLeader() {
		ItemStack old = leaderCard;
		leaderCard = ItemStack.EMPTY;
		sync();
		return old;
	}

	public void dropContents(Level level, BlockPos pos) {
		if (!leaderCard.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), leaderCard);
		leaderCard = ItemStack.EMPTY;
		if (deskId != null && HyperCraft.signals() != null) HyperCraft.signals().removeDesk(deskId);
	}

	/** The leader card is the follower's own account, and no address overrides it. */
	private boolean selfCopy(CopyDesk d) {
		ModComponents.AccountRef f = cardRef();
		SignalNode.Input leader = leaderCard.isEmpty() ? null : SignalRegistry.inputFrom(leaderCard);
		if (f == null || leader == null || !leader.account() || leader.uuid() == null || !d.address.isEmpty()) return false;
		return leader.uuid().toString().equals(f.id());
	}

	public JsonObject info() {
		JsonObject o = screenJson();
		CopyDesk d = desk();
		SignalRegistry reg = HyperCraft.signals();
		if (d == null || reg == null) return o;
		ModComponents.AccountRef f = cardRef();
		SignalNode.Input leader = leaderCard.isEmpty() ? null : SignalRegistry.inputFrom(leaderCard);
		// two different accounts can share a name; then the start of each id tells them apart
		boolean twins = f != null && leader != null && leader.account() && leader.name.equals(f.name()) && !leader.id.equals(f.id());
		o.addProperty("follower", f == null ? "" : f.name() + (twins ? " #" + f.id().substring(0, 4) : ""));
		o.addProperty("leader", leader == null ? "" : (leader.account() ? "◈ " : "◇ ") + leader.name + (twins ? " #" + leader.id.substring(0, Math.min(4, leader.id.length())) : "")
				+ (leader.account() && "READ".equals(leader.level) && !leader.name.startsWith("Watch ") ? " (watch)" : ""));
		o.addProperty("selfCopy", selfCopy(d));
		o.addProperty("address", d.address);
		o.addProperty("running", d.running);
		o.addProperty("sizing", d.sizing.name());
		o.addProperty("ratio", d.ratio);
		o.addProperty("maxLeverage", d.maxLeverage);
		o.addProperty("bandPct", d.bandPct);
		o.addProperty("bandMin", d.bandMin);
		o.addProperty("maxStep", d.maxStep);
		o.addProperty("interval", d.interval);
		o.addProperty("drawdownPct", d.drawdownPct);
		o.addProperty("onLost", d.onLost.name());
		o.addProperty("redstone", d.redstone.name());
		o.addProperty("status", d.status);
		o.addProperty("tracking", d.tracking);
		o.addProperty("leaderValue", SignalEngine.describe(reg.leaderValue(d)));
		return o;
	}

	@Override
	public JsonObject configJson() {
		return new JsonObject();
	}

	@Override
	public void applyConfig(JsonObject json) {
		CopyDesk d = desk();
		if (d == null) return;
		if (json.has("address")) {
			String a = json.get("address").getAsString().trim().toLowerCase();
			d.address = a.matches("0x[0-9a-f]{40}") ? a : "";
		}
		if (json.has("running")) {
			// copying an account onto itself would trade against its own positions
			boolean run = json.get("running").getAsBoolean() && !selfCopy(d);
			if (run && !d.running) {
				d.peakEquity = 0;
				d.lastStep = 0;
			}
			d.running = run;
		}
		if (json.has("sizing")) d.sizing = "MIRROR".equals(json.get("sizing").getAsString()) ? CopyDesk.Sizing.MIRROR : CopyDesk.Sizing.PROPORTIONAL;
		d.ratio = clamp(num(json, "ratio", d.ratio), 0, 100);
		d.maxLeverage = clamp(num(json, "maxLeverage", d.maxLeverage), 0.1, 50);
		d.bandPct = clamp(num(json, "bandPct", d.bandPct), 0, 50);
		d.bandMin = clamp(num(json, "bandMin", d.bandMin), 10, 1e7);
		d.maxStep = clamp(num(json, "maxStep", d.maxStep), 10, 1e8);
		d.interval = (int) clamp(num(json, "interval", d.interval), 3, 3600);
		d.drawdownPct = clamp(num(json, "drawdownPct", d.drawdownPct), 0, 99);
		if (json.has("onLost")) d.onLost = "FLATTEN".equals(json.get("onLost").getAsString()) ? CopyDesk.OnLost.FLATTEN : CopyDesk.OnLost.HOLD;
		if (json.has("redstone")) {
			try {
				d.redstone = CopyDesk.Redstone.valueOf(json.get("redstone").getAsString());
			} catch (IllegalArgumentException ignored) {
			}
		}
		if (HyperCraft.signals() != null) HyperCraft.signals().markDirty();
		sync();
	}

	private static double clamp(double v, double lo, double hi) {
		return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
	}

	@Override
	protected void writeConfig(CompoundTag tag, HolderLookup.Provider registries) {
		if (deskId != null) tag.putUUID("Desk", deskId);
		if (!leaderCard.isEmpty()) tag.put("Leader", leaderCard.save(registries));
		tag.putString("Label", label);
		tag.putString("Status", status);
		tag.putInt("Tracking", tracking);
	}

	@Override
	protected void readConfig(CompoundTag tag, HolderLookup.Provider registries) {
		deskId = tag.hasUUID("Desk") ? tag.getUUID("Desk") : null;
		leaderCard = tag.contains("Leader") ? ItemStack.parseOptional(registries, tag.getCompound("Leader")) : ItemStack.EMPTY;
		label = tag.getString("Label");
		status = tag.getString("Status");
		tracking = tag.getInt("Tracking");
	}
}
