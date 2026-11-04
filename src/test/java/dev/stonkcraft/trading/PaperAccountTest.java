package dev.stonkcraft.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stonkcraft.market.MarketData;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaperAccountTest {
	private final Map<String, Double> mids = new HashMap<>();
	private final Map<String, MarketData.Asset> assets = Map.of(
			"BTC", new MarketData.Asset("BTC", 0, 5, 40),
			"HYPE/USDC", new MarketData.Asset("HYPE/USDC", 10107, 2, 1, true, "@107", "HYPE"));
	private final PaperAccount.Quotes quotes = new PaperAccount.Quotes() {
		@Override
		public MarketData.Asset asset(String coin) {
			return assets.get(coin);
		}

		@Override
		public double mid(String coin) {
			return mids.getOrDefault(coin, Double.NaN);
		}

		@Override
		public double funding(String coin) {
			return coin.equals("BTC") ? 0.0001 : 0;
		}
	};
	private PaperAccount acct;

	@BeforeEach
	void setUp() {
		mids.put("BTC", 80_000.0);
		mids.put("HYPE/USDC", 80.0);
		acct = new PaperAccount(PaperAccount.fresh(), () -> quotes);
		acct.deposit(10_000);
	}

	private OrderResult submit(OrderRequest r) {
		return acct.submit(r).join();
	}

	private static OrderRequest order(String coin, Side side, double usd, OrderRequest.Type type) {
		OrderRequest r = OrderRequest.market(coin, side, usd);
		r.type = type;
		return r;
	}

	@Test
	void restingLimitFillsWhenPriceComesToIt() {
		OrderRequest r = order("BTC", Side.BUY, 7_600, OrderRequest.Type.LIMIT);
		r.price = 76_000;
		assertTrue(submit(r).ok());
		assertEquals(1, acct.openOrders("BTC"));
		acct.tick(System.currentTimeMillis());
		assertEquals(0, acct.positionSize("BTC"), 1e-12);
		mids.put("BTC", 75_900.0);
		acct.tick(System.currentTimeMillis());
		assertEquals(0.1, acct.positionSize("BTC"), 1e-12);
		assertEquals(0, acct.openOrders(null));
		assertTrue(acct.lastFillTime("BTC") > 0);
	}

	@Test
	void postOnlyRejectsWhenCrossingButGtcFillsImmediately() {
		OrderRequest alo = order("BTC", Side.BUY, 800, OrderRequest.Type.LIMIT);
		alo.price = 81_000;
		alo.tif = OrderRequest.Tif.ALO;
		assertFalse(submit(alo).ok());
		alo.tif = OrderRequest.Tif.GTC;
		assertTrue(submit(alo).ok());
		assertTrue(acct.positionSize("BTC") > 0);
		assertEquals(0, acct.openOrders(null));
	}

	@Test
	void bracketTakeProfitClosesAndCancelsStop() {
		OrderRequest r = order("BTC", Side.BUY, 8_000, OrderRequest.Type.MARKET);
		r.tpPct = 5;
		r.slPct = 5;
		assertTrue(submit(r).ok());
		assertEquals(2, acct.openOrders("BTC"));
		mids.put("BTC", 84_500.0);
		var events = acct.tick(System.currentTimeMillis());
		assertEquals(0, acct.positionSize("BTC"), 1e-12);
		assertEquals(0, acct.openOrders(null));
		assertTrue(events.get(0).contains("Take profit"));
		assertTrue(acct.equity() > 10_000);
	}

	@Test
	void bracketOnRestingEntryWaitsForTheFill() {
		OrderRequest r = order("BTC", Side.BUY, 7_600, OrderRequest.Type.LIMIT);
		r.price = 76_000;
		r.slPct = 2;
		submit(r);
		mids.put("BTC", 74_000.0); // below the SL price but the entry hasn't filled when we check
		var snap = acct.snapshot().getAsJsonArray("orders");
		assertTrue(snap.get(1).getAsJsonObject().get("pending").getAsBoolean());
		// the gap down fills the entry at 76k, which arms the SL already past its 74,480 trigger: both fire this tick
		var events = acct.tick(System.currentTimeMillis());
		assertEquals(0, acct.positionSize("BTC"), 1e-12);
		assertEquals(2, events.size());
		assertEquals(2, acct.snapshot().getAsJsonArray("fills").size());
	}

	@Test
	void stopOnShortTriggersWhenPriceRises() {
		submit(order("BTC", Side.SELL, 8_000, OrderRequest.Type.MARKET));
		OrderRequest stop = order("BTC", Side.CLOSE, 0, OrderRequest.Type.STOP);
		stop.price = 82_000;
		assertTrue(submit(stop).ok());
		mids.put("BTC", 81_000.0);
		acct.tick(System.currentTimeMillis());
		assertTrue(acct.positionSize("BTC") < 0);
		mids.put("BTC", 82_100.0);
		acct.tick(System.currentTimeMillis());
		assertEquals(0, acct.positionSize("BTC"), 1e-12);
	}

	@Test
	void leverageLimitsSizeAndCantDropBelowOpenExposure() {
		assertTrue(acct.setLeverage("BTC", 2, true).join().ok());
		assertFalse(submit(order("BTC", Side.BUY, 25_000, OrderRequest.Type.MARKET)).ok());
		assertTrue(submit(order("BTC", Side.BUY, 15_000, OrderRequest.Type.MARKET)).ok());
		assertFalse(acct.setLeverage("BTC", 1, true).join().ok());
		assertEquals(2, acct.leverage("BTC"));
		assertFalse(acct.setLeverage("BTC", 41, true).join().ok());
		assertTrue(acct.setLeverage("BTC", 20, false).join().ok());
		assertFalse(acct.isCross("BTC"));
	}

	@Test
	void spotBuysAndSellsTokens() {
		// spot spends the spot account's USDC: none until some is moved over from perps
		assertFalse(submit(order("HYPE/USDC", Side.BUY, 800, OrderRequest.Type.MARKET)).ok());
		assertNull(acct.transfer(1_000, false));
		assertTrue(submit(order("HYPE/USDC", Side.BUY, 800, OrderRequest.Type.MARKET)).ok());
		assertEquals(10.0, acct.positionSize("HYPE/USDC"), 1e-12);
		assertEquals(1, acct.snapshot().getAsJsonArray("balances").size());
		mids.put("HYPE/USDC", 100.0);
		assertTrue(submit(order("HYPE/USDC", Side.CLOSE, 0, OrderRequest.Type.MARKET)).ok());
		assertEquals(0, acct.positionSize("HYPE/USDC"), 1e-12);
		assertTrue(acct.equity() > 10_150);
		assertFalse(submit(order("HYPE/USDC", Side.BUY, 50_000, OrderRequest.Type.MARKET)).ok());
	}

	@Test
	void aSpotSaleNeedsATransferBeforeItCanMarginAPerp() {
		double perps = acct.withdrawable();
		assertNull(acct.transfer(perps, false));
		assertEquals(0, acct.withdrawable(), 1e-9);
		// everything is in spot now: a perp can't open on it
		assertFalse(submit(order("BTC", Side.BUY, 1_000, OrderRequest.Type.MARKET)).ok());
		assertTrue(submit(order("HYPE/USDC", Side.BUY, 800, OrderRequest.Type.MARKET)).ok());
		mids.put("HYPE/USDC", 100.0);
		assertTrue(submit(order("HYPE/USDC", Side.CLOSE, 0, OrderRequest.Type.MARKET)).ok());
		// the sale paid spot, not perps
		assertEquals(0, acct.withdrawable(), 1e-9);
		assertTrue(acct.spotUsdc() > perps);
		assertNotNull(acct.transfer(acct.spotUsdc() + 1, true));
		assertNull(acct.transfer(2_000, true));
		assertTrue(submit(order("BTC", Side.BUY, 1_000, OrderRequest.Type.MARKET)).ok());
	}

	@Test
	void marginInUseCantMoveToSpot() {
		assertTrue(submit(order("BTC", Side.BUY, 8_000, OrderRequest.Type.MARKET)).ok());
		double free = acct.withdrawable();
		assertNotNull(acct.transfer(free + 50, false));
		assertNull(acct.transfer(free, false));
		// spot holdings and spot USDC aren't perp collateral
		assertEquals(acct.perpEquity() + acct.spotEquity(), acct.equity(), 1e-6);
		assertEquals(free, acct.spotUsdc(), 1e-9);
	}

	@Test
	void reduceOnlyOrdersClearWhenFlatAndCancelRemovesChildren() {
		submit(order("BTC", Side.BUY, 8_000, OrderRequest.Type.MARKET));
		OrderRequest tp = order("BTC", Side.CLOSE, 0, OrderRequest.Type.TAKE_PROFIT);
		tp.price = 90_000;
		submit(tp);
		assertEquals(1, acct.openOrders("BTC"));
		submit(order("BTC", Side.CLOSE, 0, OrderRequest.Type.MARKET));
		assertEquals(0, acct.openOrders("BTC"));

		OrderRequest r = order("BTC", Side.BUY, 7_000, OrderRequest.Type.LIMIT);
		r.price = 70_000;
		r.tpPct = 10;
		submit(r);
		long entryOid = acct.snapshot().getAsJsonArray("orders").get(0).getAsJsonObject().get("oid").getAsLong();
		assertTrue(acct.cancel("BTC", entryOid).join().ok());
		assertEquals(0, acct.openOrders(null));
	}

	@Test
	void twapSlicesUntilDone() {
		OrderRequest r = order("BTC", Side.BUY, 8_000, OrderRequest.Type.TWAP);
		r.minutes = 5;
		r.randomize = true;
		assertTrue(submit(r).ok());
		assertEquals(1, acct.openOrders("BTC"));
		long start = System.currentTimeMillis();
		acct.tick(start);
		double afterFirst = acct.positionSize("BTC");
		assertTrue(afterFirst > 0 && afterFirst < 0.1, "first slice only: " + afterFirst);
		for (int k = 1; k <= 12; k++) acct.tick(start + k * 30_000L);
		assertEquals(0.1, acct.positionSize("BTC"), 1e-9);
		assertEquals(0, acct.openOrders(null));
	}

	@Test
	void deadMansSwitchCancelsEverything() {
		OrderRequest r = order("BTC", Side.BUY, 1_000, OrderRequest.Type.LIMIT);
		r.price = 70_000;
		submit(r);
		assertFalse(acct.scheduleCancel(System.currentTimeMillis() + 1_000).join().ok());
		long when = System.currentTimeMillis() + 6_000;
		assertTrue(acct.scheduleCancel(when).join().ok());
		acct.tick(when - 1);
		assertEquals(1, acct.openOrders(null));
		var events = acct.tick(when + 1);
		assertEquals(0, acct.openOrders(null));
		assertEquals(0, acct.scheduledCancel());
		assertTrue(events.get(0).contains("Dead man"));
	}

	@Test
	void longsPayPositiveFundingEachHour() {
		submit(order("BTC", Side.BUY, 8_000, OrderRequest.Type.MARKET));
		long now = System.currentTimeMillis();
		acct.state().lastFundingHour = now / 3_600_000L - 1;
		double before = acct.equity();
		acct.tick(now);
		assertEquals(-0.8, acct.equity() - before, 0.01);
		assertEquals(1, acct.snapshot().getAsJsonArray("funding").size());
	}

	@Test
	void aUnifiedAccountHasOneUsdcBalance() {
		assertNull(acct.transfer(1_000, false));
		acct.setUnified(true);
		// the spot USDC joined the one balance, and there's nothing to transfer between
		assertEquals(0, acct.spotUsdc(), 1e-9);
		assertNotNull(acct.transfer(100, true));
		assertTrue(submit(order("HYPE/USDC", Side.BUY, 800, OrderRequest.Type.MARKET)).ok());
		mids.put("HYPE/USDC", 100.0);
		double before = acct.withdrawable();
		assertTrue(submit(order("HYPE/USDC", Side.CLOSE, 0, OrderRequest.Type.MARKET)).ok());
		// a sale pays straight into the balance that margins perps
		assertTrue(acct.withdrawable() > before + 900);
		assertTrue(submit(order("BTC", Side.BUY, 1_000, OrderRequest.Type.MARKET)).ok());
	}
}
