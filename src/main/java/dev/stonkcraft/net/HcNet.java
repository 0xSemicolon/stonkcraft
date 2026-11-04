package dev.stonkcraft.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stonkcraft.Stonkcraft;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Two JSON-carrying payloads, one per direction. Kinds are listed in {@link ServerNet} and the client handler. */
public final class HcNet {
	private HcNet() {}

	private static final int MAX = 1 << 20;

	public record ToClient(String kind, String json) implements CustomPacketPayload {
		public static final Type<ToClient> TYPE = new Type<>(Stonkcraft.id("to_client"));
		public static final StreamCodec<FriendlyByteBuf, ToClient> CODEC = CustomPacketPayload.codec(ToClient::write, ToClient::new);

		private ToClient(FriendlyByteBuf buf) {
			this(buf.readUtf(64), buf.readUtf(MAX));
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(kind, 64);
			buf.writeUtf(json, MAX);
		}

		public JsonObject body() {
			return JsonParser.parseString(json).getAsJsonObject();
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ToServer(String kind, String json) implements CustomPacketPayload {
		public static final Type<ToServer> TYPE = new Type<>(Stonkcraft.id("to_server"));
		public static final StreamCodec<FriendlyByteBuf, ToServer> CODEC = CustomPacketPayload.codec(ToServer::write, ToServer::new);

		private ToServer(FriendlyByteBuf buf) {
			this(buf.readUtf(64), buf.readUtf(32000));
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(kind, 64);
			buf.writeUtf(json, 32000);
		}

		public JsonObject body() {
			return JsonParser.parseString(json).getAsJsonObject();
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void registerTypes() {
		PayloadTypeRegistry.playS2C().register(ToClient.TYPE, ToClient.CODEC);
		PayloadTypeRegistry.playC2S().register(ToServer.TYPE, ToServer.CODEC);
	}

	public static void send(ServerPlayer player, String kind, JsonElement body) {
		if (ServerPlayNetworking.canSend(player, ToClient.TYPE)) {
			ServerPlayNetworking.send(player, new ToClient(kind, body.toString()));
		}
	}
}
