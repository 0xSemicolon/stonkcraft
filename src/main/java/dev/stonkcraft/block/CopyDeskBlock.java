package dev.stonkcraft.block;

import com.mojang.serialization.MapCodec;
import dev.stonkcraft.net.HcNet;
import dev.stonkcraft.registry.ModBlocks;
import dev.stonkcraft.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

/** Copy trading: right-click with the follower's trade card and a leader's card, or type an address on its screen. */
public class CopyDeskBlock extends HcBlock {
	public static final MapCodec<CopyDeskBlock> CODEC = simpleCodec(CopyDeskBlock::new);

	public CopyDeskBlock(Properties props) {
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
		boolean card = dev.stonkcraft.item.AccountCardItem.isCard(stack) || stack.is(ModItems.SIGNAL_CARD);
		if (!card || !(level.getBlockEntity(pos) instanceof CopyDeskBlockEntity be)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (level.isClientSide) return ItemInteractionResult.SUCCESS;
		ServerPlayer sp = (ServerPlayer) player;
		if (!be.canEdit(sp)) {
			sp.displayClientMessage(Component.literal("§cThis desk belongs to " + be.ownerName()), true);
			return ItemInteractionResult.FAIL;
		}
		boolean wasEmpty = be.card().isEmpty();
		ItemStack old = be.insert(stack);
		if (!old.isEmpty() && !sp.getInventory().add(old)) sp.drop(old, false);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1f, 1.2f);
		sp.displayClientMessage(Component.literal(wasEmpty && !be.card().isEmpty() ? "§bFollower: " + be.cardRef().name() : "§bLeader set"), true);
		return ItemInteractionResult.CONSUME;
	}

	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof CopyDeskBlockEntity be) {
			if (!be.canEdit(sp)) {
				sp.displayClientMessage(Component.literal("§cThis desk belongs to " + be.ownerName()), true);
			} else {
				if (be.owner() == null) be.setOwner(sp.getUUID(), sp.getGameProfile().getName());
				HcNet.send(sp, "open_copy_desk", be.info());
			}
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.is(newState.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof CopyDeskBlockEntity be) be.dropContents(level, pos);
		super.onRemove(state, level, pos, newState, moved);
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof CopyDeskBlockEntity be ? be.comparatorOutput() : 0;
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new CopyDeskBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type, ModBlocks.COPY_DESK_BE, CopyDeskBlockEntity::serverTick);
	}
}
