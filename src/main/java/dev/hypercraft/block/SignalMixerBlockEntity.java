package dev.hypercraft.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.hypercraft.HyperCraft;
import dev.hypercraft.game.Advancements;
import dev.hypercraft.registry.ModBlocks;
import dev.hypercraft.registry.ModComponents;
import dev.hypercraft.registry.ModItems;
import dev.hypercraft.signal.SignalEngine;
import dev.hypercraft.signal.SignalNode;
import dev.hypercraft.signal.SignalOp;
import dev.hypercraft.signal.SignalRegistry;
import dev.hypercraft.signal.SignalValue;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/**
 * A Signal Mixer: one signal, its op and parameters, and up to four input cards (account cards or Signal Cards).
 * The signal itself lives in the server's {@link SignalRegistry}; this block edits it, prints Signal Cards for it,
 * feeds it redstone, and outputs its value as redstone.
 */
public class SignalMixerBlockEntity extends HcBlockEntity {
	public static final int MAX_INPUTS = 4;
	@Nullable
	public UUID nodeId;
	public final List<ItemStack> inputCards = new ArrayList<>();
	/** Synced for the label. */
	public String name = "Signal";
	public String opLabel = "";
	public String reading = "";
	private long lastSync;

	public SignalMixerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.SIGNAL_MIXER_BE, pos, state);
	}

	@Override
	public String screen() {
		return "signal_mixer";
	}

	/** A mixer's signal is its reading: Print card makes the same Signal Card {@link #printCard} does. */
	@Override
	public boolean hasTap() {
		return true;
	}

	@Override
	@Nullable
	public SignalNode tap() {
		return node();
	}

	@Nullable
	public SignalNode node() {
		SignalRegistry reg = HyperCraft.signals();
		if (reg == null || level == null || level.isClientSide) return null;
		SignalNode n = nodeId == null ? null : reg.node(nodeId);
		if (n == null) {
			n = reg.createNode(owner, ownerName, worldPosition, level.dimension().location().toString());
			nodeId = n.id;
			rebuildInputs(n);
			setChanged();
		}
		return n;
	}

	private void rebuildInputs(SignalNode n) {
		List<Double> weights = new ArrayList<>();
		for (SignalNode.Input in : n.inputs) weights.add(in.weight);
		n.inputs.clear();
		for (int i = 0; i < inputCards.size(); i++) {
			SignalNode.Input in = SignalRegistry.inputFrom(inputCards.get(i));
			if (in == null) continue;
			if (i < weights.size()) in.weight = weights.get(i);
			n.inputs.add(in);
		}
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, SignalMixerBlockEntity be) {
		if (level.getGameTime() % 10 != 0) return;
		SignalNode n = be.node();
		SignalRegistry reg = HyperCraft.signals();
		if (n == null || reg == null) return;
		n.redstone = level.getBestNeighborSignal(pos);
		if (be.owner != null && n.owner == null) {
			n.owner = be.owner;
			n.ownerName = be.ownerName;
		}
		SignalValue v = reg.value(n.id);
		int power = be.power(n, v);
		if (power != state.getValue(BlockStateProperties.POWER)) level.setBlock(pos, state.setValue(BlockStateProperties.POWER, power), Block.UPDATE_ALL);
		long now = System.currentTimeMillis();
		if (now - be.lastSync > 2000) {
			be.lastSync = now;
			String r = SignalEngine.describe(v);
			if (!r.equals(be.reading) || !n.name.equals(be.name) || !n.op.label.equals(be.opLabel)) {
				be.reading = r;
				be.name = n.name;
				be.opLabel = n.op.label;
				be.sync();
			}
		}
	}

	/** Redstone out: 0..1 outputs (map, compare, gates) span 0-15; other numbers are taken as they are; portfolios show gross x5. */
	private int power(SignalNode n, SignalValue v) {
		if (v instanceof SignalValue.Scalar s) {
			double x = s.value();
			boolean unit = n.op == SignalOp.MAP || n.op == SignalOp.COMPARE || n.op == SignalOp.MARKET_HOURS;
			return Mth.clamp((int) Math.round(unit ? x * 15 : x), 0, 15);
		}
		if (v instanceof SignalValue.Portfolio p) return Mth.clamp((int) Math.round(p.gross() * 5), 0, 15);
		return 0;
	}

	@Override
	public String labelTop() {
		return "◇ " + name + (opLabel.isEmpty() ? "" : " · " + opLabel);
	}

	@Override
	public String labelBottom() {
		return reading.isEmpty() ? "no value yet" : reading;
	}

	@Override
	public int labelColor() {
		return 0xFFD08CFF;
	}

	/** Adds a card as the next input. Returns why not, or null. */
	@Nullable
	public String addInput(ServerPlayer player, ItemStack card) {
		SignalNode n = node();
		SignalRegistry reg = HyperCraft.signals();
		if (n == null || reg == null) return "Signals aren't running";
		if (inputCards.size() >= MAX_INPUTS) return "All four inputs are full: eject one first";
		SignalNode.Input in = SignalRegistry.inputFrom(card);
		if (in == null) return "That isn't a Trade, Watch or Signal Card";
		String err = reg.checkInput(n, in);
		if (err != null) return err;
		inputCards.add(dev.hypercraft.item.AccountCardItem.normalized(card.split(1)));
		n.inputs.add(in);
		if (!in.account()) Advancements.award(player, "signal_processing");
		reg.markDirty();
		sync();
		return null;
	}

	public ItemStack ejectInput(int i) {
		if (i < 0 || i >= inputCards.size()) return ItemStack.EMPTY;
		ItemStack out = inputCards.remove(i);
		SignalNode n = node();
		if (n != null && i < n.inputs.size()) n.inputs.remove(i);
		if (HyperCraft.signals() != null) HyperCraft.signals().markDirty();
		sync();
		return out;
	}

	/** Prints a Signal Card for this mixer's signal. */
	public ItemStack printCard() {
		SignalNode n = node();
		if (n == null) return ItemStack.EMPTY;
		ItemStack s = new ItemStack(ModItems.SIGNAL_CARD);
		s.set(ModComponents.SIGNAL, new ModComponents.SignalRef(n.id.toString(), n.name));
		return s;
	}

	public void dropContents(Level level, BlockPos pos) {
		for (ItemStack s : inputCards) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), s);
		inputCards.clear();
		if (nodeId != null && HyperCraft.signals() != null) HyperCraft.signals().removeNode(nodeId);
	}

	public JsonObject info() {
		JsonObject o = screenJson();
		SignalNode n = node();
		if (n == null) return o;
		o.addProperty("name", n.name);
		o.addProperty("op", n.op.name());
		o.addProperty("text", n.text);
		o.addProperty("a", n.a);
		o.addProperty("b", n.b);
		o.addProperty("redstone", n.redstone);
		JsonArray ins = new JsonArray();
		for (SignalNode.Input in : n.inputs) {
			JsonObject j = new JsonObject();
			j.addProperty("kind", in.kind);
			j.addProperty("name", in.name);
			j.addProperty("weight", in.weight);
			j.addProperty("level", in.level);
			ins.add(j);
		}
		o.add("inputs", ins);
		SignalRegistry reg = HyperCraft.signals();
		SignalValue v = reg == null ? SignalValue.NONE : reg.value(n.id);
		o.addProperty("valueKind", v instanceof SignalValue.Scalar ? "scalar" : v instanceof SignalValue.Portfolio ? "portfolio" : "none");
		if (v instanceof SignalValue.Scalar s) o.addProperty("value", s.value());
		if (v instanceof SignalValue.Portfolio p) {
			o.addProperty("equity", p.equity());
			o.addProperty("gross", p.gross());
			JsonArray w = new JsonArray();
			p.weights().entrySet().stream().sorted((x, y) -> Double.compare(Math.abs(y.getValue()), Math.abs(x.getValue()))).limit(8).forEach(e -> {
				JsonArray row = new JsonArray();
				row.add(e.getKey());
				row.add(e.getValue());
				w.add(row);
			});
			o.add("weights", w);
		}
		return o;
	}

	@Override
	public JsonObject screenJson() {
		JsonObject o = super.screenJson();
		o.addProperty("coin", "");
		return o;
	}

	@Override
	public JsonObject configJson() {
		return new JsonObject();
	}

	@Override
	public void applyConfig(JsonObject json) {
		SignalNode n = node();
		if (n == null) return;
		if (json.has("name")) {
			String nm = json.get("name").getAsString().replaceAll("[§\\p{Cntrl}]", "").trim();
			n.name = nm.isEmpty() ? "Signal" : nm.substring(0, Math.min(24, nm.length()));
		}
		if (json.has("op")) n.op = SignalOp.parse(json.get("op").getAsString());
		if (json.has("text")) n.text = json.get("text").getAsString().substring(0, Math.min(200, json.get("text").getAsString().length()));
		n.a = num(json, "a", n.a);
		n.b = num(json, "b", n.b);
		if (json.has("weights")) {
			JsonArray w = json.getAsJsonArray("weights");
			for (int i = 0; i < Math.min(w.size(), n.inputs.size()); i++) {
				double x = w.get(i).getAsDouble();
				if (Double.isFinite(x)) n.inputs.get(i).weight = Math.max(-100, Math.min(100, x));
			}
		}
		if (HyperCraft.signals() != null) HyperCraft.signals().markDirty();
		name = n.name;
		opLabel = n.op.label;
		sync();
	}

	@Override
	protected void writeConfig(CompoundTag tag, HolderLookup.Provider registries) {
		if (nodeId != null) tag.putUUID("Node", nodeId);
		ListTag list = new ListTag();
		for (ItemStack s : inputCards) list.add(s.save(registries));
		tag.put("Inputs", list);
		tag.putString("Name", name);
		tag.putString("OpLabel", opLabel);
		tag.putString("Reading", reading);
	}

	@Override
	protected void readConfig(CompoundTag tag, HolderLookup.Provider registries) {
		nodeId = tag.hasUUID("Node") ? tag.getUUID("Node") : null;
		inputCards.clear();
		for (Tag t : tag.getList("Inputs", Tag.TAG_COMPOUND)) {
			ItemStack s = ItemStack.parseOptional(registries, (CompoundTag) t);
			if (!s.isEmpty()) inputCards.add(s);
		}
		name = tag.getString("Name");
		opLabel = tag.getString("OpLabel");
		reading = tag.getString("Reading");
	}
}
