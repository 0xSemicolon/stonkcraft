package dev.stonkcraft.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/** Sets a market's leverage from the strength of the redstone signal it receives (1-15 mapped onto a range). */
public class LeverageDialBlock extends HcBlock {
	public static final MapCodec<LeverageDialBlock> CODEC = simpleCodec(LeverageDialBlock::new);

	public LeverageDialBlock(Properties props) {
		super(props);
		registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.POWER, 0));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(BlockStateProperties.POWER);
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new LeverageDialBlockEntity(pos, state);
	}

	@Override
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos, boolean moved) {
		if (level.isClientSide) return;
		int power = level.getBestNeighborSignal(pos);
		if (power == state.getValue(BlockStateProperties.POWER)) return;
		level.setBlock(pos, state.setValue(BlockStateProperties.POWER, power), Block.UPDATE_CLIENTS);
		if (level.getBlockEntity(pos) instanceof LeverageDialBlockEntity be) be.onSignal((ServerLevel) level, power);
	}

	@Override
	public boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	/** Comparators read the leverage last applied, scaled onto 0-15 across the dial's range. */
	@Override
	public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof LeverageDialBlockEntity be ? be.comparatorOutput() : 0;
	}
}
