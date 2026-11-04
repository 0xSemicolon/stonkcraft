package dev.hypercraft.block;

import com.google.gson.JsonObject;
import dev.hypercraft.HyperCraft;
import dev.hypercraft.market.MarketData;
import dev.hypercraft.registry.ModBlocks;
import dev.hypercraft.trading.Fmt;
import dev.hypercraft.trading.OrderRequest;
import dev.hypercraft.trading.OrderResult;
import dev.hypercraft.trading.Side;
import dev.hypercraft.trading.TradingAccount;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Places any kind of order on a redstone pulse; everything the terminal's ticket can do, wired to redstone.
 * Size, leverage, price offset, TP/SL and TWAP minutes can each be fixed or read from a face's signal strength,
 * so other circuits can steer the order. Faces bound to an input don't trigger the executor.
 */
public class TradeExecutorBlockEntity extends HcBlockEntity implements PulseBlock.Target {
	/** Maps a source's signal 0..15 onto lo..hi. */
	/** Signal Card read by inputs whose source is SIGNAL (a second slot; the account card stays in the first). */
	public ItemStack signalCard = ItemStack.EMPTY;

	@Override
	@Nullable
	public ItemStack acceptSignalCard(ItemStack one) {
		ItemStack old = signalCard;
		signalCard = one;
		sync();
		return old;
	}

	@Override
	public java.util.List<ItemStack> extraDrops() {
		java.util.List<ItemStack> out = new java.util.ArrayList<>();
		if (!signalCard.isEmpty()) out.add(signalCard);
		if (!ticket.isEmpty()) out.add(ticket);
		return out;
	}

	/** This executor's order as a ticket carries it: everything but the machine's own settings, plus a summary. */
	public JsonObject orderJson() {
		JsonObject o = configJson();
		for (String k : MACHINE) o.remove(k);
		o.addProperty("summary", describe());
		return o;
	}

	/** Runs an order a ticket carries, keeping the machine's own settings. */
	private void loadOrder(JsonObject order) {
		JsonObject merged = configJson();
		for (var e : order.entrySet()) if (!MACHINE.contains(e.getKey())) merged.add(e.getKey(), e.getValue());
		applyOrder(merged);
	}

	/**
	 * Takes an Order Ticket: a blank one gets this executor's order written on it and comes back, a written one is
	 * loaded and the ticket it replaces (or EMPTY) comes back.
	 */
	@Override
	@Nullable
	public ItemStack acceptTicket(ItemStack one) {
		if (!dev.hypercraft.item.OrderTicketItem.written(one)) {
			one.set(dev.hypercraft.registry.ModComponents.ORDER_SPEC, orderJson().toString());
			return one;
		}
		ItemStack old = ticket;
		// its own order is saved once, when the first ticket goes in, and kept while tickets cycle through
		if (ticket.isEmpty() && ownOrder.isEmpty()) ownOrder = configJson().toString();
		ticket = one;
		loadOrder(com.google.gson.JsonParser.parseString(one.get(dev.hypercraft.registry.ModComponents.ORDER_SPEC)).getAsJsonObject());
		sync();
		return old;
	}

	/** Takes the ticket out (sneak with an empty hand), putting the executor's own order back. */
	@Override
	public ItemStack ejectFirst() {
		if (ticket.isEmpty()) return ItemStack.EMPTY;
		ItemStack old = ticket;
		ticket = ItemStack.EMPTY;
		restoreOwn();
		sync();
		return old;
	}

	private void restoreOwn() {
		if (ownOrder.isEmpty()) return;
		try {
			loadOrder(com.google.gson.JsonParser.parseString(ownOrder).getAsJsonObject());
		} catch (RuntimeException ignored) {
		}
		ownOrder = "";
	}

