package dev.hypercraft.client;

import dev.hypercraft.HyperCraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Dev driver for {@code ./gradlew runClient -Pshowroom}: a small flat world that other dev drivers ({@link UiTour}) build
 * in, next to spawn. {@code -Pshots} puts every market logo on a wall of Ticker Displays and one of each block in a row,
 * screenshots them, the terminal, the build menu and the ticker tape, and quits.
 */
public final class Showroom {
	public static final boolean ENABLED = Boolean.getBoolean("hypercraft.showroom");
	private static final boolean SHOTS = Boolean.getBoolean("hypercraft.showroom.shots");

	private static int ticks;
	private static BlockPos origin = BlockPos.ZERO;

	private Showroom() {}

	/** The world is loaded and {@link #origin} (the spawn point) is known. */
	public static boolean ready() {
		return ENABLED && ticks > 40;
	}

	public static BlockPos origin() {
		return origin;
	}

	public static void tick(Minecraft mc) {
		if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null) return;
		MinecraftServer server = mc.getSingleplayerServer();
		ticks++;
		if (ticks == 40) origin = server.overworld().getSharedSpawnPos().atY(mc.player.blockPosition().getY() - 1);
		if (!SHOTS || ticks < 80) return;
		int t = ticks - 80;
		if (t == 0) {
			run(server, "gamemode creative @a");
			run(server, "time set 6000");
			server.execute(() -> wall(server));
		}
		String[][] stops = {
				{"showroom_logos", "0 6.5 -12", "0 6.5 -20"},
				{"showroom_blocks", "0 2.6 -4", "0 1.4 -14"},
				{"showroom_blocks_close", "-6 1.9 -11", "-6 1.5 -14"},
		};
		int stop = t / 100, phase = t % 100;
		if (stop < stops.length) {
			if (phase == 1) {
				mc.options.hideGui = true;
				String[] s = stops[stop];
				run(server, "tp @a " + rel(s[1]) + " facing " + rel(s[2]));
			}
			if (phase == 95) Screenshot.grab(mc.gameDirectory, stops[stop][0] + ".png", mc.getMainRenderTarget(), msg -> {});
			return;
		}
		// last, the screens: the market list, chart title and tape all carry logos
		if (phase == 1) mc.options.hideGui = false;
		if (phase == 20) mc.setScreen(new dev.hypercraft.client.screen.TerminalScreen());
		if (phase == 80) Screenshot.grab(mc.gameDirectory, "showroom_terminal.png", mc.getMainRenderTarget(), msg -> {});
		if (phase == 82) mc.setScreen(new dev.hypercraft.client.screen.BuildMenuScreen());
		if (phase == 90) Screenshot.grab(mc.gameDirectory, "showroom_build_menu.png", mc.getMainRenderTarget(), msg -> {});
		if (phase == 92) mc.setScreen(null);
		if (phase == 98) Screenshot.grab(mc.gameDirectory, "showroom_tape.png", mc.getMainRenderTarget(), msg -> {});
		if (phase == 99) mc.stop();
	}

	/** "x y z" relative to the origin, as absolute coordinates for a command. */
	private static String rel(String xyz) {
		String[] p = xyz.split(" ");
		return String.format(Locale.ROOT, "%.1f %.1f %.1f", origin.getX() + Double.parseDouble(p[0]) + 0.5,
				origin.getY() + Double.parseDouble(p[1]), origin.getZ() + Double.parseDouble(p[2]) + 0.5);
	}

	/** Every market logo on a Ticker Display (14 across), and below it one of each block, front-on, facing south. */
	private static void wall(MinecraftServer server) {
		ServerLevel level = server.overworld();
		List<String> logos = new ArrayList<>();
		Minecraft.getInstance().getResourceManager().listResources("textures/logo", r -> r.getPath().endsWith(".png")).keySet().forEach(r -> {
			String n = r.getPath().substring(r.getPath().lastIndexOf('/') + 1).replace(".png", "");
			if (!n.startsWith("_")) logos.add(n.toUpperCase(Locale.ROOT));
		});
		logos.sort(null);
		int y0 = origin.getY() + 1, z = origin.getZ() - 20;
		for (int x = -8; x <= 8; x++) for (int y = 0; y < 12; y++) level.setBlock(origin.offset(x, y + 1, -21), Blocks.BLACK_CONCRETE.defaultBlockState(), 2);
		for (int i = 0; i < logos.size(); i++) {
			BlockPos p = new BlockPos(origin.getX() - 7 + i % 14, y0 + 10 - i / 14, z);
			level.setBlock(p, dev.hypercraft.registry.ModBlocks.TICKER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH), 2);
			if (level.getBlockEntity(p) instanceof dev.hypercraft.block.HcBlockEntity be) {
				com.google.gson.JsonObject o = new com.google.gson.JsonObject();
				String coin = logos.get(i);
				o.addProperty("coin", dev.hypercraft.unit.UnitConfig.get().token(coin) != null ? coin + "/USDC" : coin);
				be.applyConfig(o);
			}
		}
		HyperCraft.LOG.info("Showroom: {} logos", logos.size());
		String[][] row = {{"trading_terminal", ""}, {"ticker_display", "BTC"}, {"account_display", ""}, {"account_vault", ""}, {"unit_bridge", ""},
				{"market_sensor", "ETH"}, {"trade_executor", "SOL"}, {"leverage_dial", "HYPE"}, {"kill_switch", ""}, {"signal_mixer", ""}, {"copy_desk", ""}, {"ticker_display", "xyz:GOLD"}};
		for (int i = 0; i < row.length; i++) {
			BlockPos p = origin.offset(-11 + i * 2, 1, -14);
			var st = BuiltInRegistries.BLOCK.get(HyperCraft.id(row[i][0])).defaultBlockState();
			if (st.hasProperty(HorizontalDirectionalBlock.FACING)) st = st.setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH);
			level.setBlock(p.below(), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 2);
			level.setBlock(p, st, 2);
			if (!row[i][1].isEmpty() && level.getBlockEntity(p) instanceof dev.hypercraft.block.HcBlockEntity be) {
				com.google.gson.JsonObject o = new com.google.gson.JsonObject();
				o.addProperty("coin", row[i][1]);
				be.applyConfig(o);
			}
		}
		for (int x = -13; x <= 13; x += 3) level.setBlock(origin.offset(x, 4, -11), Blocks.SEA_LANTERN.defaultBlockState(), 2);
	}

	private static void run(MinecraftServer server, String cmd) {
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd));
	}
}
