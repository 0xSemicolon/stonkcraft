package dev.stonkcraft.game;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;

/** Ways trading reaches into the world. */
public final class GameHooks {
	private GameHooks() {}

	/** A liquidation: a harmless lightning strike next to the owner, thunder, a title, and the Rekt advancement. */
	public static void marginCall(ServerPlayer p, String account) {
		if (p.level() instanceof ServerLevel level) {
			LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
			if (bolt != null) {
				bolt.moveTo(p.getX() + 2, p.getY(), p.getZ() + 2);
				bolt.setVisualOnly(true);
				level.addFreshEntity(bolt);
			}
			level.playSound(null, p.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 1f, 0.8f);
		}
		p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 20));
		p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§cMARGIN CALL")));
		p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7" + account + " was liquidated")));
		Advancements.award(p, "rekt");
	}
}
