package dev.hypercraft.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.hypercraft.client.render.FloatingLabelRenderer;
import dev.hypercraft.client.render.TickerDisplayRenderer;
import dev.hypercraft.client.render.TickerTapeHud;
import dev.hypercraft.client.render.VaultRenderer;
import dev.hypercraft.client.screen.VaultScreen;
import dev.hypercraft.client.screen.VaultSetupScreen;
import dev.hypercraft.client.screen.ExecutorScreen;
import dev.hypercraft.client.screen.KillSwitchScreen;
import dev.hypercraft.client.screen.LeverageDialScreen;
import dev.hypercraft.client.screen.SensorScreen;
import dev.hypercraft.client.screen.TerminalScreen;
import dev.hypercraft.client.screen.TickerScreen;
import dev.hypercraft.net.HcNet;
import dev.hypercraft.registry.ModBlocks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class HyperCraftClient implements ClientModInitializer {

	private static KeyMapping toggleTape;
	/** H: the build menu, for making blocks anywhere. Trading stays at a Trading Terminal. */
	public static KeyMapping openBuild;
	private static boolean welcomePending;
	private static final boolean WELCOME_SHOT = Boolean.getBoolean("hypercraft.welcomeShot");
	private static int welcomeTicks;

	@Override
	public void onInitializeClient() {
		ClientConfig.load();
		dev.hypercraft.client.ui.UiLint.install();

		toggleTape = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.hypercraft.tape", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.hypercraft"));
		openBuild = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.hypercraft.build", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.hypercraft"));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			DevDemo.tick(mc);
			Showroom.tick(mc);
			UiTour.tick(mc);
			if (welcomePending && mc.player != null && mc.screen == null) {
				welcomePending = false;
				mc.setScreen(new dev.hypercraft.client.screen.WelcomeScreen());
			}
			// -Pwelcomeshot: screenshot the welcome screen and quit (dev only)
			if (WELCOME_SHOT && (welcomeTicks > 0 || mc.screen instanceof dev.hypercraft.client.screen.WelcomeScreen)) {
				welcomeTicks++;
				if (welcomeTicks == 60) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "welcome.png", mc.getMainRenderTarget(), msg -> {});
				if (welcomeTicks == 660) mc.stop(); // 30 s more for server-side checks
			}
			// the terminal is a place: walk away from it and it closes
			if (mc.screen instanceof TerminalScreen && ClientState.terminalPos != null && mc.player != null
					&& mc.player.distanceToSqr(net.minecraft.core.BlockPos.of(ClientState.terminalPos).getCenter()) > dev.hypercraft.net.ServerNet.TERMINAL_REACH * dev.hypercraft.net.ServerNet.TERMINAL_REACH) {
				mc.setScreen(null);
				mc.player.displayClientMessage(Component.literal("You walked away from the Trading Terminal"), true);
			}
			while (openBuild.consumeClick()) {
				if (mc.player != null && mc.screen == null) mc.setScreen(new dev.hypercraft.client.screen.BuildMenuScreen());
			}
			while (toggleTape.consumeClick()) {
				ClientConfig cfg = ClientConfig.get();
				cfg.tape = !cfg.tape;
				ClientConfig.save();
				if (mc.player != null) mc.player.displayClientMessage(Component.literal("Ticker tape " + (cfg.tape ? "on" : "off")), true);
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(HcNet.ToClient.TYPE, (payload, ctx) -> ctx.client().execute(() -> handle(ctx.client(), payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientState::reset));
		// first world only, and never during the scripted demo, tour and self-check runs
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> welcomePending = WELCOME_SHOT || !ClientConfig.get().welcomed
				&& System.getProperty("hypercraft.demo") == null && !Showroom.ENABLED);

		BlockEntityRenderers.register(ModBlocks.TICKER_BE, TickerDisplayRenderer::new);
		BlockEntityRenderers.register(ModBlocks.EXECUTOR_BE, dev.hypercraft.client.render.ExecutorFaceRenderer::new);
		BlockEntityRenderers.register(ModBlocks.ACCOUNT_DISPLAY_BE, dev.hypercraft.client.render.AccountDisplayRenderer::new);
		BlockEntityRenderers.register(ModBlocks.VAULT_BE, VaultRenderer::new);
		BlockEntityRenderers.register(ModBlocks.SENSOR_BE, FloatingLabelRenderer::new);
		BlockEntityRenderers.register(ModBlocks.KILL_SWITCH_BE, FloatingLabelRenderer::new);
		BlockEntityRenderers.register(ModBlocks.LEVERAGE_DIAL_BE, FloatingLabelRenderer::new);
		BlockEntityRenderers.register(ModBlocks.UNIT_BRIDGE_BE, FloatingLabelRenderer::new);
		BlockEntityRenderers.register(ModBlocks.SIGNAL_MIXER_BE, FloatingLabelRenderer::new);
		BlockEntityRenderers.register(ModBlocks.COPY_DESK_BE, FloatingLabelRenderer::new);

		HudRenderCallback.EVENT.register(TickerTapeHud::render);
	}

	private static void handle(Minecraft mc, HcNet.ToClient payload) {
		JsonObject body = payload.body();
		switch (payload.kind()) {
			case "market" -> ClientState.onMarket(body);
			case "portfolio" -> ClientState.portfolio = body;
			case "accounts" -> {
				ClientState.accounts = body.getAsJsonArray("accounts");
				ClientState.canBuild = body.get("canBuild").getAsBoolean();
				ClientState.faucet = body.has("faucet") && body.get("faucet").getAsBoolean();
				ClientState.faucetUsd = body.has("faucetUsd") ? body.get("faucetUsd").getAsDouble() : 0;
			}
			case "access" -> ClientState.access = body;
			case "speedrun" -> ClientState.speedrun = body.has("start") ? body : null;
			case "vault_result" -> {
				ClientState.lastVaultOk = body.get("ok").getAsBoolean();
				ClientState.lastVaultMsg = body.get("msg").getAsString();
				ClientState.lastVaultAt = System.currentTimeMillis();
				if (mc.screen == null) mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(ClientState.lastVaultMsg)
						.withStyle(ClientState.lastVaultOk ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED), false);
			}
			case "open_vault" -> mc.setScreen(new VaultScreen(body));
			case "open_vault_setup" -> mc.setScreen(new VaultSetupScreen(body));
			case "close_screen" -> mc.setScreen(null);
			case "candles" -> ClientState.candles = body;
			case "book" -> ClientState.book = body;
			case "funding_history" -> ClientState.fundingHistory = body;
			case "history" -> ClientState.history = body;
			case "order_result" -> {
				ClientState.lastOrderOk = body.get("ok").getAsBoolean();
				ClientState.lastOrderMsg = body.get("msg").getAsString();
				ClientState.lastOrderAt = System.currentTimeMillis();
			}
			case "open_terminal" -> {
				// used with an account card: trade that account
				if (body.has("account")) {
					JsonObject o = new JsonObject();
					o.addProperty("id", body.get("account").getAsString());
					ClientNet.send("select_account", o);
				}
				ClientState.terminalPos = body.has("pos") ? body.get("pos").getAsLong() : null;
				mc.setScreen(new TerminalScreen());
			}
			case "open_sensor" -> mc.setScreen(new SensorScreen(body));
			case "open_executor" -> mc.setScreen(new ExecutorScreen(body));
			case "open_ticker" -> mc.setScreen(new TickerScreen(body));
			case "open_account_display" -> mc.setScreen(new dev.hypercraft.client.screen.AccountDisplayScreen(body));
			case "open_kill_switch" -> mc.setScreen(new KillSwitchScreen(body));
			case "open_leverage_dial" -> mc.setScreen(new LeverageDialScreen(body));
			case "open_unit_bridge" -> mc.setScreen(new dev.hypercraft.client.screen.UnitBridgeScreen(body));
			case "unit_info" -> dev.hypercraft.client.screen.UnitBridgeScreen.info = body;
			case "open_signal_mixer" -> mc.setScreen(new dev.hypercraft.client.screen.SignalMixerScreen(body));
			case "mixer_info" -> {
				if (mc.screen instanceof dev.hypercraft.client.screen.SignalMixerScreen s) s.onInfo(body);
			}
			case "open_copy_desk" -> mc.setScreen(new dev.hypercraft.client.screen.CopyDeskScreen(body));
			case "desk_info" -> dev.hypercraft.client.screen.CopyDeskScreen.info = body;
			case "block_result" -> {
				boolean ok = body.get("ok").getAsBoolean();
				String msg = body.get("msg").getAsString();
				if (mc.screen instanceof dev.hypercraft.client.screen.BlockConfigScreen s) s.onResult(body.get("pos").getAsLong(), ok, msg);
				// screens of their own (the copy desk) show it as a toast
				else if (mc.screen instanceof dev.hypercraft.client.ui.HcScreen h) h.toast((ok ? "✔ " : "✘ ") + msg, ok ? dev.hypercraft.client.ui.Ui.GREEN : dev.hypercraft.client.ui.Ui.RED);
			}
			default -> {}
		}
	}
}
