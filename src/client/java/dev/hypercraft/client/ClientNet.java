package dev.hypercraft.client;

import com.google.gson.JsonObject;
import dev.hypercraft.net.HcNet;
import dev.hypercraft.trading.OrderRequest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientNet {
	private ClientNet() {}

	public static void send(String kind, JsonObject body) {
		// the deep history belongs to the account it was asked for
		if (kind.equals("select_account")) ClientState.history = null;
		if (ClientPlayNetworking.canSend(HcNet.ToServer.TYPE)) {
			ClientPlayNetworking.send(new HcNet.ToServer(kind, body.toString()));
		}
	}

	public static void send(String kind) {
		send(kind, new JsonObject());
	}

	public static void order(OrderRequest r) {
		JsonObject o = r.toJson();
		o.addProperty("account", ClientState.selectedAccountId());
		send("order", o);
	}

	public static void order(String coin, String side, double usd) {
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		o.addProperty("side", side);
		o.addProperty("usd", usd);
		o.addProperty("account", ClientState.selectedAccountId());
		send("order", o);
	}
}
