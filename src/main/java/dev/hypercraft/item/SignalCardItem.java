package dev.hypercraft.item;

import dev.hypercraft.registry.ModComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Carries a signal from a Signal Mixer to anything that reads one. Whoever holds it can read the signal's values. */
public class SignalCardItem extends Item {
	public SignalCardItem(Properties props) {
		super(props);
	}

	@Override
	public Component getName(ItemStack stack) {
		ModComponents.SignalRef ref = stack.get(ModComponents.SIGNAL);
		return ref == null ? super.getName(stack) : Component.literal("Signal · " + ref.name()).withStyle(ChatFormatting.LIGHT_PURPLE);
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return stack.has(ModComponents.SIGNAL);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
		if (!stack.has(ModComponents.SIGNAL)) {
			lines.add(Component.literal("Print one at a Signal Mixer").withStyle(ChatFormatting.GRAY));
			return;
		}
		lines.add(Component.literal("Insert into a Signal Mixer, Copy Desk, Market Sensor,").withStyle(ChatFormatting.GRAY));
		lines.add(Component.literal("Trade Executor or Account Display").withStyle(ChatFormatting.GRAY));
		lines.add(Component.literal("Reads the signal's values; can't trade").withStyle(ChatFormatting.DARK_GRAY));
	}
}
