package dev.hypercraft;

import dev.hypercraft.command.HlCommand;
import dev.hypercraft.config.HyperConfig;
import dev.hypercraft.hl.HyperliquidApi;
import dev.hypercraft.market.MarketData;
import dev.hypercraft.net.HcNet;
import dev.hypercraft.net.ServerNet;
import dev.hypercraft.registry.ModBlocks;
import dev.hypercraft.registry.ModComponents;
import dev.hypercraft.registry.ModItems;
import dev.hypercraft.trading.AccountRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HyperCraft implements ModInitializer {
	public static final String MOD_ID = "hypercraft";
	public static final Logger LOG = LoggerFactory.getLogger("HyperCraft");

	@Nullable
	private static MarketData market;
	@Nullable
	private static AccountRegistry accounts;
	@Nullable
	private static dev.hypercraft.signal.SignalRegistry signals;

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	@Nullable
	public static MarketData market() {
		return market;
	}

	@Nullable
	public static AccountRegistry accounts() {
		return accounts;
	}

	@Nullable
	public static dev.hypercraft.signal.SignalRegistry signals() {
		return signals;
	}

	@Override
	public void onInitialize() {
		HyperConfig.load();
		ModComponents.init();
		ModItems.init();
		ModBlocks.init();
		ItemStorage.SIDED.registerForBlockEntity((vault, dir) -> vault.coinInput, ModBlocks.VAULT_BE);
		ItemStorage.SIDED.registerForBlockEntity((bridge, dir) -> bridge.metalInput, ModBlocks.UNIT_BRIDGE_BE);
		ItemStorage.SIDED.registerForBlockEntity((executor, dir) -> executor.ticketInput, ModBlocks.EXECUTOR_BE);
		dev.hypercraft.item.TicketCopyRecipe.init();
		HcNet.registerTypes();
		ServerNet.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) -> HlCommand.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			HyperConfig.load();
			HyperConfig cfg = HyperConfig.get();
			market = new MarketData(new HyperliquidApi(cfg.apiBase()));
			dev.hypercraft.unit.UnitConfig.load();
			dev.hypercraft.unit.UnitBridge.bindTags();
			java.util.List<String> metals = dev.hypercraft.unit.UnitConfig.get().addModMetals();
			if (!metals.isEmpty()) LOG.info("UNIT.XYZ also takes mod metals: {}", String.join(", ", metals));
			market.setUnits(dev.hypercraft.unit.UnitConfig.get().markets());
			market.start(cfg.marketPollMillis, cfg.websocket ? cfg.apiBase() : null);
			accounts = new AccountRegistry(server, () -> market);
			signals = new dev.hypercraft.signal.SignalRegistry(server, accounts);
			dev.hypercraft.game.Speedruns.load(server);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (signals != null) signals.save();
			signals = null;
			if (accounts != null) accounts.stop();
			if (market != null) market.stop();
			market = null;
			accounts = null;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int t = server.getTickCount();
			if (t % 20 == 0) {
				if (accounts != null) accounts.tick();
				if (signals != null) signals.tick();
				ServerNet.tick(server);
			}
			if (t % 1200 == 0 && accounts != null) accounts.save();
		});

		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon && entity.level().getServer() != null) {
				dev.hypercraft.game.Speedruns.dragonKilled(entity.level().getServer(), entity.level());
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (accounts == null) return;
			var p = handler.getPlayer();
			accounts.onJoin(p);
			p.sendSystemMessage(Component.literal("§b[Stonkcraft] §7Paper trading on live Hyperliquid prices. Press §fH§7 to build blocks, §f/hl accounts§7 for your accounts."));
		});
	}
}
