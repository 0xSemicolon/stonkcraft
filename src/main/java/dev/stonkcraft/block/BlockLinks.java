package dev.stonkcraft.block;

import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.trading.Account;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Stonkcraft blocks that touch share an account: from a block, through any chain of Stonkcraft blocks touching face to
 * face, to the nearest Account Vault with an account. A vault ends the chain, so two vaults make two groups.
 */
public final class BlockLinks {
	/** How far a search walks, so a huge build can't cost a server tick. */
	static final int MAX_BLOCKS = 64;

	private BlockLinks() {}

	/** The nearest position that {@code isTarget}, walking from {@code start} through positions that {@code conducts}. */
	@Nullable
	static <P> P nearest(P start, Function<P, ? extends Iterable<P>> neighbours, Predicate<P> isTarget, Predicate<P> conducts) {
		Set<P> seen = new HashSet<>(List.of(start));
		ArrayDeque<P> queue = new ArrayDeque<>(List.of(start));
		while (!queue.isEmpty() && seen.size() <= MAX_BLOCKS) {
			for (P next : neighbours.apply(queue.poll())) {
				if (!seen.add(next)) continue;
				if (isTarget.test(next)) return next;
				if (conducts.test(next)) queue.add(next);
			}
		}
		return null;
	}

	/** The vault this block is linked to. Works on either side: a client knows a vault's account and name too. */
	@Nullable
	public static AccountVaultBlockEntity vault(@Nullable Level level, BlockPos pos) {
		if (level == null) return null;
		BlockPos found = nearest(pos, BlockLinks::around,
				p -> level.isLoaded(p) && level.getBlockEntity(p) instanceof AccountVaultBlockEntity v && v.accountId != null,
				p -> level.isLoaded(p) && Stonkcraft.MOD_ID.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getNamespace()));
		return found != null && level.getBlockEntity(found) instanceof AccountVaultBlockEntity v ? v : null;
	}

	/** The account this block is linked to, on the server. Whether someone may use it is for the caller to check. */
	@Nullable
	public static Account account(@Nullable Level level, BlockPos pos) {
		AccountVaultBlockEntity v = vault(level, pos);
		return v == null ? null : v.accountData();
	}

	private static List<BlockPos> around(BlockPos pos) {
		List<BlockPos> out = new ArrayList<>(6);
		for (Direction d : Direction.values()) out.add(pos.relative(d));
		return out;
	}
}
