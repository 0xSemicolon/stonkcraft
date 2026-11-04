package dev.hypercraft.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Places an order on each rising redstone edge, like a dispenser that dispenses trades. */
public class TradeExecutorBlock extends PulseBlock {
	public static final MapCodec<TradeExecutorBlock> CODEC = simpleCodec(TradeExecutorBlock::new);

	public TradeExecutorBlock(Properties props) {
		super(props);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected int triggerPower(net.minecraft.world.level.Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof TradeExecutorBlockEntity be ? be.triggerPower(level) : super.triggerPower(level, pos);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(net.minecraft.world.level.Level level,
			BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type, dev.hypercraft.registry.ModBlocks.EXECUTOR_BE, TradeExecutorBlockEntity::serverTick);
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TradeExecutorBlockEntity(pos, state);
	}
}
