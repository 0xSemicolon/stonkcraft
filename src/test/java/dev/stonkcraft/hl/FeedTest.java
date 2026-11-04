package dev.stonkcraft.hl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stonkcraft.market.MarketData;
import dev.stonkcraft.trading.WatchAccount;
import dev.stonkcraft.trading.OrderStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Feed routing and state updates, from messages shaped like Hyperliquid's (recorded 2026-10-20). */
class FeedTest {
	private static final String USER = "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303";

	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void routesByChannelAndCoinOrUser() {
		assertEquals("l2Book:btc", HlSocket.identifier(HlSocket.sub("l2Book", "BTC", null)));
		assertEquals("openOrders:" + USER, HlSocket.identifier(HlSocket.sub("openOrders", null, USER.toUpperCase().replace("0X", "0x"))));
		assertEquals("allMids", HlSocket.identifier(HlSocket.sub("allMids", null, null)));
		assertEquals("l2Book:btc", HlSocket.identifierOf(json("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\",\"time\":1,\"levels\":[[],[]]}}")));
		assertEquals("clearinghouseState:" + USER, HlSocket.identifierOf(json("{\"channel\":\"clearinghouseState\",\"data\":{\"dex\":\"\",\"user\":\"" + USER + "\"}}")));
		assertEquals("allMids", HlSocket.identifierOf(json("{\"channel\":\"allMids\",\"data\":{\"mids\":{}}}")));
		assertNull(HlSocket.identifierOf(json("{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\"}}")));
		assertNull(HlSocket.identifierOf(json("{\"channel\":\"pong\"}")));
	}

	@Test
	void dispatchHandsDataToTheRightSubscriber() {
		HlSocket socket = new HlSocket("https://example.invalid", Executors.newSingleThreadScheduledExecutor());
		List<String> got = new ArrayList<>();
		socket.subscribe(HlSocket.sub("l2Book", "BTC", null), d -> got.add("btc:" + d.get("time")));
		socket.subscribe(HlSocket.sub("l2Book", "ETH", null), d -> got.add("eth:" + d.get("time")));
		socket.subscribe(HlSocket.sub("allMids", null, null), d -> got.add("mids:" + d.getAsJsonObject("mids").get("BTC").getAsString()));
		socket.dispatch("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"ETH\",\"time\":7,\"levels\":[[],[]]}}");
		socket.dispatch("{\"channel\":\"allMids\",\"data\":{\"mids\":{\"BTC\":\"82045.5\"}}}");
		socket.dispatch("{\"channel\":\"error\",\"data\":\"bad request\"}");
		socket.dispatch("not json");
		assertEquals(List.of("eth:7", "mids:82045.5"), got);
		assertEquals(3, socket.subscriptionCount());
		socket.unsubscribe(HlSocket.sub("l2Book", "ETH", null));
		socket.dispatch("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"ETH\",\"time\":8,\"levels\":[[],[]]}}");
		assertEquals(2, got.size());
		socket.subscribe(HlSocket.sub("openOrders", null, USER), d -> {});
		socket.subscribe(HlSocket.sub("userFills", null, USER), d -> {});
		assertEquals(1, socket.userCount());
	}

	@Test
	void feedMessagesUpdateAWatchedAccount() {
		MarketData md = new MarketData(new HyperliquidApi("https://example.invalid"));
		WatchAccount acct = new WatchAccount(md, USER, null);
		acct.applyForTest("clearinghouseState", json("""
				{"dex":"","user":"%s","clearinghouseState":{"marginSummary":{"accountValue":"1250.5","totalNtlPos":"1000","totalRawUsd":"250.5","totalMarginUsed":"100"},
				"withdrawable":"1150.5","assetPositions":[{"type":"oneWay","position":{"coin":"BTC","szi":"0.0125","entryPx":"80000","positionValue":"1025.0",
				"unrealizedPnl":"25.0","liquidationPx":"70000","leverage":{"type":"cross","value":10}}}]}}""".formatted(USER)));
		assertEquals(1250.5, acct.equity(), 1e-9);
		assertEquals(0.0125, acct.positionSize("BTC"), 1e-12);
		assertEquals(25.0, acct.unrealizedPnl("BTC"), 1e-9);
		assertEquals(10, acct.leverage("BTC"));

		acct.applyForTest("openOrders", json("""
				{"dex":"","user":"%s","orders":[{"coin":"BTC","side":"B","limitPx":"79000","sz":"0.004","oid":42,"timestamp":1792467567236,
				"origSz":"0.01"}]}""".formatted(USER)));
		assertEquals(1, acct.openOrders("BTC"));

		acct.applyForTest("openOrders", json("{\"dex\":\"\",\"user\":\"" + USER + "\",\"orders\":[]}"));
		acct.applyForTest("userFills", json("""
				{"isSnapshot":false,"user":"%s","fills":[{"coin":"BTC","px":"79000","sz":"0.004","side":"B","time":1792467600000,
				"closedPnl":"0","oid":42}]}""".formatted(USER)));
		assertEquals(0, acct.openOrders("BTC"));
		assertEquals(1792467600000L, acct.lastFillTime("BTC"));
		JsonArray fills = acct.snapshot().getAsJsonArray("fills");
		assertEquals(1, fills.size());
		assertTrue(fills.get(0).getAsJsonObject().get("size").getAsDouble() > 0);
	}
}
