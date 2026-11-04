package dev.hypercraft.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hypercraft.HyperCraft;
import dev.hypercraft.registry.ModBlocks;
import dev.hypercraft.trading.OrderResult;
import dev.hypercraft.trading.Side;
import dev.hypercraft.trading.TradingAccount;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public class KillSwitchBlockEntity extends HcBlockEntity implements PulseBlock.Target {
	/** Market to act on; empty = every market. */
	public String coin = "";
	public enum Mode {
		CANCEL("Cancel orders"), PANIC("Cancel + close"), DEADMAN("Dead man's switch");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	public Mode mode = Mode.CANCEL;
	/** Dead man's switch: how far ahead each heartbeat pushes the cancel deadline. */
	public int windowSeconds = 60;
	private long lastBeat;
	private boolean beating;
	/** Signal Card whose events can fire the switch, e.g. a leader's fills or a sensor's reading going off. */
	public ItemStack signalCard = ItemStack.EMPTY;
	/** Fire on each new event from the Signal Card. */
	public boolean onEvent;
	/** With onEvent: redstone no longer fires it. */
	public boolean eventOnly;
	private final dev.hypercraft.signal.EventWatch events = new dev.hypercraft.signal.EventWatch();

	@Override
	@org.jetbrains.annotations.Nullable
	public ItemStack acceptSignalCard(ItemStack one) {
		ItemStack old = signalCard;
		signalCard = one;
		sync();
		return old;
	}

	@Override
	public java.util.List<ItemStack> extraDrops() {
		return signalCard.isEmpty() ? java.util.List.of() : java.util.List.of(signalCard);
	}

	public KillSwitchBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.KILL_SWITCH_BE, pos, state);
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
		return "kill_switch";
	}

	public String describe() {
		return switch (mode) {
			case CANCEL -> "Cancel orders " + (coin.isEmpty() ? "ALL" : coin);
			case PANIC -> "PANIC: cancel + close " + (coin.isEmpty() ? "ALL" : coin);
			case DEADMAN -> "Dead man's switch " + windowSeconds + "s";
		};
	}

	/**
	 * Dead man's mode: while powered, renew Hyperliquid's schedule-cancel deadline; if power (or the server)
	 * goes away, the deadline passes and every open order is cancelled.
	 */
	public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, KillSwitchBlockEntity be) {
		if (be.onEvent && be.mode != Mode.DEADMAN && level.getGameTime() % 10 == 0 && level instanceof ServerLevel sl
				&& be.events.next(signalValue(be.signalCard.isEmpty() ? null : be.signalCard.get(dev.hypercraft.registry.ModComponents.SIGNAL)))) {
			be.fire(sl);
		}
		if (be.mode != Mode.DEADMAN || level.getGameTime() % 20 != 0) return;
		boolean powered = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.TRIGGERED);
		long now = System.currentTimeMillis();
		int window = Math.max(10, be.windowSeconds);
		if (!powered || be.beating || now - be.lastBeat < window * 1000L / 3) return;
		TradingAccount acct = be.tradingAccount();
		if (acct == null) return;
		be.beating = true;
		be.lastBeat = now;
		ServerLevel sl = (ServerLevel) level;
		acct.scheduleCancel(now + window * 1000L).whenComplete((r, err) -> sl.getServer().execute(() -> {
			be.beating = false;
			OrderResult res = err != null ? OrderResult.fail("", String.valueOf(err.getMessage())) : r;
			if (!res.ok() || be.lastStatus != 1) be.announce(sl, res);
			else {
				be.lastMessage = res.message();
				be.sync();
			}
		}));
	}

	@Override
	public void onPulse(ServerLevel level, int power) {
		if (mode == Mode.DEADMAN) {
			lastBeat = 0; // renew straight away on power-up
			return;
		}
		if (onEvent && eventOnly) return;
		fire(level);
	}

	private void fire(ServerLevel level) {
		boolean flatten = mode == Mode.PANIC;
		OrderResult missing = tradeReason();
		if (missing != null) {
			announce(level, missing);
			return;
		}
		TradingAccount acct = tradingAccount();
		String target = coin.isEmpty() ? null : coin;
		CompletableFuture<OrderResult> cancel = acct.cancelAll(target);
		List<CompletableFuture<OrderResult>> closes = new ArrayList<>();
		if (flatten) {
			for (JsonElement el : acct.snapshot().getAsJsonArray("positions")) {
				String c = el.getAsJsonObject().get("coin").getAsString();
				if (target == null || c.equals(target)) closes.add(acct.submit(c, Side.CLOSE, 0));
			}
		}
		List<CompletableFuture<OrderResult>> all = new ArrayList<>(closes);
		all.add(cancel);
		CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).whenComplete((v, err) -> level.getServer().execute(() -> {
			int closed = 0;
			for (CompletableFuture<OrderResult> f : closes) if (!f.isCompletedExceptionally() && f.join().ok()) closed++;
			OrderResult c = cancel.isCompletedExceptionally() ? OrderResult.fail(coin, "cancel failed") : cancel.join();
			String msg = c.message() + (flatten ? ", closed " + closed + " position(s)" : "");
			announce(level, new OrderResult(c.ok() || closed > 0, msg, coin, 0, 0));
		}));
	}

	@Override
	public JsonObject configJson() {
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		o.addProperty("mode", mode.name());
		o.addProperty("window", windowSeconds);
		o.addProperty("onEvent", onEvent);
		o.addProperty("eventOnly", eventOnly);
		o.addProperty("signal", signalCard.isEmpty() ? "" : signalCard.getHoverName().getString());
		return o;
	}

	/** Adds what a pulse would hit right now, per market, for the screen's dry run. */
	@Override
	public JsonObject screenJson() {
		JsonObject o = super.screenJson();
		TradingAccount acct = account();
		if (acct == null) return o;
		JsonObject orders = new JsonObject();
		com.google.gson.JsonArray positions = new com.google.gson.JsonArray();
		JsonObject snap = acct.snapshot();
		if (snap.has("orders")) {
			for (JsonElement el : snap.getAsJsonArray("orders")) {
				String c = el.getAsJsonObject().get("coin").getAsString();
				orders.addProperty(c, (orders.has(c) ? orders.get(c).getAsInt() : 0) + 1);
			}
		}
		for (var e : acct.perpPositions().entrySet()) if (e.getValue()[0] != 0) positions.add(e.getKey());
		o.add("atRiskOrders", orders);
		o.add("atRiskPositions", positions);
		return o;
	}

	@Override
	public void applyConfig(JsonObject json) {
		String c = str(json, "coin", "").trim();
		String resolved = c.isEmpty() || HyperCraft.market() == null ? null : HyperCraft.market().resolve(c);
		coin = resolved != null ? resolved : c;
		try {
			mode = Mode.valueOf(str(json, "mode", mode.name()));
		} catch (IllegalArgumentException e) {
			mode = Mode.CANCEL;
		}
		windowSeconds = Math.max(10, Math.min(3600, (int) num(json, "window", windowSeconds)));
		if (json.has("onEvent")) onEvent = json.get("onEvent").getAsBoolean();
		if (json.has("eventOnly")) eventOnly = json.get("eventOnly").getAsBoolean();
		sync();
	}

	@Override
	protected void writeConfig(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putString("Coin", coin);
		tag.putString("Mode", mode.name());
		tag.putInt("Window", windowSeconds);
		tag.putBoolean("OnEvent", onEvent);
		tag.putBoolean("EventOnly", eventOnly);
		if (!signalCard.isEmpty()) tag.put("SignalCard", signalCard.save(registries));
	}

	@Override
	protected void readConfig(CompoundTag tag, HolderLookup.Provider registries) {
		coin = tag.getString("Coin");
		try {
			mode = tag.contains("Mode") ? Mode.valueOf(tag.getString("Mode")) : tag.getBoolean("Flatten") ? Mode.PANIC : Mode.CANCEL;
		} catch (IllegalArgumentException e) {
			mode = Mode.CANCEL;
		}
		if (tag.contains("Window")) windowSeconds = tag.getInt("Window");
		onEvent = tag.getBoolean("OnEvent");
		eventOnly = tag.getBoolean("EventOnly");
		signalCard = tag.contains("SignalCard") ? ItemStack.parseOptional(registries, tag.getCompound("SignalCard")) : ItemStack.EMPTY;
	}
}