	/** Cycle, after a pulse: the used ticket goes down into the container below, and the next one comes up. */
	private void cycleTickets(ServerLevel level) {
		var below = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, worldPosition.below(), Direction.UP);
		if (!ticket.isEmpty() && below != null) {
			try (var tx = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
				if (below.insert(net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.of(ticket), 1, tx) == 1) {
					tx.commit();
					ticket = ItemStack.EMPTY;
				}
			}
		}
		pullTicket(level);
		if (ticket.isEmpty()) restoreOwn();
		sync();
	}

	/** Cycle: with the slot empty, takes the next Order Ticket from the container above. */
	private void pullTicket(ServerLevel level) {
		if (!ticket.isEmpty()) return;
		var above = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, worldPosition.above(), Direction.DOWN);
		if (above == null) return;
		try (var tx = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
			for (var view : above.nonEmptyViews()) {
				var res = view.getResource();
				ItemStack s = res.toStack(1);
				if (!dev.hypercraft.item.OrderTicketItem.written(s) || view.extract(res, 1, tx) != 1) continue;
				tx.commit();
				acceptTicket(s);
				return;
			}
		}
	}

	/** Hopper and pipe input: an Order Ticket goes into the empty ticket slot. */
	public final TicketInput ticketInput = new TicketInput();

	public final class TicketInput extends net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant<ItemStack>
			implements net.fabricmc.fabric.api.transfer.v1.storage.base.InsertionOnlyStorage<net.fabricmc.fabric.api.transfer.v1.item.ItemVariant> {
		private ItemStack pending = ItemStack.EMPTY;

		@Override
		public long insert(net.fabricmc.fabric.api.transfer.v1.item.ItemVariant resource, long maxAmount,
				net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext tx) {
			ItemStack s = resource.toStack(1);
			if (maxAmount < 1 || !ticket.isEmpty() || !pending.isEmpty() || !dev.hypercraft.item.OrderTicketItem.written(s)) return 0;
			updateSnapshots(tx);
			pending = s;
			return 1;
		}

		@Override
		protected ItemStack createSnapshot() {
			return pending;
		}

		@Override
		protected void readSnapshot(ItemStack snapshot) {
			pending = snapshot;
		}

		@Override
		protected void onFinalCommit() {
			ItemStack s = pending;
			pending = ItemStack.EMPTY;
			if (!s.isEmpty() && ticket.isEmpty()) acceptTicket(s);
		}
	}

	/** The Signal Card's value clamped to 0..1 (normalise with a Map op), NaN without one. */
	public double signalFraction() {
		double v = signalValue(signalCard.isEmpty() ? null : signalCard.get(dev.hypercraft.registry.ModComponents.SIGNAL)).asNumber(coin);
		return Double.isNaN(v) ? Double.NaN : Math.max(0, Math.min(1, v));
	}

	public static final class Binding {
		public ExecutorInput.Source source = ExecutorInput.Source.FIXED;
		public double lo, hi;

		Binding(double lo, double hi) {
			this.lo = lo;
			this.hi = hi;
		}

		public boolean bound() {
			return source != ExecutorInput.Source.FIXED;
		}
	}

	public String coin = "BTC";
	public Side side = Side.BUY;
	public double usd = 100;
	public OrderRequest.Type type = OrderRequest.Type.MARKET;
	/** true: price is % from mid; false: absolute price. */
	public boolean offsetMode = true;
	public double price;
	public double offsetPct;
	public OrderRequest.Tif tif = OrderRequest.Tif.GTC;
	public boolean reduceOnly;
	public double tpPct;
	public double slPct;
	public int cooldownSeconds = 5;
	/** TWAP duration when type is TWAP. */
	public int minutes = 30;
	/** Leverage set on the coin before each order; 0 leaves the account's setting alone. */
	public int leverage;
	public boolean cross = true;
	public final Map<ExecutorInput, Binding> inputs = new EnumMap<>(ExecutorInput.class);

	/** What a pulse does while this executor's last order is still resting. */
	public enum Repeat {
		NEW("Place new"), AMEND("Amend my order"), REPLACE("Replace my order");

		public final String label;

		Repeat(String label) {
			this.label = label;
		}

		public static Repeat parse(String s) {
			try {
				return valueOf(s);
			} catch (IllegalArgumentException | NullPointerException e) {
				return NEW;
			}
		}
	}

	public Repeat onRepeat = Repeat.NEW;
	/** The Order Ticket it runs, or EMPTY to run its own order. */
	public ItemStack ticket = ItemStack.EMPTY;
	/** Its own order while a ticket is in, put back when the ticket comes out (JSON; "" without a ticket). */
	private String ownOrder = "";
	/** After each pulse, pass the ticket down into a container below and take the next one from a container above. */
	public boolean cycle;
	/** Settings of the machine, not of the order: they stay with the executor when tickets come and go. */
	private static final java.util.Set<String> MACHINE = java.util.Set.of("cooldown", "comparatorOrder", "onEvent", "eventOnly", "cycle", "ticket");
	/** Also fire on each new event from the Signal Card (an executor's order, a fill, a bell). */
	public boolean onEvent;
	/** With onEvent: redstone no longer fires it. */
	public boolean eventOnly;
	private final dev.hypercraft.signal.EventWatch events = new dev.hypercraft.signal.EventWatch();
	/** Comparator reads this executor's order status instead of the last result. */
	public boolean comparatorOrder;
	/** Cancel this executor's order if it is still resting this many seconds after placement; 0 = never. */
	public int cancelAfter;
	/** Client order id of this executor's most recent order, and when cancel-after fires (epoch ms, 0 = never). */
	@Nullable
	public String myCloid;
	public long deadline;
	private int orderSignal;

	private long lastFireTick = Long.MIN_VALUE / 2;
	private boolean pending;

	/** The last few outcomes, newest first, for the config screen (this session only). */
	private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();

	@Override
	protected void announce(ServerLevel level, OrderResult r) {
		recent.addFirst(java.time.LocalTime.now().withNano(0) + (r.ok() ? "  ✔ " : "  ✘ ") + r.message());
		while (recent.size() > 5) recent.removeLast();
		// a bot's first trade is worth a toast
		if (r.ok() && owner() != null) dev.hypercraft.game.Advancements.award(level.getServer(), owner(), "hands_off_the_wheel");
		super.announce(level, r);
	}

	public TradeExecutorBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.EXECUTOR_BE, pos, state);
		for (ExecutorInput in : ExecutorInput.values()) inputs.put(in, new Binding(in.defLo, in.defHi));
	}

	@Override
	public boolean usesCard() {
		return true;
	}

	@Override
	public boolean needsTradeCard() {
		return true;
	}

	@Override
	public String screen() {
		return "executor";
	}

	public Binding input(ExecutorInput in) {
		return inputs.get(in);
	}

	/** Whether a face feeds an input (and so doesn't trigger). */
	public boolean isInputFace(Direction d) {
		for (Binding b : inputs.values()) if (b.source.face() == d) return true;
		return false;
	}

	/** Strongest signal on the faces that aren't inputs: what fires the executor. */
	public int triggerPower(Level level) {
		int best = 0;
		for (Direction d : Direction.values()) {
			if (!isInputFace(d)) best = Math.max(best, level.getSignal(worldPosition.relative(d), d));
		}
		return best;
	}

	/** Signal strength a source currently delivers. */
	public int signal(@Nullable Level level, ExecutorInput.Source src, int triggerPower) {
		if (src == ExecutorInput.Source.TRIGGER) return triggerPower;
		if (src == ExecutorInput.Source.SIGNAL) {
			double f = signalFraction();
			return Double.isNaN(f) ? 0 : (int) Math.round(f * 15);
		}
		Direction d = src.face();
		return d == null || level == null ? 0 : level.getSignal(worldPosition.relative(d), d);
	}

	/** An input's value right now: the fixed setting, or its source's signal mapped onto its range. */
	public double value(@Nullable Level level, ExecutorInput in, double fixed, int triggerPower) {
		Binding b = inputs.get(in);
		if (b.source == ExecutorInput.Source.SIGNAL) {
			double f = signalFraction();
			return Double.isNaN(f) ? fixed : b.lo + (b.hi - b.lo) * f;
		}
		return b.bound() ? ExecutorInput.map(signal(level, b.source, triggerPower), b.lo, b.hi) : fixed;
	}

	public int effectiveLeverage(@Nullable Level level, int power) {
		return (int) Math.round(value(level, ExecutorInput.LEVERAGE, leverage, power));
	}

	public OrderRequest toRequest(@Nullable Level level, int power) {
		OrderRequest r = new OrderRequest();
		r.coin = coin;
		r.side = side;
		r.usd = Math.max(0, value(level, ExecutorInput.SIZE, usd, power));
		r.type = type;
		double px = value(level, ExecutorInput.PRICE, offsetMode ? offsetPct : price, power);
		r.price = offsetMode ? 0 : px;
		r.offsetPct = offsetMode ? px : 0;
		r.tif = tif;
		r.reduceOnly = reduceOnly;
		r.tpPct = Math.max(0, value(level, ExecutorInput.TP, tpPct, power));
		r.slPct = Math.max(0, value(level, ExecutorInput.SL, slPct, power));
		r.minutes = Mth.clamp((int) Math.round(value(level, ExecutorInput.MINUTES, minutes, power)), 5, 1440);
		return r;
	}

	/** Short summary for the floating label, e.g. "Limit -1% BUY $500 BTC 10x +TP/SL". */
	public String describe() {
		StringBuilder sb = new StringBuilder();
		if (type == OrderRequest.Type.TWAP) {
			sb.append("TWAP ").append(input(ExecutorInput.MINUTES).bound() ? "~" : minutes + "m").append(' ');
		} else if (type != OrderRequest.Type.MARKET) {
			sb.append(type == OrderRequest.Type.TAKE_PROFIT ? "TP" : type.label).append(' ');
			if (input(ExecutorInput.PRICE).bound()) sb.append("~ ");
			else sb.append(offsetMode ? String.format("%+.2g%%", offsetPct) : Fmt.price(price)).append(' ');
		}
		sb.append(side.label);
		if (side.usesAmount()) sb.append(' ').append(input(ExecutorInput.SIZE).bound() ? "$~" : Fmt.usd(usd).replace(".00", ""));
		sb.append(' ').append(coin);
		if (input(ExecutorInput.LEVERAGE).bound()) sb.append(" ~x");
		else if (leverage > 0) sb.append(' ').append(leverage).append('x');
		if (tpPct > 0 || slPct > 0 || input(ExecutorInput.TP).bound() || input(ExecutorInput.SL).bound()) sb.append(" +TP/SL");
		if (reduceOnly) sb.append(" RO");
		if (onRepeat == Repeat.AMEND) sb.append(" ↻amend");
		else if (onRepeat == Repeat.REPLACE) sb.append(" ↻replace");
		if (cancelAfter > 0) sb.append(" ⏱").append(cancelAfter).append('s');
		return sb.toString();
	}

	/** Owner half of this block's client order ids. */
	public String cloidPrefix() {
		String dim = level == null ? "?" : level.dimension().location().toString();
		return dev.hypercraft.trading.Cloids.blockPrefix(dim, worldPosition.asLong());
	}

	public dev.hypercraft.trading.OrderStatus myOrderStatus() {
		TradingAccount acct = account();
		return acct == null || myCloid == null ? dev.hypercraft.trading.OrderStatus.NONE : acct.orderStatus(myCloid);
	}

	@Override
	public boolean hasTap() {
		return true;
	}

	@Override
	protected void defineTap(dev.hypercraft.signal.SignalNode n) {
		n.op = dev.hypercraft.signal.SignalOp.ORDER;
		n.text = "";
		n.inputs.clear();
		String c = coin.contains(":") ? coin.substring(coin.indexOf(':') + 1) : coin;
		n.name = c + " " + side.label.toLowerCase(java.util.Locale.ROOT) + " orders";
	}

	@Override
	public void onPulse(ServerLevel level, int power) {
		// redstone; an executor driven by events alone ignores it
		if (onEvent && eventOnly) return;
		fire(level, power);
	}

	private void fire(ServerLevel level, int power) {
		// a cycling executor with nothing loaded starts on the first ticket waiting above
		if (cycle) pullTicket(level);
		long now = level.getGameTime();
		if (pending || now - lastFireTick < cooldownSeconds * 20L) {
			level.playSound(null, worldPosition, SoundEvents.DISPENSER_FAIL, SoundSource.BLOCKS, 0.5f, 1.6f);
			return;
		}
		lastFireTick = now;
		OrderResult missing = tradeReason();
		if (missing != null) {
			announce(level, missing);
			return;
		}
		TradingAccount acct = tradingAccount();
		OrderRequest req = toRequest(level, power);
		int lev = effectiveLeverage(level, power);
		boolean twap = type == OrderRequest.Type.TWAP;
		boolean open = !twap && myOrderStatus().isOpen();
		pending = true;
		level.playSound(null, worldPosition, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.4f, 1.8f);
		CompletableFuture<OrderResult> action;
		if (open && onRepeat == Repeat.AMEND) {
			String id = myCloid;
			req.cloid = id;
			action = applyLeverage(acct, lev).thenCompose(levErr -> levErr != null ? CompletableFuture.completedFuture(levErr) : acct.modify(id, req));
		} else if (open && onRepeat == Repeat.REPLACE) {
			String old = myCloid;
			action = acct.cancelByCloid(coin, old).thenCompose(c -> placeNew(acct, req, lev, twap));
		} else {
			action = placeNew(acct, req, lev, twap);
		}
		action.whenComplete((result, err) -> level.getServer().execute(() -> {
			pending = false;
			OrderResult res = err != null ? OrderResult.fail(coin, String.valueOf(err.getMessage())) : result;
			announce(level, res);
			updateOrderSignal(level);
			if (cycle) cycleTickets(level);
			// what this executor's Signal Cards read: one event per order that went through
			if (res.ok() && HyperCraft.signals() != null && tap() != null) {
				long t = System.currentTimeMillis();
				HyperCraft.signals().push(tapId, new dev.hypercraft.signal.SignalValue.Event(t, t, res.message()));
			}
		}));
	}

	private CompletableFuture<OrderResult> placeNew(TradingAccount acct, OrderRequest req, int lev, boolean twap) {
		req.cloid = twap ? null : dev.hypercraft.trading.Cloids.next(cloidPrefix());
		myCloid = req.cloid;
		deadline = !twap && cancelAfter > 0 ? System.currentTimeMillis() + cancelAfter * 1000L : 0;
		setChanged();
		return applyLeverage(acct, lev).thenCompose(levErr -> levErr != null ? CompletableFuture.completedFuture(levErr) : acct.submit(req));
	}

	/** Once a second: cancel-after deadline, and the comparator's view of this executor's order. */
	public static void serverTick(Level level, BlockPos pos, BlockState state, TradeExecutorBlockEntity be) {
		if (level.getGameTime() % 10 != 0 || !(level instanceof ServerLevel sl)) return;
		// its own orders would fire it again and again
		dev.hypercraft.registry.ModComponents.SignalRef ref = be.signalCard.isEmpty() ? null : be.signalCard.get(dev.hypercraft.registry.ModComponents.SIGNAL);
		boolean own = ref != null && be.tapId != null && be.tapId.toString().equals(ref.id());
		if (be.onEvent && !own && be.events.next(signalValue(ref))) be.fire(sl, 15);
		if (level.getGameTime() % 20 != 0) return;
		be.tap();
		be.tickOrder(sl);
	}

	private void tickOrder(ServerLevel level) {
		if (myCloid == null) return;
		TradingAccount acct = tradingAccount();
		if (acct == null) return;
		if (deadline > 0 && System.currentTimeMillis() >= deadline && !pending) {
			deadline = 0;
			setChanged();
			if (acct.orderStatus(myCloid).isOpen()) {
				pending = true;
				int secs = cancelAfter;
				acct.cancelByCloid(coin, myCloid).whenComplete((r, err) -> level.getServer().execute(() -> {
					pending = false;
					if (r != null && r.ok()) announce(level, new OrderResult(true, "Cancelled after " + secs + "s unfilled", coin, 0, 0));
					updateOrderSignal(level);
				}));
			}
		}
		updateOrderSignal(level);
	}

	private void updateOrderSignal(ServerLevel level) {
		int sig = myOrderStatus().signal;
		if (sig == orderSignal) return;
		orderSignal = sig;
		if (comparatorOrder) level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
	}

	@Override
	public int comparatorOutput() {
		return comparatorOrder ? orderSignal : super.comparatorOutput();
	}

	/** Sets the coin's leverage first when it differs; completes with a failure to abort, or null to go ahead. */
	private CompletableFuture<OrderResult> applyLeverage(TradingAccount acct, int lev) {
		MarketData md = HyperCraft.market();
		MarketData.Asset a = md == null ? null : md.asset(coin);
		if (lev <= 0 || a == null || a.spot()) return CompletableFuture.completedFuture(null);
		lev = Mth.clamp(lev, 1, a.maxLeverage());
		if (acct.leverage(coin) == lev && acct.isCross(coin) == cross) return CompletableFuture.completedFuture(null);
		return acct.setLeverage(coin, lev, cross).thenApply(r -> r.ok() ? null : r);
	}

	@Override
	public JsonObject configJson() {
		JsonObject o = toRequest(null, 15).toJson();
		o.addProperty("usd", usd);
		o.addProperty("offsetPct", offsetPct);
		o.addProperty("tpPct", tpPct);
		o.addProperty("slPct", slPct);
		o.addProperty("minutes", minutes);
		o.addProperty("offsetMode", offsetMode);
		o.addProperty("absPrice", price);
		o.addProperty("cooldown", cooldownSeconds);
		o.addProperty("leverage", leverage);
		o.addProperty("cross", cross);
		o.addProperty("onRepeat", onRepeat.name());
		o.addProperty("comparatorOrder", comparatorOrder);
		o.addProperty("cancelAfter", cancelAfter);
		o.addProperty("onEvent", onEvent);
		o.addProperty("eventOnly", eventOnly);
		o.addProperty("cycle", cycle);
		o.addProperty("ticket", ticket.isEmpty() ? "" : dev.hypercraft.item.OrderTicketItem.summary(ticket));
		JsonObject ins = new JsonObject();
		for (Map.Entry<ExecutorInput, Binding> e : inputs.entrySet()) {
			JsonObject b = new JsonObject();
			b.addProperty("source", e.getValue().source.name());
			b.addProperty("lo", e.getValue().lo);
			b.addProperty("hi", e.getValue().hi);
			ins.add(e.getKey().name(), b);
		}
		o.add("inputs", ins);
		return o;
	}

	@Override
	public JsonObject screenJson() {
		JsonObject o = super.screenJson();
		com.google.gson.JsonArray rec = new com.google.gson.JsonArray();
		for (String s : recent) rec.add(s);
		o.add("recent", rec);
		// the account's own leverage on this coin, so "0 = keep" can still preview the margin a pulse ties up
		TradingAccount acct = account();
		if (acct != null && !coin.isEmpty()) o.addProperty("accountLev", acct.leverage(coin));
		if (level != null) {
			JsonObject sig = new JsonObject();
			int trig = triggerPower(level);
			for (ExecutorInput.Source s : ExecutorInput.Source.values()) {
				if (s != ExecutorInput.Source.FIXED) sig.addProperty(s.name(), signal(level, s, trig));
			}
			o.add("signals", sig);
		}
		return o;
	}

	/** From the screen: the order (written back onto the ticket, if one is in) and the machine's settings. */
	@Override
	public void applyConfig(JsonObject json) {
		cooldownSeconds = Mth.clamp((int) num(json, "cooldown", cooldownSeconds), 1, 3600);
		if (json.has("comparatorOrder")) comparatorOrder = json.get("comparatorOrder").getAsBoolean();
		if (json.has("onEvent")) onEvent = json.get("onEvent").getAsBoolean();
		if (json.has("eventOnly")) eventOnly = json.get("eventOnly").getAsBoolean();
		if (json.has("cycle")) cycle = json.get("cycle").getAsBoolean();
		applyOrder(json);
		if (!ticket.isEmpty()) ticket.set(dev.hypercraft.registry.ModComponents.ORDER_SPEC, orderJson().toString());
		sync();
	}

	/** The order's own settings, as the screen or a ticket gives them. */
	private void applyOrder(JsonObject json) {
		OrderRequest r = OrderRequest.fromJson(json);
		String resolved = HyperCraft.market() == null ? null : HyperCraft.market().resolve(r.coin);
		coin = resolved != null ? resolved : r.coin.trim();
		side = r.side;
		usd = Math.max(0, r.usd);
		// a block runs what it has settings for: the rest are the terminal's
		type = r.type.onBlocks() ? r.type : OrderRequest.Type.MARKET;
		offsetMode = !json.has("offsetMode") || json.get("offsetMode").getAsBoolean();
		price = Math.max(0, num(json, "absPrice", price));
		offsetPct = r.offsetPct;
		tif = r.tif;
		reduceOnly = r.reduceOnly;
		tpPct = r.tpPct;
		slPct = r.slPct;
		minutes = r.minutes;
		leverage = Mth.clamp((int) num(json, "leverage", leverage), 0, 100);
		if (json.has("cross")) cross = json.get("cross").getAsBoolean();
		onRepeat = Repeat.parse(str(json, "onRepeat", onRepeat.name()));
		int ca = (int) num(json, "cancelAfter", cancelAfter);
		cancelAfter = ca <= 0 ? 0 : Mth.clamp(ca, 5, 86_400);
		if (json.has("inputs")) {
			JsonObject ins = json.getAsJsonObject("inputs");
			for (ExecutorInput in : ExecutorInput.values()) {
				if (!ins.has(in.name())) continue;
				JsonObject b = ins.getAsJsonObject(in.name());
				Binding bind = inputs.get(in);
				bind.source = ExecutorInput.Source.parse(str(b, "source", "FIXED"));
				bind.lo = num(b, "lo", bind.lo);
				bind.hi = num(b, "hi", bind.hi);
			}
		}
	}

	@Override
	protected void writeConfig(CompoundTag tag, HolderLookup.Provider registries) {
		if (!signalCard.isEmpty()) tag.put("SignalCard", signalCard.save(registries));
		tag.putString("Coin", coin);
		tag.putString("Side", side.name());
		tag.putDouble("Usd", usd);
		tag.putString("Type", type.name());
		tag.putBoolean("OffsetMode", offsetMode);
		tag.putDouble("Price", price);
		tag.putDouble("OffsetPct", offsetPct);
		tag.putString("Tif", tif.name());
		tag.putBoolean("ReduceOnly", reduceOnly);
		tag.putDouble("TpPct", tpPct);
		tag.putDouble("SlPct", slPct);
		tag.putInt("Cooldown", cooldownSeconds);
		tag.putInt("Minutes", minutes);
		tag.putInt("Leverage", leverage);
		tag.putBoolean("Cross", cross);
		tag.putString("OnRepeat", onRepeat.name());
		tag.putBoolean("ComparatorOrder", comparatorOrder);
		tag.putInt("CancelAfter", cancelAfter);
		tag.putBoolean("OnEvent", onEvent);
		tag.putBoolean("EventOnly", eventOnly);
		tag.putBoolean("Cycle", cycle);
		if (!ticket.isEmpty()) tag.put("Ticket", ticket.save(registries));
		tag.putString("OwnOrder", ownOrder);
		if (myCloid != null) tag.putString("MyCloid", myCloid);
		tag.putLong("Deadline", deadline);
		tag.putInt("OrderSignal", orderSignal);
		CompoundTag ins = new CompoundTag();
		for (Map.Entry<ExecutorInput, Binding> e : inputs.entrySet()) {
			CompoundTag b = new CompoundTag();
			b.putString("Source", e.getValue().source.name());
			b.putDouble("Lo", e.getValue().lo);
			b.putDouble("Hi", e.getValue().hi);
			ins.put(e.getKey().name(), b);
		}
		tag.put("Inputs", ins);
	}

	@Override
	protected void readConfig(CompoundTag tag, HolderLookup.Provider registries) {
		signalCard = tag.contains("SignalCard") ? ItemStack.parseOptional(registries, tag.getCompound("SignalCard")) : ItemStack.EMPTY;
		if (tag.contains("Coin")) coin = tag.getString("Coin");
		if (tag.contains("Side")) side = Side.parse(tag.getString("Side"));
		if (tag.contains("Usd")) usd = tag.getDouble("Usd");
		if (tag.contains("Type")) type = OrderRequest.Type.parse(tag.getString("Type"));
		offsetMode = !tag.contains("OffsetMode") || tag.getBoolean("OffsetMode");
		price = tag.getDouble("Price");
		offsetPct = tag.getDouble("OffsetPct");
		if (tag.contains("Tif")) tif = OrderRequest.Tif.parse(tag.getString("Tif"));
		reduceOnly = tag.getBoolean("ReduceOnly");
		tpPct = tag.getDouble("TpPct");
		slPct = tag.getDouble("SlPct");
		if (tag.contains("Cooldown")) cooldownSeconds = tag.getInt("Cooldown");
		if (tag.contains("Minutes")) minutes = tag.getInt("Minutes");
		leverage = tag.getInt("Leverage");
		cross = !tag.contains("Cross") || tag.getBoolean("Cross");
		onRepeat = Repeat.parse(tag.getString("OnRepeat"));
		comparatorOrder = tag.getBoolean("ComparatorOrder");
		cancelAfter = tag.getInt("CancelAfter");
		onEvent = tag.getBoolean("OnEvent");
		eventOnly = tag.getBoolean("EventOnly");
		cycle = tag.getBoolean("Cycle");
		ticket = tag.contains("Ticket") ? ItemStack.parseOptional(registries, tag.getCompound("Ticket")) : ItemStack.EMPTY;
		ownOrder = tag.getString("OwnOrder");
		myCloid = tag.contains("MyCloid") ? tag.getString("MyCloid") : null;
		deadline = tag.getLong("Deadline");
		orderSignal = tag.getInt("OrderSignal");
		CompoundTag ins = tag.getCompound("Inputs");
		for (ExecutorInput in : ExecutorInput.values()) {
			Binding b = inputs.get(in);
			if (ins.contains(in.name())) {
				CompoundTag t = ins.getCompound(in.name());
				b.source = ExecutorInput.Source.parse(t.getString("Source"));
				b.lo = t.getDouble("Lo");
				b.hi = t.getDouble("Hi");
			}
		}
		// worlds from before inputs: "size x signal" becomes SIZE fed by trigger strength over 0..usd
		if (tag.getBoolean("Analog") && !ins.contains(ExecutorInput.SIZE.name())) {
			Binding b = inputs.get(ExecutorInput.SIZE);
			b.source = ExecutorInput.Source.TRIGGER;
			b.lo = 0;
			b.hi = usd;
		}
	}
}
