package dev.stonkcraft.block;

import dev.stonkcraft.item.AccountCardItem;
import dev.stonkcraft.net.HcNet;
import dev.stonkcraft.registry.ModComponents;
import dev.stonkcraft.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Shared behaviour: remember who placed it, right-click opens its config screen, and for trading blocks
 * an account-card slot (right-click with a card to insert, sneak + empty hand to eject).
 */
public abstract class HcBlock extends BaseEntityBlock {
	protected HcBlock(Properties props) {
		super(props);
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof HcBlockEntity be) {
			be.setOwner(player.getUUID(), player.getGameProfile().getName());
		}
	}

	@Override
	public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (stack.is(ModItems.SIGNAL_CARD) && stack.has(ModComponents.SIGNAL) && level.getBlockEntity(pos) instanceof HcBlockEntity sbe) {
			if (level.isClientSide) return ItemInteractionResult.SUCCESS;
			ServerPlayer sp = (ServerPlayer) player;
			if (!sbe.canEdit(sp)) {
				sp.displayClientMessage(Component.literal("§cThis block belongs to " + sbe.ownerName()), true);
				return ItemInteractionResult.FAIL;
			}
			ItemStack old = sbe.acceptSignalCard(stack.copyWithCount(1));
			if (old == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
			String name = stack.getHoverName().getString();
			stack.shrink(1);
			if (!old.isEmpty() && !sp.getInventory().add(old)) sp.drop(old, false);
			level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1f, 1.5f);
			sp.displayClientMessage(Component.literal("§d" + name), true);
			return ItemInteractionResult.CONSUME;
		}
		if (stack.getItem() instanceof dev.stonkcraft.item.OrderTicketItem && level.getBlockEntity(pos) instanceof HcBlockEntity tbe) {
			if (level.isClientSide) return ItemInteractionResult.SUCCESS;
			ServerPlayer sp = (ServerPlayer) player;
			if (!tbe.canEdit(sp)) {
				sp.displayClientMessage(Component.literal("§cThis block belongs to " + tbe.ownerName()), true);
				return ItemInteractionResult.FAIL;
			}
			boolean blank = !dev.stonkcraft.item.OrderTicketItem.written(stack);
			ItemStack one = stack.copyWithCount(1);
			ItemStack back = tbe.acceptTicket(one);
			if (back == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
			stack.shrink(1);
			if (!back.isEmpty() && !sp.getInventory().add(back)) sp.drop(back, false);
			level.playSound(null, pos, blank ? SoundEvents.BOOK_PAGE_TURN : SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1f, 1.3f);
			sp.displayClientMessage(Component.literal("§b" + (blank ? "Wrote the order on a ticket: " : "Loaded ") + dev.stonkcraft.item.OrderTicketItem.summary(blank ? back : one)), true);
			return ItemInteractionResult.CONSUME;
		}
		if (!AccountCardItem.isCard(stack)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!(level.getBlockEntity(pos) instanceof HcBlockEntity be) || !be.usesCard()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (level.isClientSide) return ItemInteractionResult.SUCCESS;
		ServerPlayer sp = (ServerPlayer) player;
		if (!be.canEdit(sp)) {
			sp.displayClientMessage(Component.literal("§cThis block belongs to " + be.ownerName()), true);
			return ItemInteractionResult.FAIL;
		}
		ItemStack one = AccountCardItem.normalized(stack.copyWithCount(1));
		// a block that trades turns a Watch Card away at the slot, not later when it fires
		if (be.needsTradeCard() && AccountCardItem.isWatch(one)) {
			sp.displayClientMessage(Component.literal("§eA Watch Card only looks: this block trades, so it needs a Trade Card"), true);
			level.playSound(null, pos, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 0.6f, 1.2f);
			return ItemInteractionResult.FAIL;
		}
		stack.shrink(1);
		ItemStack old = be.swapCard(one);
		if (!old.isEmpty() && !sp.getInventory().add(old)) sp.drop(old, false);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1f, 1.2f);
		sp.displayClientMessage(Component.literal("§aLinked to " + be.cardRef().name()), true);
		return ItemInteractionResult.CONSUME;
	}

	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof HcBlockEntity be) {
			ItemStack first;
			if (!be.canEdit(sp)) {
				sp.displayClientMessage(Component.literal("§cThis block belongs to " + be.ownerName()), true);
			} else if (sp.isShiftKeyDown() && !(first = be.ejectFirst()).isEmpty()) {
				// an executor's ticket comes out before its card
				if (!sp.getInventory().add(first)) sp.drop(first, false);
				level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1f, 1.2f);
			} else if (sp.isShiftKeyDown() && be.usesCard() && !be.card().isEmpty()) {
				ItemStack old = be.swapCard(ItemStack.EMPTY);
				if (!sp.getInventory().add(old)) sp.drop(old, false);
				level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1f, 1f);
			} else {
				HcNet.send(sp, "open_" + be.screen(), be.screenJson());
			}
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof HcBlockEntity be) {
			if (!be.card().isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), be.card());
			for (ItemStack extra : be.extraDrops()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), extra);
			if (!level.isClientSide) be.removeTap();
		}
		super.onRemove(state, level, pos, newState, moved);
	}
}
