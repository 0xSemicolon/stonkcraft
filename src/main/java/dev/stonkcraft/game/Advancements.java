package dev.stonkcraft.game;

import dev.stonkcraft.Stonkcraft;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * The Stonkcraft advancement tab. Each advancement has one {@code minecraft:impossible} criterion, "done", granted
 * from code when the thing happens.
 */
public final class Advancements {
	private Advancements() {}

	public static void award(@Nullable ServerPlayer player, String key) {
		if (player == null || player.getServer() == null) return;
		AdvancementHolder adv = player.getServer().getAdvancements().get(Stonkcraft.id(key));
		if (adv != null) player.getAdvancements().award(adv, "done");
	}

	private static final java.util.Set<String> GIVEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** {@link #award}, skipping the lookup after the first time this session (for checks that run every second). */
	public static void awardOnce(ServerPlayer player, String key) {
		if (GIVEN.add(player.getUUID() + ":" + key)) award(player, key);
	}

	/** Awards the player with this id if they're online. */
	public static void award(@Nullable MinecraftServer server, @Nullable UUID player, String key) {
		if (server == null || player == null) return;
		award(server.getPlayerList().getPlayer(player), key);
	}
}
