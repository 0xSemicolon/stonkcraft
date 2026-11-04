package dev.stonkcraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.stonkcraft.Stonkcraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** Per-player UI preferences at config/stonkcraft-client.json. */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean tape = true;
	public List<String> watchlist = new ArrayList<>(List.of("xyz:GOLD", "xyz:SILVER", "xyz:CL", "xyz:SP500", "xyz:NVDA"));
	public String lastCoin = "xyz:GOLD";
	public String interval = "15m";
	public double orderUsd = 100;
	/** The welcome screen has been shown and closed. */
	public boolean welcomed = false;
	/** The order form's size is in coin rather than USD. */
	public boolean sizeInCoin = false;
	/** The order form's TP/SL boxes take prices rather than distances. */
	public boolean tpslByPrice = false;
	/** The most a market order from the order form may slip, in %. Closes use 8%, market TP/SL 10%, whatever this says. */
	public double maxSlippagePct = 8;
	/** Ask twice before an order that ties up over half the free margin. */
	public boolean confirmOrders = true;
	/** Ask twice before closing a position. */
	public boolean confirmCloses = true;
	/** Close All takes a one-second hold rather than a second click. */
	public boolean holdToCloseAll = false;
	/** A note when an order's answered. */
	public boolean sounds = true;

	private static ClientConfig instance = new ClientConfig();

	public static ClientConfig get() {
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("stonkcraft-client.json");
	}

	public static void load() {
		try {
			if (Files.exists(path())) {
				ClientConfig c = GSON.fromJson(Files.readString(path()), ClientConfig.class);
				if (c != null) instance = c;
			}
			save(); // writes newly added keys, so they show up in the file

		} catch (Exception e) {
			Stonkcraft.LOG.warn("Could not read client config", e);
		}
	}

	public static void save() {
		try {
			Files.writeString(path(), GSON.toJson(instance));
		} catch (Exception e) {
			Stonkcraft.LOG.warn("Could not save client config", e);
		}
	}

	public boolean isWatched(String coin) {
		return watchlist.contains(coin);
	}

	public void toggleWatch(String coin) {
		if (!watchlist.remove(coin)) watchlist.add(coin);
		save();
	}
}
