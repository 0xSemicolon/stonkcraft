package dev.stonkcraft.item;

import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.registry.ModComponents;
import dev.stonkcraft.registry.ModItems;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * A card for one account, in two kinds. A Trade Card acts on the account: executors, kill switches, leverage dials,
 * a copy desk's follower slot and Commodity Exchange take only these. A Watch Card only looks: sensors, displays, mixer inputs
 * and a copy desk's leader slot take either kind. Unprinted, the trade item is the Blank Card both are printed on.
 * The account's own record of the card decides what it may do; the item says so at a glance.
 */
public class AccountCardItem extends Item {
	private final boolean watch;

	public AccountCardItem(Properties props, boolean watch) {
		super(props);
		this.watch = watch;
	}

	@Override
	public Component getName(ItemStack stack) {
		ModComponents.AccountRef ref = stack.get(ModComponents.ACCOUNT);
		if (ref == null) return Component.literal(watch ? "Watch Card" : "Blank Card");
		return Component.literal((watch ? "Watch Card: " : "Trade Card: ") + ref.name()).withStyle(watch ? ChatFormatting.BLUE : ChatFormatting.GOLD);
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return stack.has(ModComponents.ACCOUNT);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
		if (!stack.has(ModComponents.ACCOUNT)) {
			lines.add(Component.literal("Right-click an Account Vault you own to print a Trade Card on it; sneak to print a Watch Card").withStyle(ChatFormatting.GRAY));
			return;
		}
		ModComponents.AccountRef ref = stack.get(ModComponents.ACCOUNT);
		if (watch) {
			lines.add(Component.literal("Read-only: shows the account, never trades").withStyle(ChatFormatting.BLUE));
			lines.add(Component.literal("For Market Sensors, Account Displays, Signal Mixers and a Copy Desk's leader").withStyle(ChatFormatting.GRAY));
		} else {
			lines.add(Component.literal(ref.cardId().isEmpty() && ref.level().isEmpty() ? "Old card: reprint it from the vault" : "Trades the account")
					.withStyle(ChatFormatting.GOLD));
			lines.add(Component.literal("For Trade Executors, Kill Switches, Leverage Dials, Commodity Exchange and a Copy Desk's follower; works anywhere a Watch Card does")
					.withStyle(ChatFormatting.GRAY));
		}
		String scope = stack.get(ModComponents.CARD_SCOPE);
		if (scope != null && !scope.isEmpty()) lines.add(Component.literal("Limits: " + scope).withStyle(ChatFormatting.YELLOW));
		lines.add(Component.literal("Only works in blocks whose owner has access; the owner can revoke it").withStyle(ChatFormatting.DARK_GRAY));
	}

	/** Old read cards were printed on the trade item: they turn into Watch Cards in your inventory. */
	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
		if (level.isClientSide || watch || !(entity instanceof Player p) || level.getGameTime() % 20 != slot % 20) return;
		ItemStack fixed = normalized(stack);
		if (fixed != stack) p.getInventory().setItem(slot, fixed);
	}

	/** Is this a printed Trade or Watch Card (not a blank)? */
	public static boolean isCard(ItemStack s) {
		return (s.is(ModItems.ACCOUNT_CARD) || s.is(ModItems.WATCH_CARD)) && s.has(ModComponents.ACCOUNT);
	}

	public static boolean isWatch(ItemStack s) {
		return s.is(ModItems.WATCH_CARD);
	}

	/**
	 * The stack as the card it really is: a card printed on the trade item at READ level, or for a watch-only
	 * account, comes back as a Watch Card. Anything else comes back unchanged (the same instance).
	 */
	public static ItemStack normalized(ItemStack s) {
		if (!s.is(ModItems.ACCOUNT_CARD)) return s;
		ModComponents.AccountRef ref = s.get(ModComponents.ACCOUNT);
		if (ref == null || !(ref.readOnly() || watchAccount(ref))) return s;
		ItemStack w = new ItemStack(ModItems.WATCH_CARD, s.getCount());
		w.applyComponents(s.getComponentsPatch());
		return w;
	}

	private static boolean watchAccount(ModComponents.AccountRef ref) {
		var reg = Stonkcraft.accounts();
		var a = reg == null || ref.uuid() == null ? null : reg.get(ref.uuid());
		return a != null && a.kind == dev.stonkcraft.trading.Account.Kind.WATCH;
	}
}
