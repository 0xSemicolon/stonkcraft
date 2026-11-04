package dev.hypercraft.block;

import com.mojang.serialization.MapCodec;
import dev.hypercraft.net.HcNet;
import dev.hypercraft.registry.ModBlocks;
import dev.hypercraft.registry.ModItems;
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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Combines signals. Right-click with account or Signal Cards to add inputs; empty-handed to configure. Outputs redstone. */
public class SignalMixerBlock extends HcBlock {
	public static final MapCodec<SignalMixerBlock> CODEC = simpleCodec(SignalMixerBlock::new);

	public SignalMixerBlock(Properties props) {
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

	@Override
	public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		boolean card = dev.hypercraft.item.AccountCardItem.isCard(stack) || stack.is(ModItems.SIGNAL_CARD);
		if (!card || !(level.getBlockEntity(pos) instanceof SignalMixerBlockEntity be)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (level.isClientSide) return ItemInteractionResult.SUCCESS;
		ServerPlayer sp = (ServerPlayer) player;
		if (!be.canEdit(sp)) {
			sp.displayClientMessage(Component.literal("§cThis mixer belongs to " + be.ownerName()), true);
			return ItemInteractionResult.FAIL;
		}
		String err = be.addInput(sp, stack);
		if (err != null) {
			sp.displayClientMessage(Component.literal("§c" + err), true);
			return ItemInteractionResult.FAIL;
		}
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1f, 1.4f);
		sp.displayClientMessage(Component.literal("§dInput " + be.inputCards.size() + " added"), true);
		return ItemInteractionResult.CONSUME;
	}

	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof SignalMixerBlockEntity be) {
			if (!be.canEdit(sp)) {
				sp.displayClientMessage(Component.literal("§cThis mixer belongs to " + be.ownerName()), true);
			} else {
				if (be.owner() == null) be.setOwner(sp.getUUID(), sp.getGameProfile().getName());
				HcNet.send(sp, "open_signal_mixer", be.info());
			}
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.is(newState.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof SignalMixerBlockEntity be) be.dropContents(level, pos);
		super.onRemove(state, level, pos, newState, moved);
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SignalMixerBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type, ModBlocks.SIGNAL_MIXER_BE, SignalMixerBlockEntity::serverTick);
	}

	@Override
	public boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return state.getValue(BlockStateProperties.POWER);
	}

	@Override
	public boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		return state.getValue(BlockStateProperties.POWER);
	}
}
