package dev.stonkcraft.block;

import com.google.gson.JsonObject;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public class TickerDisplayBlockEntity extends HcBlockEntity {
	public String coin = "xyz:GOLD";

	public TickerDisplayBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.TICKER_BE, pos, state);
	}

	@Override
	public String screen() {
		return "ticker";
	}

	@Override
	public JsonObject configJson() {
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		return o;
	}

	@Override
	public void applyConfig(JsonObject json) {
		coin = str(json, "coin", coin).trim();
		String resolved = Stonkcraft.market() == null ? null : Stonkcraft.market().resolve(coin);
		if (resolved != null) coin = resolved;
		sync();
	}

	@Override
	protected void writeConfig(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putString("Coin", coin);
	}

	@Override
	protected void readConfig(CompoundTag tag, HolderLookup.Provider registries) {
		if (tag.contains("Coin")) coin = tag.getString("Coin");
	}
}
