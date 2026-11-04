package dev.stonkcraft.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.stonkcraft.market.MarketData;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** UNIT[XYZ] maths: metal in and out as spot balances or as longs, with withdrawals prorated by leverage. */
class PhysicalSettlementTest {
	private static final String GOLD = "xyz:GOLD";
	private static final String UGOLD = "UGOLD/USDC";
	private final Map<String, Double> mids = new HashMap<>();
	private final Map<String, MarketData.Asset> assets = Map.of(
			GOLD, new MarketData.Asset(GOLD, 110_000, 4, 25, false, GOLD, "GOLD", "xyz"),
			UGOLD, new MarketData.Asset(UGOLD, 90_001, 4, 1, true, GOLD, "UGOLD", "unit"));
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
		mids.put(GOLD, 4000.0);
		mids.put(UGOLD, 4000.0);
		acct = new PaperAccount(PaperAccount.fresh(), () -> quotes);
	}

	@Test
	void spotDepositAddsEquityAndExposure() {
		acct.creditSpot(UGOLD, 10, 4000);
		assertEquals(40_000, acct.equity(), 1e-6);
		assertEquals(40_000, acct.state().netDeposits, 1e-6);
		mids.put(UGOLD, 4400.0);
		assertEquals(44_000, acct.equity(), 1e-6);
		assertNull(acct.debitSpot(UGOLD, 4, 4400));
		assertEquals(6, acct.spotBalance(UGOLD), 1e-9);
		assertEquals(26_400, acct.equity(), 1e-6);
		assertNotNull(acct.debitSpot(UGOLD, 7, 4400));
	}

	@Test
	void oneXLongIsAllYours() {
		acct.depositLong(GOLD, 10, 4000);
		assertEquals(10, acct.positionSize(GOLD), 1e-9);
		assertEquals(40_000, acct.equity(), 1e-6);
		mids.put(GOLD, 4100.0);
		assertEquals(41_000, acct.equity(), 1e-6);
		assertEquals(10, acct.longWithdrawable(GOLD, 4100), 1e-9);
	}

	@Test
	void withdrawingFromOneXLongConservesValue() {
		acct.depositLong(GOLD, 10, 4000);
		assertEquals(1, acct.leverage(GOLD));
		mids.put(GOLD, 4100.0);
		double before = acct.equity();
		assertNull(acct.withdrawLong(GOLD, 4, 4100));
		assertEquals(6, acct.positionSize(GOLD), 1e-9);
		assertEquals(before - 4 * 4100, acct.equity(), 1e-6);
		assertEquals(6, acct.longWithdrawable(GOLD, 4100), 1e-9);
	}

	@Test
	void leveredLongsAreProratedByEquity() {
		acct.deposit(40_000);
		// gold's on trade[xyz], which in Manual margins from its own USDC
		assertNull(acct.transferDex(40_000, "xyz", true));
		acct.setLeverage(GOLD, 3, true).join();
		// about 25 oz at 3x with $40k: $100k notional, $33k margin
		assertEquals(true, acct.submit(OrderRequest.market(GOLD, Side.LONG, 100_000)).join().ok());
		double size = acct.positionSize(GOLD);
		double entry = acct.state().positions.get(GOLD).entry;
		double kmax = acct.longWithdrawable(GOLD, 4000);
		assertEquals((size * entry / 3 + size * (4000 - entry)) / 4000, kmax, 1e-9);
		double before = acct.equity();
		double margin = acct.marginUsed();
		assertNull(acct.withdrawLong(GOLD, kmax / 2, 4000));
		// half the position's equity left as metal, so half the position closed and leverage unchanged
		assertEquals(size / 2, acct.positionSize(GOLD), 1e-9);
		assertEquals(before - kmax / 2 * 4000, acct.equity(), 1e-6);
		assertEquals(margin / 2, acct.marginUsed(), 1e-6);
		assertEquals(3, acct.leverage(GOLD));
		assertNotNull(acct.withdrawLong(GOLD, kmax, 4000));
	}

	@Test
	void depositIntoAShortSettlesIt() {
		acct.deposit(10_000);
		acct.submit(OrderRequest.market(GOLD, Side.SHORT, 8000)).join();
		double shortSize = acct.positionSize(GOLD);
		acct.depositLong(GOLD, -shortSize, 4000);
		assertEquals(0, acct.positionSize(GOLD), 1e-9);
	}

	@Test
	void shortsCantLeaveAsMetal() {
		acct.deposit(10_000);
		acct.submit(OrderRequest.market(GOLD, Side.SHORT, 8000)).join();
		assertEquals(0, acct.longWithdrawable(GOLD, 4000));
		assertNotNull(acct.withdrawLong(GOLD, 1, 4000));
	}
}
