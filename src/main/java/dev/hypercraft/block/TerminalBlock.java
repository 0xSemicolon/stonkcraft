package dev.hypercraft.block;

import com.google.gson.JsonObject;
import dev.hypercraft.net.HcNet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Right-click to open the trading terminal, the one place to trade by hand: with an account card, on that account;
 * touching an Account Vault, directly or through other HyperCraft blocks, on the vault's account.
 */
public class TerminalBlock extends Block {
	public TerminalBlock(Properties props) {
		super(props);
		registerDefaultState(stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
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

	/** With an account card in hand it opens the terminal on that card's account. */
	@Override
	protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
		var ref = stack.get(dev.hypercraft.registry.ModComponents.ACCOUNT);
		if (ref == null || ref.id() == null || ref.id().isEmpty()) return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!level.isClientSide && player instanceof ServerPlayer sp) {
			JsonObject o = new JsonObject();
			o.addProperty("account", ref.id());
			o.addProperty("pos", pos.asLong());
			dev.hypercraft.net.ServerNet.openedTerminal(sp, pos);
			HcNet.send(sp, "open_terminal", o);
		}
		return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide && player instanceof ServerPlayer sp) {
			JsonObject o = new JsonObject();
			o.addProperty("pos", pos.asLong());
			var linked = BlockLinks.account(level, pos);
			var reg = dev.hypercraft.HyperCraft.accounts();
			// someone else's vault doesn't open for you: you get your own account, as anywhere
			if (linked != null && reg != null && reg.canView(sp, linked)) o.addProperty("account", linked.id.toString());
			dev.hypercraft.net.ServerNet.openedTerminal(sp, pos);
			HcNet.send(sp, "open_terminal", o);
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}
}
