package dev.hypercraft.registry;

import dev.hypercraft.HyperCraft;
import dev.hypercraft.block.AccountDisplayBlock;
import dev.hypercraft.block.AccountDisplayBlockEntity;
import dev.hypercraft.block.AccountVaultBlock;
import dev.hypercraft.block.AccountVaultBlockEntity;
import dev.hypercraft.block.KillSwitchBlock;
import dev.hypercraft.block.KillSwitchBlockEntity;
import dev.hypercraft.block.LeverageDialBlock;
import dev.hypercraft.block.LeverageDialBlockEntity;
import dev.hypercraft.block.MarketSensorBlock;
import dev.hypercraft.item.CoinItem;
import dev.hypercraft.item.Coins;
import dev.hypercraft.item.VaultItem;
import dev.hypercraft.block.MarketSensorBlockEntity;
import dev.hypercraft.block.TerminalBlock;
import dev.hypercraft.block.TickerDisplayBlock;
import dev.hypercraft.block.TickerDisplayBlockEntity;
import dev.hypercraft.block.TradeExecutorBlock;
import dev.hypercraft.block.TradeExecutorBlockEntity;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.MapColor;

public final class ModBlocks {
	private ModBlocks() {}

	public static final Block TERMINAL = block("trading_terminal", new TerminalBlock(props().lightLevel(s -> 6)));
	public static final Block SENSOR = block("market_sensor",
			new MarketSensorBlock(props().lightLevel(s -> s.getValue(BlockStateProperties.POWER) > 0 ? 9 : 2)));
	public static final Block EXECUTOR = block("trade_executor",
			new TradeExecutorBlock(props().lightLevel(s -> s.getValue(BlockStateProperties.TRIGGERED) ? 9 : 3)));
	public static final Block TICKER = block("ticker_display", new TickerDisplayBlock(props().lightLevel(s -> 10)));
	public static final Block ACCOUNT_DISPLAY = block("account_display", new AccountDisplayBlock(props().lightLevel(s -> 10)));
	public static final Block VAULT = vault();
	public static final Block KILL_SWITCH = block("kill_switch",
			new KillSwitchBlock(props().lightLevel(s -> s.getValue(BlockStateProperties.TRIGGERED) ? 10 : 3)));
	public static final Block LEVERAGE_DIAL = block("leverage_dial",
			new LeverageDialBlock(props().lightLevel(s -> 3 + s.getValue(BlockStateProperties.POWER) / 3)));
	public static final Block UNIT_BRIDGE = block("unit_bridge",
			new dev.hypercraft.block.UnitBridgeBlock(props().mapColor(MapColor.GOLD).strength(4.0f, 1200f).lightLevel(s -> 7)));

	public static final Block SIGNAL_MIXER = block("signal_mixer",
			new dev.hypercraft.block.SignalMixerBlock(props().mapColor(MapColor.COLOR_PURPLE).lightLevel(s -> 4 + s.getValue(BlockStateProperties.POWER) / 2)));
	public static final Block COPY_DESK = block("copy_desk", new dev.hypercraft.block.CopyDeskBlock(props().mapColor(MapColor.COLOR_LIGHT_BLUE).lightLevel(s -> 6)));

	public static final BlockEntityType<MarketSensorBlockEntity> SENSOR_BE = blockEntity("market_sensor",
			BlockEntityType.Builder.of(MarketSensorBlockEntity::new, SENSOR).build(null));
	public static final BlockEntityType<TradeExecutorBlockEntity> EXECUTOR_BE = blockEntity("trade_executor",
			BlockEntityType.Builder.of(TradeExecutorBlockEntity::new, EXECUTOR).build(null));
	public static final BlockEntityType<AccountVaultBlockEntity> VAULT_BE = blockEntity("account_vault",
			BlockEntityType.Builder.of(AccountVaultBlockEntity::new, VAULT).build(null));
	public static final BlockEntityType<KillSwitchBlockEntity> KILL_SWITCH_BE = blockEntity("kill_switch",
			BlockEntityType.Builder.of(KillSwitchBlockEntity::new, KILL_SWITCH).build(null));
	public static final BlockEntityType<LeverageDialBlockEntity> LEVERAGE_DIAL_BE = blockEntity("leverage_dial",
			BlockEntityType.Builder.of(LeverageDialBlockEntity::new, LEVERAGE_DIAL).build(null));
	public static final BlockEntityType<AccountDisplayBlockEntity> ACCOUNT_DISPLAY_BE = blockEntity("account_display",
			BlockEntityType.Builder.of(AccountDisplayBlockEntity::new, ACCOUNT_DISPLAY).build(null));
	public static final BlockEntityType<dev.hypercraft.block.UnitBridgeBlockEntity> UNIT_BRIDGE_BE = blockEntity("unit_bridge",
			BlockEntityType.Builder.of(dev.hypercraft.block.UnitBridgeBlockEntity::new, UNIT_BRIDGE).build(null));
	public static final BlockEntityType<dev.hypercraft.block.SignalMixerBlockEntity> SIGNAL_MIXER_BE = blockEntity("signal_mixer",
			BlockEntityType.Builder.of(dev.hypercraft.block.SignalMixerBlockEntity::new, SIGNAL_MIXER).build(null));
	public static final BlockEntityType<dev.hypercraft.block.CopyDeskBlockEntity> COPY_DESK_BE = blockEntity("copy_desk",
			BlockEntityType.Builder.of(dev.hypercraft.block.CopyDeskBlockEntity::new, COPY_DESK).build(null));
	public static final BlockEntityType<TickerDisplayBlockEntity> TICKER_BE = blockEntity("ticker_display",
			BlockEntityType.Builder.of(TickerDisplayBlockEntity::new, TICKER).build(null));

	public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, HyperCraft.id("main"),
			FabricItemGroup.builder()
					.icon(() -> new ItemStack(TERMINAL))
					.title(Component.translatable("itemGroup.hypercraft"))
					.displayItems((params, out) -> {
						out.accept(VAULT);
						out.accept(UNIT_BRIDGE);
						out.accept(new ItemStack(ModItems.ACCOUNT_CARD));
						out.accept(new ItemStack(ModItems.ORDER_TICKET));
						out.accept(TERMINAL);
						out.accept(TICKER);
						out.accept(ACCOUNT_DISPLAY);
						out.accept(SENSOR);
						out.accept(EXECUTOR);
						out.accept(LEVERAGE_DIAL);
						out.accept(KILL_SWITCH);
						out.accept(SIGNAL_MIXER);
						out.accept(COPY_DESK);
						for (CoinItem coin : Coins.denominations()) out.accept(Coins.stack(coin, 1, "paper"));
					})
					.build());

	private static BlockBehaviour.Properties props() {
		return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(2.0f, 6.0f).sound(SoundType.COPPER);
	}

	private static Block vault() {
		Block block = Registry.register(BuiltInRegistries.BLOCK, HyperCraft.id("account_vault"),
				new AccountVaultBlock(props().strength(4.0f, 1200f).lightLevel(s -> 5)));
		Registry.register(BuiltInRegistries.ITEM, HyperCraft.id("account_vault"), new VaultItem(block, new Item.Properties().stacksTo(1)));
		return block;
	}

	private static Block block(String name, Block block) {
		Registry.register(BuiltInRegistries.BLOCK, HyperCraft.id(name), block);
		Registry.register(BuiltInRegistries.ITEM, HyperCraft.id(name), new BlockItem(block, new Item.Properties()));
		return block;
	}

	private static <T extends BlockEntityType<?>> T blockEntity(String name, T type) {
		return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, HyperCraft.id(name), type);
	}

	public static void init() {}
}
