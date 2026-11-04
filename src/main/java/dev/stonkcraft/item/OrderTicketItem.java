package dev.stonkcraft.item;

import dev.stonkcraft.registry.ModComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * An order written down: coin, side, type, size, TP/SL, TWAP, and where each of those comes from. A Trade Executor
 * holding one places that order; the ticket carries no account and no authority, so copying or losing one is
 * harmless. Blank, it copies the order of the executor it's used on.
 */
public class OrderTicketItem extends Item {
	public OrderTicketItem(Properties props) {
		super(props);
	}

	public static boolean written(ItemStack s) {
		return s.getItem() instanceof OrderTicketItem && s.has(ModComponents.ORDER_SPEC);
	}

	/** The one-line summary written with the order, e.g. "Market BUY $50 BTC". */
	public static String summary(ItemStack s) {
		var spec = s.get(ModComponents.ORDER_SPEC);
		if (spec == null) return "";
		try {
			var o = com.google.gson.JsonParser.parseString(spec).getAsJsonObject();
			return o.has("summary") ? o.get("summary").getAsString() : "";
		} catch (RuntimeException e) {
			return "";
		}
	}

	@Override
	public Component getName(ItemStack stack) {
		if (!written(stack)) return Component.literal("Blank Order Ticket");
		return Component.literal("Order Ticket: " + summary(stack)).withStyle(ChatFormatting.AQUA);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
		if (!written(stack)) {
			lines.add(Component.literal("Right-click a Trade Executor to write its order on it").withStyle(ChatFormatting.GRAY));
			return;
		}
		lines.add(Component.literal("Right-click a Trade Executor to load it; sneak with an empty hand to take it back").withStyle(ChatFormatting.GRAY));
		lines.add(Component.literal("Craft with paper to copy it. Carries no account: harmless to hand out").withStyle(ChatFormatting.DARK_GRAY));
	}
}
