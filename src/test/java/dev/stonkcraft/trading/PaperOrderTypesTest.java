package dev.stonkcraft.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.stonkcraft.market.MarketData;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Stop and Take limits, Scale, trailing stops, position TP/SL, Reverse, coin sizing, TP/SL by price, and TWAP's limits. */
class PaperOrderTypesTest {
	private final Map<String, Double> mids = new HashMap<>();
	private final Map<String, MarketData.Asset> assets = Map.of("BTC", new MarketData.Asset("BTC", 0, 5, 40));
	private final PaperAccount.Quotes quotes = new PaperAccount.Quotes() {
		@Override
		public MarketData.Asset asset(String coin) {
			return assets.get(coin);
		}

		@Override
		public double mid(String coin) {
			return mids.getOrDefault(coin, Double.NaN);
		}
	};
	private PaperAccount acct;

	@BeforeEach
	void setUp() {
		mids.put("BTC", 80_000.0);
		acct = new PaperAccount(PaperAccount.fresh(), () -> quotes);
		acct.deposit(100_000);
	}

	private OrderResult submit(OrderRequest r) {
		return acct.submit(r).join();
	}

	private OrderRequest order(Side side, double usd, OrderRequest.Type type) {
		OrderRequest r = OrderRequest.market("BTC", side, usd);
		r.type = type;
		return r;
	}

	private void at(double px) {
		mids.put("BTC", px);
		acct.tick(System.currentTimeMillis());
	}

	private JsonObject onlyOrder() {
		return acct.snapshot().getAsJsonArray("orders").get(0).getAsJsonObject();
	}

	@Test
	void stopLimitRestsOnceTriggeredAndFillsAtItsLimit() {
		assertTrue(submit(order(Side.BUY, 8_000, OrderRequest.Type.MARKET)).ok());
		OrderRequest r = order(Side.CLOSE, 0, OrderRequest.Type.STOP_LIMIT);
		r.price = 75_000;
		r.limitPrice = 74_000;
		assertTrue(submit(r).ok());
		assertEquals("Stop limit", onlyOrder().get("type").getAsString());
		assertTrue(onlyOrder().get("trigger").getAsString().contains("75,000"), onlyOrder().toString());
		// not yet triggered
		at(76_000);
		assertEquals(0.1, acct.positionSize("BTC"), 1e-9);
		assertEquals(1, acct.openOrders(null));
		// a fall past the trigger and the limit: the limit is marketable, fills at the mid
		at(73_000);
		assertEquals(0.1, acct.positionSize("BTC"), 1e-9, "a sell limit at 74,000 isn't reached at 73,000");
		assertEquals("Limit", onlyOrder().get("type").getAsString());
		at(74_500);
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
	}

	@Test
	void takeLimitWithAMarketableLimitFillsAsSoonAsItTriggers() {
		assertTrue(submit(order(Side.BUY, 8_000, OrderRequest.Type.MARKET)).ok());
		OrderRequest r = order(Side.CLOSE, 0, OrderRequest.Type.TAKE_LIMIT);
		r.price = 85_000;
		r.limitPrice = 84_500;
		assertTrue(submit(r).ok());
		at(85_100);
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
	}

	@Test
	void triggerLimitNeedsALimit() {
		OrderRequest r = order(Side.BUY, 1_000, OrderRequest.Type.STOP_LIMIT);
		r.price = 85_000;
		assertFalse(submit(r).ok());
	}

	@Test
	void scaleSpreadsOrdersWithSkew() {
		OrderRequest r = order(Side.BUY, 0, OrderRequest.Type.SCALE);
		r.sizeCoin = 1;
		r.scaleStart = 70_000;
		r.scaleEnd = 78_000;
		r.scaleOrders = 5;
		r.scaleSkew = 2;
		assertTrue(submit(r).ok());
		var orders = acct.snapshot().getAsJsonArray("orders");
		assertEquals(5, orders.size());
		double total = 0;
		for (var el : orders) total += el.getAsJsonObject().get("size").getAsDouble();
		assertEquals(1, total, 1e-3);
		JsonObject first = orders.get(0).getAsJsonObject(), last = orders.get(4).getAsJsonObject();
		assertEquals(70_000, first.get("px").getAsDouble(), 1e-6);
		assertEquals(78_000, last.get("px").getAsDouble(), 1e-6);
		assertEquals(2, last.get("size").getAsDouble() / first.get("size").getAsDouble(), 0.01);
		at(77_000);
		assertEquals(last.get("size").getAsDouble(), acct.positionSize("BTC"), 1e-9, "the nearest one filled");
	}

