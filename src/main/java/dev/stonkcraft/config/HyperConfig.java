package dev.stonkcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.stonkcraft.Stonkcraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side config at config/stonkcraft.json. Stonkcraft is paper trading only: prices and watched accounts come
 * from Hyperliquid mainnet, and no order ever reaches an exchange.
 */
public class HyperConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String MAINNET = "https://api.hyperliquid.xyz";

	public String _readme = "Paper trading against live market prices. Nothing here touches real money.";
	/** The default most a market order may slip (IOC limit at mid +/- this fraction); players set their own in the terminal. */
	public double slippage = 0.08;

	/**
	 * The build menu (H) hands out Stonkcraft blocks for free. Off: blocks are crafted, and only creative players
	 * get them free (the build menu has survival players craft them from what they carry).
	 */
	public boolean buildMenu = false;
	/** Paper coins every player receives once, on first join. 0: money comes in through UNIT[XYZ], from goods. */
	public double paperStarterGrant = 0;
	/** Balance given to accounts migrated from the old one-account-per-player format. */
	public double paperStartingBalance = 100_000;
	/** The paper mint: players can print paper money for free from the Fund screen. Off: ops can still /stonk mint. */
	public boolean paperFaucet = false;
	/** Players can watch any Hyperliquid address read-only (no keys involved) and wire its figures to redstone. */
	public boolean allowWatchAccounts = true;
	/** Most paper money one mint press prints. */
	public double paperFaucetUsd = 100_000;
	/** Seconds between mints for survival players without the build menu (ops and creative players never wait). */
	public int paperFaucetCooldownSeconds = 300;
	public double paperMaxLeverage = 10;
	/** Leverage a paper position opens at until the player sets one: low, so a first trade can't wipe out an account. */
	public double paperDefaultLeverage = 2;
	public double paperTakerFee = 0.00045;

	public int marketPollMillis = 1000;
	/** Stream prices, books and live accounts over Hyperliquid's WebSocket (REST polling stays as a fallback). */
	public boolean websocket = true;
	/** Most accounts given a live feed; Hyperliquid caps distinct users per IP across user feeds. */
	public int websocketMaxUsers = 10;
	/** Defaults this file was last brought up to; 0 for files from when money and blocks came free. */
	public int version;
	static final int VERSION = 2;

	private static HyperConfig instance = new HyperConfig();

	public static HyperConfig get() {
		return instance;
	}

	/**
	 * Hyperliquid's API host: mainnet. Developers can point it elsewhere with the STONKCRAFT_API environment variable
	 * or -Dstonkcraft.api; there's deliberately no config key or screen for it.
	 */
	public String apiBase() {
		String override = System.getProperty("stonkcraft.api", System.getenv("STONKCRAFT_API"));
		return override != null && override.startsWith("http") ? override.replaceAll("/+$", "") : MAINNET;
	}

	public boolean isMainnet() {
		return apiBase().equals(MAINNET);
	}

	/**
	 * Version 1 made money and blocks earned: no starter grant, no mint, no free blocks. A file still on the old
	 * defaults moves to the new ones; one an owner changed keeps its settings. Version 2 made Hyperliquid's 8% the
	 * default max slippage, for files still on the old 1%.
	 */
	void upgrade() {
		if (version >= VERSION) return;
		if (buildMenu && paperStarterGrant == 100_000 && paperFaucet) {
			buildMenu = false;
			paperStarterGrant = 0;
			paperFaucet = false;
		}
		if (slippage == 0.01) slippage = 0.08;
		version = VERSION;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("stonkcraft.json");
	}

	public static void load() {
		Path path = path();
		try {
			if (Files.exists(path)) {
				HyperConfig loaded = GSON.fromJson(Files.readString(path), HyperConfig.class);
				if (loaded != null) instance = loaded;
				instance._readme = new HyperConfig()._readme;
				instance.upgrade();
			}
			// rewriting drops keys from older versions (network, liveTrading, privateKey, builder fees...)
			Files.writeString(path, GSON.toJson(instance));
		} catch (IOException | RuntimeException e) {
			Stonkcraft.LOG.error("Failed to load {}, using defaults", path, e);
		}
	}
}
