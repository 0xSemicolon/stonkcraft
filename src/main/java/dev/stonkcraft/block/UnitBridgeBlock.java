package dev.stonkcraft.block;

import com.mojang.serialization.MapCodec;
import dev.stonkcraft.net.HcNet;
import dev.stonkcraft.registry.ModBlocks;
import dev.stonkcraft.unit.UnitBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** UNIT[XYZ]: deposit metal (right-click with it; sneak for everything you carry), open empty-handed to withdraw. */
public class UnitBridgeBlock extends HcBlock {
	public static final MapCodec<UnitBridgeBlock> CODEC = simpleCodec(UnitBridgeBlock::new);

	public UnitBridgeBlock(Properties props) {
		super(props);
		registerDefaultState(stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HorizontalDirectionalBlock.FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, ctx.getHorizontalDirection().getOpposite());
	}

	@Override
	public BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(HorizontalDirectionalBlock.FACING, rotation.rotate(state.getValue(HorizontalDirectionalBlock.FACING)));
	}

	@Override
	public BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(HorizontalDirectionalBlock.FACING)));
	}

	@Override
	public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (!UnitBridge.bridgeable(stack) || hand != InteractionHand.MAIN_HAND) return super.useItemOn(stack, state, level, pos, player, hand, hit);
		if (level.isClientSide) return ItemInteractionResult.SUCCESS;
		if (level.getBlockEntity(pos) instanceof UnitBridgeBlockEntity be) be.depositFrom((ServerPlayer) player, player.isShiftKeyDown());
		return ItemInteractionResult.CONSUME;
	}

	/** Anyone may use a bridge (it's an ATM); only its owner swaps the card or changes its mode. */
	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof UnitBridgeBlockEntity be) {
			if (sp.isShiftKeyDown() && be.canEdit(sp) && !be.card().isEmpty()) return super.useWithoutItem(state, level, pos, player, hit);
			if (sp.isShiftKeyDown()) {
				be.depositFrom(sp, true);
			} else {
				HcNet.send(sp, "open_unit_bridge", be.info(sp));
			}
			if (be.owner() == null) be.setOwner(sp.getUUID(), sp.getGameProfile().getName());
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof UnitBridgeBlockEntity be ? be.comparatorOutput() : 0;
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new UnitBridgeBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type, ModBlocks.UNIT_BRIDGE_BE, UnitBridgeBlockEntity::serverTick);
	}
}
