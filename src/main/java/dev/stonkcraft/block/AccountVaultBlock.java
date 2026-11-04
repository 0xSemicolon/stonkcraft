package dev.stonkcraft.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.item.CoinItem;
import dev.stonkcraft.item.Coins;
import dev.stonkcraft.net.HcNet;
import dev.stonkcraft.registry.ModBlocks;
import dev.stonkcraft.registry.ModComponents;
import dev.stonkcraft.registry.ModItems;
import dev.stonkcraft.trading.Account;
import dev.stonkcraft.trading.AccountRegistry;
import dev.stonkcraft.trading.Fmt;
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

/**
 * An account in block form. Right-click with coins to deposit, with a blank card to issue a card,
 * empty-handed to open it. Breaking it keeps the account on the dropped item.
 */
public class AccountVaultBlock extends HcBlock {
	public static final MapCodec<AccountVaultBlock> CODEC = simpleCodec(AccountVaultBlock::new);

	public AccountVaultBlock(Properties props) {
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

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new AccountVaultBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type, ModBlocks.VAULT_BE, AccountVaultBlockEntity::serverTick);
	}

	@Override
	public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		boolean coin = stack.getItem() instanceof CoinItem;
		boolean blankCard = stack.is(ModItems.ACCOUNT_CARD) && !stack.has(ModComponents.ACCOUNT);
		if (!coin && !blankCard) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (level.isClientSide) return ItemInteractionResult.SUCCESS;
		if (!(player instanceof ServerPlayer sp) || !(level.getBlockEntity(pos) instanceof AccountVaultBlockEntity vault)) {
			return ItemInteractionResult.FAIL;
		}
		AccountRegistry reg = Stonkcraft.accounts();
		Account a = vault.accountData();
		if (reg == null || a == null) {
			sp.displayClientMessage(Component.literal("§eRight-click the vault empty-handed to open an account first"), true);
			return ItemInteractionResult.FAIL;
		}
		return coin ? deposit(sp, hand, stack, reg, a, vault) : issueCard(sp, stack, reg, a, level, pos);
	}

	private ItemInteractionResult deposit(ServerPlayer sp, InteractionHand hand, ItemStack stack, AccountRegistry reg, Account a, AccountVaultBlockEntity vault) {
		long usd = Coins.value(stack, a.backing());
		if (usd == 0) {
			sp.displayClientMessage(Component.literal("§c" + a.name + " is watch-only: it doesn't take coins"), true);
			return ItemInteractionResult.FAIL;
		}
		ItemStack taken = stack.copy();
		sp.setItemInHand(hand, ItemStack.EMPTY);
		String err = reg.deposit(a, usd);
		if (err != null) {
			if (!sp.getInventory().add(taken)) sp.drop(taken, false);
			sp.displayClientMessage(Component.literal("§c" + err), true);
			return ItemInteractionResult.FAIL;
		}
		sp.level().playSound(null, vault.getBlockPos(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1f, 1.2f);
		sp.displayClientMessage(Component.literal("§aDeposited " + Fmt.usd(usd) + " into " + a.name), true);
		vault.bind(a);
		return ItemInteractionResult.CONSUME;
	}

	private ItemInteractionResult issueCard(ServerPlayer sp, ItemStack stack, AccountRegistry reg, Account a, Level level, BlockPos pos) {
		if (!reg.canManage(sp, a)) {
			sp.displayClientMessage(Component.literal("§cOnly " + a.ownerName + " can issue cards for " + a.name), true);
			return ItemInteractionResult.FAIL;
		}
		stack.shrink(1);
		// sneaking prints a Watch Card; a watch-only account only ever gets those
		boolean watch = sp.isShiftKeyDown() || a.kind == Account.Kind.WATCH;
		ItemStack card = dev.stonkcraft.net.ServerNet.cardItem(reg, a, watch ? "READ" : "TRADE", "");
		reg.logEvent(a, sp, "Printed a " + (watch ? "Watch" : "Trade") + " Card at the vault");
		if (!sp.getInventory().add(card)) sp.drop(card, false);
		level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 1.4f);
		sp.displayClientMessage(Component.literal(watch ? "§9Printed a Watch Card for " + a.name + ": sensors, displays, mixers and copy-desk leaders read it"
				: "§6Printed a Trade Card for " + a.name + ": executors, kill switches and dials trade with it. Sneak for a Watch Card"), true);
		return ItemInteractionResult.CONSUME;
	}

	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide) return InteractionResult.SUCCESS;
		if (!(player instanceof ServerPlayer sp) || !(level.getBlockEntity(pos) instanceof AccountVaultBlockEntity vault)) return InteractionResult.PASS;
		AccountRegistry reg = Stonkcraft.accounts();
		if (reg == null) return InteractionResult.PASS;
		Account a = vault.accountData();
		if (a == null) {
			if (!vault.canEdit(sp)) {
				sp.displayClientMessage(Component.literal("§cThis vault belongs to " + vault.ownerName()), true);
				return InteractionResult.SUCCESS;
			}
			HcNet.send(sp, "open_vault_setup", setupJson(sp, reg, pos));
		} else if (reg.canView(sp, a)) {
			reg.select(sp, a.id);
			JsonObject o = new JsonObject();
			o.addProperty("pos", pos.asLong());
			o.addProperty("id", a.id.toString());
			o.addProperty("owner", a.owner.equals(sp.getUUID()) || sp.hasPermissions(2));
			HcNet.send(sp, "open_vault", o);
		} else {
			sp.displayClientMessage(Component.literal("§7" + a.name + " §8(" + a.ownerName + "'s account). You can still deposit coins."), true);
		}
		return InteractionResult.SUCCESS;
	}

	private static JsonObject setupJson(ServerPlayer sp, AccountRegistry reg, BlockPos pos) {
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos.asLong());
		JsonArray mine = new JsonArray();
		for (Account a : reg.owned(sp.getUUID())) {
			JsonObject m = new JsonObject();
			m.addProperty("id", a.id.toString());
			m.addProperty("name", a.name);
			m.addProperty("label", a.label());
			m.addProperty("tag", reg.tag(a));
			mine.add(m);
		}
		o.add("mine", mine);
		return o;
	}
}
