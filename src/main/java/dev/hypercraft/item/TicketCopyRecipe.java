package dev.hypercraft.item;

import dev.hypercraft.HyperCraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

/** A written Order Ticket and paper make a copy; the original stays in the grid, as a written book does. */
public class TicketCopyRecipe extends CustomRecipe {
	public static final RecipeSerializer<TicketCopyRecipe> SERIALIZER = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
			HyperCraft.id("ticket_copy"), new SimpleCraftingRecipeSerializer<>(TicketCopyRecipe::new));

	public TicketCopyRecipe(CraftingBookCategory category) {
		super(category);
	}

	/** The written ticket in the grid, if the grid holds exactly one of those and one paper. */
	private static ItemStack original(CraftingInput input) {
		ItemStack ticket = ItemStack.EMPTY;
		int paper = 0;
		for (int i = 0; i < input.size(); i++) {
			ItemStack s = input.getItem(i);
			if (s.isEmpty()) continue;
			if (OrderTicketItem.written(s) && ticket.isEmpty()) ticket = s;
			else if (s.is(Items.PAPER)) paper++;
			else return ItemStack.EMPTY;
		}
		return paper == 1 ? ticket : ItemStack.EMPTY;
	}

	@Override
	public boolean matches(CraftingInput input, Level level) {
		return !original(input).isEmpty();
	}

	@Override
	public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
		return original(input).copyWithCount(1);
	}

	@Override
	public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
		NonNullList<ItemStack> out = NonNullList.withSize(input.size(), ItemStack.EMPTY);
		for (int i = 0; i < input.size(); i++) {
			if (OrderTicketItem.written(input.getItem(i))) {
				out.set(i, input.getItem(i).copyWithCount(1));
				break;
			}
		}
		return out;
	}

	@Override
	public boolean canCraftInDimensions(int w, int h) {
		return w * h >= 2;
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return SERIALIZER;
	}

	public static void init() {}
}
