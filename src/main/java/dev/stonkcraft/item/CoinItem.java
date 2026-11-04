package dev.stonkcraft.item;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Physical paper USDC. Coins from before live trading was removed (backed by testnet or mainnet) count as paper too. */
public class CoinItem extends Item {
	public final long value;
	private final String noun;

	public CoinItem(long value, String noun, Properties props) {
		super(props);
		this.value = value;
		this.noun = noun;
	}

	@Override
	public Component getName(ItemStack stack) {
		return Component.literal(String.format(Locale.ROOT, "$%,d %s", value, noun) + " · Paper").withStyle(ChatFormatting.AQUA);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
		lines.add(Component.literal("Paper money for Stonkcraft accounts").withStyle(ChatFormatting.GRAY));
		long total = value * stack.getCount();
		if (stack.getCount() > 1) lines.add(Component.literal(String.format(Locale.ROOT, "Stack: $%,d", total)).withStyle(ChatFormatting.DARK_AQUA));
		lines.add(Component.literal("Right-click an Account Vault to deposit").withStyle(ChatFormatting.DARK_GRAY));
	}
}
