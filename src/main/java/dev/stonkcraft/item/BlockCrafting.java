package dev.stonkcraft.item;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/** Crafting from the build menu: a recipe's ingredients out of a player's inventory, without a crafting table. */
public final class BlockCrafting {
	private BlockCrafting() {}

	/** The ingredients the inventory can't cover, each stack used once; empty when it can all be made. Client and server both ask. */
	public static List<Ingredient> missing(Inventory inv, List<Ingredient> ingredients) {
		int[] left = counts(inv);
		List<Ingredient> out = new ArrayList<>();
		for (Ingredient ing : ingredients) {
			if (ing.isEmpty()) continue;
			if (findSlot(inv, ing, left) < 0) out.add(ing);
		}
		return out;
	}

	/** Takes the ingredients, leaving behind what a recipe leaves (a lava bucket's bucket). False, with nothing taken, if any is missing. */
	public static boolean take(Inventory inv, List<Ingredient> ingredients) {
		if (!missing(inv, ingredients).isEmpty()) return false;
		int[] left = counts(inv);
		List<ItemStack> leftovers = new ArrayList<>();
		for (Ingredient ing : ingredients) {
			if (ing.isEmpty()) continue;
			ItemStack stack = inv.getItem(findSlot(inv, ing, left));
			if (stack.getItem().hasCraftingRemainingItem()) leftovers.add(new ItemStack(stack.getItem().getCraftingRemainingItem()));
			stack.shrink(1);
		}
		for (ItemStack s : leftovers) if (!inv.add(s)) inv.player.drop(s, false);
		return true;
	}

	private static int[] counts(Inventory inv) {
		int[] left = new int[inv.getContainerSize()];
		for (int i = 0; i < left.length; i++) left[i] = inv.getItem(i).getCount();
		return left;
	}

	/** A slot with some of this ingredient not yet spoken for, and marks one used; -1 if there's none. */
	private static int findSlot(Inventory inv, Ingredient ing, int[] left) {
		for (int i = 0; i < left.length; i++) {
			if (left[i] > 0 && ing.test(inv.getItem(i))) {
				left[i]--;
				return i;
			}
		}
		return -1;
	}
}
