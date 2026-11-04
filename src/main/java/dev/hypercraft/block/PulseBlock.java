package dev.hypercraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** A block that acts once per rising redstone edge, like a dispenser. Comparators read its last outcome. */
public abstract class PulseBlock extends HcBlock {
	/** Implemented by the block entity. */
	public interface Target {
		void onPulse(ServerLevel level, int power);

		/** 15 after success, 1 after a failure, 0 before the first pulse. */
		int comparatorOutput();
	}

	protected PulseBlock(Properties props) {
		super(props);
		registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.TRIGGERED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(BlockStateProperties.TRIGGERED);
	}

	@Override
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos, boolean moved) {
		if (level.isClientSide) return;
		int power = triggerPower(level, pos);
		boolean powered = power > 0;
		if (powered == state.getValue(BlockStateProperties.TRIGGERED)) return;
		level.setBlock(pos, state.setValue(BlockStateProperties.TRIGGERED, powered), Block.UPDATE_CLIENTS);
		if (powered && level.getBlockEntity(pos) instanceof Target t) t.onPulse((ServerLevel) level, power);
	}

	/** The signal that fires this block; subclasses can leave some faces out (e.g. faces used as inputs). */
	protected int triggerPower(Level level, BlockPos pos) {
		return level.getBestNeighborSignal(pos);
	}

	@Override
	public boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof Target t ? t.comparatorOutput() : 0;
	}
}
