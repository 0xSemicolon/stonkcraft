package dev.hypercraft.block;

import com.google.gson.JsonObject;
import dev.hypercraft.HyperCraft;
import dev.hypercraft.registry.ModBlocks;
import dev.hypercraft.trading.OrderResult;
import dev.hypercraft.trading.TradingAccount;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

public class LeverageDialBlockEntity extends HcBlockEntity {
	public String coin = "BTC";
	public int minLev = 1;
	// a new dial tops out at a modest 5x; players raise it on purpose
	public int maxLev = 2;
	public boolean cross = true;
	/** Leverage most recently applied, 0 if none yet. */
	public int applied;

	private boolean pending;
	private int queuedPower = -1;

	public LeverageDialBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.LEVERAGE_DIAL_BE, pos, state);
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
		return "leverage_dial";
	}

	/** Signal 1..15 mapped linearly onto minLev..maxLev. */
	public int leverageFor(int power) {
		if (power <= 0) return 0;
		int lo = Math.min(minLev, maxLev), hi = Math.max(minLev, maxLev);
		return lo + Math.round((hi - lo) * (power - 1) / 14f);
	}

	public int comparatorOutput() {
		int lo = Math.min(minLev, maxLev), hi = Math.max(minLev, maxLev);
		if (applied <= 0 || hi == lo) return applied > 0 ? 15 : 0;
		return Mth.clamp(1 + Math.round((applied - lo) * 14f / (hi - lo)), 1, 15);
	}

	public void onSignal(ServerLevel level, int power) {
		int lev = leverageFor(power);
		if (lev <= 0 || lev == applied) return;
		if (pending) {
			queuedPower = power; // apply the latest setting once the in-flight change lands
			return;
		}
		OrderResult missing = tradeReason();
		if (missing != null) {
			announce(level, missing);
			return;
		}
		TradingAccount acct = tradingAccount();
		pending = true;
		acct.setLeverage(coin, lev, cross).whenComplete((r, err) -> level.getServer().execute(() -> {
			pending = false;
			OrderResult res = err != null ? OrderResult.fail(coin, String.valueOf(err.getMessage())) : r;
			if (res.ok()) applied = lev;
			announce(level, res);
			if (queuedPower >= 0) {
				int q = queuedPower;
				queuedPower = -1;
				onSignal(level, q);
			}
		}));
	}

	public String describe() {
		return coin + " " + minLev + "-" + maxLev + "x " + (cross ? "cross" : "iso") + (applied > 0 ? " → " + applied + "x" : "");
	}

	/** Adds the live input and the leverage last set, so the screen can mark where the dial sits now. */
	@Override
	public JsonObject screenJson() {
		JsonObject o = super.screenJson();
		o.addProperty("power", level == null ? 0 : level.getBestNeighborSignal(worldPosition));
		o.addProperty("applied", applied);
		return o;
	}

	@Override
	public JsonObject configJson() {
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		o.addProperty("min", minLev);
		o.addProperty("max", maxLev);
		o.addProperty("cross", cross);
		return o;
	}

	@Override
	public void applyConfig(JsonObject json) {
		String c = str(json, "coin", coin).trim();
		String resolved = HyperCraft.market() == null ? null : HyperCraft.market().resolve(c);
		coin = resolved != null ? resolved : c;
		minLev = Mth.clamp((int) num(json, "min", minLev), 1, 200);
		maxLev = Mth.clamp((int) num(json, "max", maxLev), 1, 200);
		cross = !json.has("cross") || json.get("cross").getAsBoolean();
		applied = 0;
		sync();
	}

	@Override
	protected void writeConfig(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putString("Coin", coin);
		tag.putInt("Min", minLev);
		tag.putInt("Max", maxLev);
		tag.putBoolean("Cross", cross);
		tag.putInt("Applied", applied);
	}

	@Override
	protected void readConfig(CompoundTag tag, HolderLookup.Provider registries) {
		if (tag.contains("Coin")) coin = tag.getString("Coin");
		if (tag.contains("Min")) minLev = tag.getInt("Min");
		if (tag.contains("Max")) maxLev = tag.getInt("Max");
		cross = !tag.contains("Cross") || tag.getBoolean("Cross");
		applied = tag.getInt("Applied");
	}
}
