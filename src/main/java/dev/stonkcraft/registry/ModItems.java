package dev.stonkcraft.registry;

import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.item.AccountCardItem;
import dev.stonkcraft.item.CoinItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

public final class ModItems {
	private ModItems() {}

	public static final Item CHIP = register("usdc_chip", new CoinItem(1, "Chip", new Item.Properties()));
	public static final Item NOTE = register("usdc_note", new CoinItem(100, "Note", new Item.Properties()));
	public static final Item BAR = register("usdc_bar", new CoinItem(10_000, "Bar", new Item.Properties().rarity(Rarity.UNCOMMON)));
	/** Blank, or printed as a Trade Card. */
	public static final Item ACCOUNT_CARD = register("account_card", new AccountCardItem(new Item.Properties().stacksTo(16), false));
	public static final Item WATCH_CARD = register("watch_card", new AccountCardItem(new Item.Properties().stacksTo(16), true));
	public static final Item SIGNAL_CARD = register("signal_card", new dev.stonkcraft.item.SignalCardItem(new Item.Properties().stacksTo(16)));
	/** Blank, or with an order written on it for a Trade Executor. */
	public static final Item ORDER_TICKET = register("order_ticket", new dev.stonkcraft.item.OrderTicketItem(new Item.Properties().stacksTo(16)));

	private static Item register(String name, Item item) {
		return Registry.register(BuiltInRegistries.ITEM, Stonkcraft.id(name), item);
	}

	public static void init() {}
}
