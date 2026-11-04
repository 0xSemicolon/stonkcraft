package dev.stonkcraft.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.trading.Account;
import dev.stonkcraft.trading.AccountRegistry;
import dev.stonkcraft.trading.Fmt;
import dev.stonkcraft.trading.TradingAccount;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Wall Street speedruns: {@code /stonk speedrun start} times a run from now to the Ender Dragon's death. While a run is
 * on, the paper mint is closed to that player, so the money has to come from the ground (Commodity Exchange) and from trading.
 */
public final class Speedruns {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Run {
		public UUID player;
		public String name = "";
		public long start;
		public double startNetWorth;
		public double endNetWorth;
		public long end;
		/** Value bridged in and out at Commodity Exchanges during the run. */
		public double bridgedIn, bridgedOut;
	}

	private static final class Saved {
		Map<String, Run> active = new HashMap<>();
		List<Run> finished = new ArrayList<>();
	}

	private static Saved saved = new Saved();
	@Nullable
	private static Path file;

	private Speedruns() {}

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("stonkcraft_speedruns.json");
		saved = new Saved();
		try {
			if (Files.exists(file)) {
				Saved s = GSON.fromJson(Files.readString(file), Saved.class);
				if (s != null) saved = s;
			}
		} catch (Exception e) {
			Stonkcraft.LOG.warn("Couldn't read speedruns", e);
		}
		if (saved.active == null) saved.active = new HashMap<>();
		if (saved.finished == null) saved.finished = new ArrayList<>();
	}

	private static void save() {
		if (file == null) return;
		try {
			Files.writeString(file, GSON.toJson(saved));
		} catch (Exception e) {
			Stonkcraft.LOG.warn("Couldn't save speedruns", e);
		}
	}

	@Nullable
	public static Run active(UUID player) {
		return saved.active.get(player.toString());
	}

	/** Total equity across the accounts a player owns. */
	public static double netWorth(UUID player) {
		AccountRegistry reg = Stonkcraft.accounts();
		if (reg == null) return 0;
		double sum = 0;
		for (Account a : reg.owned(player)) {
			if (a.kind != Account.Kind.PAPER) continue;
			TradingAccount t = reg.trading(a.id);
			if (t != null && Double.isFinite(t.equity())) sum += t.equity();
		}
		return sum;
	}

	public static String start(ServerPlayer p) {
		Run r = new Run();
		r.player = p.getUUID();
		r.name = p.getGameProfile().getName();
		r.start = System.currentTimeMillis();
		r.startNetWorth = netWorth(p.getUUID());
		saved.active.put(p.getUUID().toString(), r);
		save();
		return "§6Speedrun started§7: mine, deposit, trade, cash out, beat the dragon. The paper mint is closed to you until it ends. Net worth now §f"
				+ Fmt.usd(r.startNetWorth);
	}

	public static String stop(ServerPlayer p) {
		Run r = saved.active.remove(p.getUUID().toString());
		save();
		return r == null ? "§7No speedrun running" : "§7Speedrun abandoned after §f" + clock(System.currentTimeMillis() - r.start);
	}

	public static void bridged(UUID player, double usd, boolean in) {
		Run r = active(player);
		if (r == null) return;
		if (in) r.bridgedIn += usd;
		else r.bridgedOut += usd;
		save();
	}

	/** The dragon died: finish the run of every player with one going who is in the End. */
	public static void dragonKilled(MinecraftServer server, Level level) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Run r = active(p.getUUID());
			if (r == null || p.level() != level) continue;
			saved.active.remove(p.getUUID().toString());
			r.end = System.currentTimeMillis();
			r.endNetWorth = netWorth(p.getUUID());
			saved.finished.add(r);
			saved.finished.sort((a, b) -> Long.compare(a.end - a.start, b.end - b.start));
			while (saved.finished.size() > 50) saved.finished.remove(saved.finished.size() - 1);
			save();
			double pnl = r.endNetWorth - r.startNetWorth - r.bridgedIn + r.bridgedOut;
			server.getPlayerList().broadcastSystemMessage(Component.literal("§6[Wall Street Speedrun] §f" + r.name + "§7 beat the dragon in §f" + clock(r.end - r.start)
					+ "§7 · deposited §f" + Fmt.usd(r.bridgedIn) + "§7, withdrawn §f" + Fmt.usd(r.bridgedOut) + "§7 · trading PnL " + (pnl >= 0 ? "§a" : "§c") + Fmt.usdSigned(pnl)), false);
			Advancements.award(p, "wall_street_speedrun");
		}
	}

	public static List<String> top() {
		List<String> out = new ArrayList<>();
		int i = 1;
		for (Run r : saved.finished) {
			if (i > 10) break;
			out.add("§7" + i++ + ". §f" + r.name + " §6" + clock(r.end - r.start) + " §7(net worth " + Fmt.usd(r.startNetWorth) + " → " + Fmt.usd(r.endNetWorth) + ")");
		}
		if (out.isEmpty()) out.add("§7No finished speedruns yet. §f/stonk speedrun start");
		return out;
	}

	/** For the HUD: start time and net worth, or nothing. */
	@Nullable
	public static JsonObject hud(ServerPlayer p) {
		Run r = active(p.getUUID());
		if (r == null) return null;
		JsonObject o = new JsonObject();
		o.addProperty("start", r.start);
		o.addProperty("netWorth", netWorth(p.getUUID()));
		return o;
	}

	public static String clock(long ms) {
		long s = ms / 1000;
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}
}
