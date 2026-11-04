package dev.hypercraft.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** On a redstone pulse: cancel open orders (and optionally flatten positions) on one market or all of them. */
public class KillSwitchBlock extends PulseBlock {
	public static final MapCodec<KillSwitchBlock> CODEC = simpleCodec(KillSwitchBlock::new);

	public KillSwitchBlock(Properties props) {
		super(props);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new KillSwitchBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(net.minecraft.world.level.Level level,
			BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type, dev.hypercraft.registry.ModBlocks.KILL_SWITCH_BE, KillSwitchBlockEntity::serverTick);
	}
}
