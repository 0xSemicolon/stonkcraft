package dev.hypercraft.item;

import dev.hypercraft.registry.ModComponents;
import dev.hypercraft.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/** Change-making between dollar amounts and coin stacks. */
public final class Coins {
	private Coins() {}

	/** Largest first. */
	public static List<CoinItem> denominations() {
		return List.of((CoinItem) ModItems.BAR, (CoinItem) ModItems.NOTE, (CoinItem) ModItems.CHIP);
	}

	/** Greedy split of whole dollars into stacks (64 max each). */
	public static List<ItemStack> make(long dollars, String backing) {
		List<ItemStack> out = new ArrayList<>();
		long remaining = dollars;
		for (CoinItem coin : denominations()) {
			long n = remaining / coin.value;
			remaining -= n * coin.value;
			while (n > 0) {
				int take = (int) Math.min(64, n);
				out.add(stack(coin, take, backing));
				n -= take;
			}
		}
		return out;
	}

	public static ItemStack stack(CoinItem coin, int count, String backing) {
		ItemStack s = new ItemStack(coin, count);
		s.set(ModComponents.BACKING, backing);
		return s;
	}

	/** Dollar value of a stack going into an account with this backing; only paper accounts take coins. */
	public static long value(ItemStack stack, String backing) {
		if (!(stack.getItem() instanceof CoinItem coin)) return 0;
		return "paper".equals(backing) ? coin.value * stack.getCount() : 0;
	}

	/** Pure change-making, exposed for tests: counts of [bars, notes, chips]. */
	public static long[] split(long dollars) {
		long bars = dollars / 10_000;
		long notes = (dollars % 10_000) / 100;
		long chips = dollars % 100;
		return new long[]{bars, notes, chips};
	}
}
