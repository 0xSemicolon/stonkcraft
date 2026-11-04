package dev.stonkcraft.item;

import dev.stonkcraft.registry.ModComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** The vault as an item; carries its account when broken so it can be moved without losing anything. */
public class VaultItem extends BlockItem {
	public VaultItem(Block block, Properties props) {
		super(block, props);
	}

	@Override
	public Component getName(ItemStack stack) {
		ModComponents.AccountRef ref = stack.get(ModComponents.ACCOUNT);
		return ref == null ? super.getName(stack) : Component.literal("Account Vault: " + ref.name()).withStyle(ChatFormatting.AQUA);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
		lines.add(Component.literal(stack.has(ModComponents.ACCOUNT)
				? "Holds this account. Place it anywhere to keep trading."
				: "Place and right-click to open a trading account").withStyle(ChatFormatting.GRAY));
	}
}