	@Test
	void scaleRefusesWhatCantWork() {
		OrderRequest same = order(Side.BUY, 1_000, OrderRequest.Type.SCALE);
		same.scaleStart = same.scaleEnd = 70_000;
		assertFalse(submit(same).ok());
		OrderRequest post = order(Side.BUY, 1_000, OrderRequest.Type.SCALE);
		post.scaleStart = 79_000;
		post.scaleEnd = 81_000;
		post.tif = OrderRequest.Tif.ALO;
		assertFalse(submit(post).ok());
		assertEquals(0, acct.openOrders(null), "nothing placed when any of it fails");
		OrderRequest close = order(Side.CLOSE, 0, OrderRequest.Type.SCALE);
		close.scaleStart = 70_000;
		close.scaleEnd = 71_000;
		assertFalse(submit(close).ok());
	}

	@Test
	void trailingStopFollowsTheHighAndFiresOnTheRetrace() {
		assertTrue(submit(order(Side.BUY, 8_000, OrderRequest.Type.MARKET)).ok());
		OrderRequest r = order(Side.CLOSE, 0, OrderRequest.Type.TRAILING);
		r.trailPct = 5;
		assertTrue(submit(r).ok());
		assertEquals("Trailing stop", onlyOrder().get("type").getAsString());
		assertEquals(76_000, onlyOrder().get("px").getAsDouble(), 1e-6);
		at(90_000);
		assertEquals(85_500, onlyOrder().get("px").getAsDouble(), 1e-6);
		at(86_000);
		assertEquals(0.1, acct.positionSize("BTC"), 1e-9);
		at(85_000);
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
	}

	@Test
	void trailingStopWaitsForItsActivationPrice() {
		assertTrue(submit(order(Side.BUY, 8_000, OrderRequest.Type.MARKET)).ok());
		OrderRequest r = order(Side.CLOSE, 0, OrderRequest.Type.TRAILING);
		r.trailPct = 5;
		r.activationPx = 90_000;
		assertTrue(submit(r).ok());
		// 20% down, but it hasn't started
		at(64_000);
		assertEquals(0.1, acct.positionSize("BTC"), 1e-9);
		at(91_000);
		at(86_000);
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
	}

	@Test
	void aPositionsTpSlCoversTheWholePositionAndCanBeReplaced() {
		assertTrue(submit(order(Side.BUY, 8_000, OrderRequest.Type.MARKET)).ok());
		assertFalse(acct.setPositionTpSl("BTC", 70_000, 0).join().ok(), "a long's TP is above the price");
		assertFalse(acct.setPositionTpSl("BTC", 0, 90_000).join().ok(), "a long's SL is below it");
		assertTrue(acct.setPositionTpSl("BTC", 90_000, 70_000).join().ok());
		assertEquals(2, acct.openOrders("BTC"));
		JsonObject pos = acct.snapshot().getAsJsonArray("positions").get(0).getAsJsonObject();
		assertEquals(90_000, pos.get("tp").getAsDouble(), 1e-6);
		assertEquals(70_000, pos.get("sl").getAsDouble(), 1e-6);
		// replacing, not adding
		assertTrue(acct.setPositionTpSl("BTC", 95_000, 0).join().ok());
		assertEquals(1, acct.openOrders("BTC"));
		// grows with the position
		assertTrue(submit(order(Side.BUY, 8_000, OrderRequest.Type.MARKET)).ok());
		at(96_000);
		assertEquals(0, acct.positionSize("BTC"), 1e-9, "all of it, not the size when set");
		assertTrue(acct.setPositionTpSl("BTC", 0, 0).join().ok() == false, "no position now");
	}

	@Test
	void reverseFlipsThePositionAtTheSameSize() {
		assertTrue(acct.setLeverage("BTC", 10, true).join().ok());
		assertFalse(submit(order(Side.REVERSE, 0, OrderRequest.Type.MARKET)).ok(), "nothing to reverse");
		assertTrue(submit(order(Side.BUY, 8_000, OrderRequest.Type.MARKET)).ok());
		assertTrue(acct.setPositionTpSl("BTC", 90_000, 70_000).join().ok());
		assertTrue(submit(order(Side.REVERSE, 0, OrderRequest.Type.MARKET)).ok());
		assertEquals(-0.1, acct.positionSize("BTC"), 1e-9);
		assertEquals(0, acct.openOrders("BTC"), "the old side's TP/SL doesn't survive a flip");
		JsonObject pos = acct.snapshot().getAsJsonArray("positions").get(0).getAsJsonObject();
		assertEquals(80_000, pos.get("entry").getAsDouble(), 80_000 * 0.001);
	}

	@Test
	void anIsolatedReverseHoldsMarginForTheNewSide() {
		assertTrue(acct.setLeverage("BTC", 10, false).join().ok());
		assertTrue(submit(order(Side.BUY, 8_000, OrderRequest.Type.MARKET)).ok());
		assertTrue(submit(order(Side.REVERSE, 0, OrderRequest.Type.MARKET)).ok());
		assertEquals(800, acct.state().positions.get("BTC").margin, 2);
	}

	@Test
	void sizingInCoinIsExact() {
		OrderRequest r = order(Side.BUY, 0, OrderRequest.Type.MARKET);
		r.sizeCoin = 0.123;
		assertTrue(submit(r).ok());
		assertEquals(0.123, acct.positionSize("BTC"), 1e-9);
		OrderRequest sell = order(Side.SELL, 0, OrderRequest.Type.LIMIT);
		sell.sizeCoin = 0.023;
		sell.price = 90_000;
		assertTrue(submit(sell).ok());
		assertEquals(0.023, onlyOrder().get("size").getAsDouble(), 1e-9);
		assertEquals(0.023, onlyOrder().get("orig").getAsDouble(), 1e-9);
	}

	@Test
	void bracketByPriceAndItsChecks() {
		OrderRequest r = order(Side.BUY, 8_000, OrderRequest.Type.MARKET);
		r.tpPx = 88_000;
		r.slPx = 76_000;
		assertTrue(submit(r).ok());
		var orders = acct.snapshot().getAsJsonArray("orders");
		assertEquals(88_000, orders.get(0).getAsJsonObject().get("px").getAsDouble(), 1e-6);
		assertEquals(76_000, orders.get(1).getAsJsonObject().get("px").getAsDouble(), 1e-6);
		OrderRequest wrong = order(Side.BUY, 8_000, OrderRequest.Type.MARKET);
		wrong.tpPx = 70_000;
		assertFalse(submit(wrong).ok());
		assertTrue(submit(wrong).message().contains("above"));
	}

	@Test
	void aTightSlippageCapCantMatch() {
		OrderRequest r = order(Side.BUY, 8_000, OrderRequest.Type.MARKET);
		r.maxSlippage = 0.0001;
		assertFalse(submit(r).ok());
		r.maxSlippage = 0.08;
		assertTrue(submit(r).ok());
	}

	@Test
	void twapNeedsItsMinimumAndRunsUpToAWeek() {
		OrderRequest small = order(Side.BUY, 50, OrderRequest.Type.TWAP);
		assertFalse(submit(small).ok());
		OrderRequest week = order(Side.BUY, 8_000, OrderRequest.Type.TWAP);
		week.minutes = OrderRequest.MAX_TWAP_MINUTES;
		assertTrue(submit(week).ok());
		assertEquals(OrderRequest.MAX_TWAP_MINUTES, OrderRequest.fromJson(week.toJson()).minutes);
	}

	@Test
	void twapWaitsAtItsLimitAndCatchesUpAtMostThreefold() {
		OrderRequest r = order(Side.BUY, 8_000, OrderRequest.Type.TWAP);
		r.minutes = 5;
		r.randomize = false;
		r.twapLimit = 79_000;
		assertTrue(submit(r).ok());
		long start = System.currentTimeMillis();
		// over the limit for five slices: nothing trades
		for (int k = 0; k < 5; k++) acct.tick(start + k * 30_000L);
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
		// back under it: three normal slices' worth (1/10 each), not all five owed
		mids.put("BTC", 78_000.0);
		acct.tick(start + 5 * 30_000L);
		assertEquals(0.03, acct.positionSize("BTC"), 1e-6);
		for (int k = 6; k <= 10; k++) acct.tick(start + k * 30_000L);
		assertTrue(acct.positionSize("BTC") > 0.03 + 1e-6);
	}

	@Test
	void anUnfilledTwapEndsAndSaysSo() {
		OrderRequest r = order(Side.BUY, 8_000, OrderRequest.Type.TWAP);
		r.minutes = 5;
		r.twapLimit = 70_000;
		assertTrue(submit(r).ok());
		long start = System.currentTimeMillis();
		for (int k = 0; k <= 12; k++) acct.tick(start + k * 30_000L);
		assertEquals(0, acct.openOrders(null));
		assertEquals("Expired", acct.history().getAsJsonArray("orderHistory").get(0).getAsJsonObject().get("status").getAsString());
	}

	@Test
	void newFieldsSurviveAWireTripAndSanitize() {
		OrderRequest r = order(Side.BUY, 100, OrderRequest.Type.SCALE);
		r.scaleStart = 1;
		r.scaleEnd = 2;
		r.scaleOrders = 500;
		r.scaleSkew = Double.NaN;
		OrderRequest back = OrderRequest.fromJson(r.toJson());
		assertEquals(OrderRequest.MAX_SCALE_ORDERS, back.scaleOrders);
		assertEquals(1, back.scaleSkew, 1e-9);
		assertEquals(OrderRequest.Type.SCALE, back.type);
		assertFalse(OrderRequest.Type.SCALE.onBlocks());
		assertTrue(OrderRequest.Type.TWAP.onBlocks());
	}
}
