package dev.hypercraft.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.hypercraft.market.MarketData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Maintenance margin, liquidation, isolated margin, dex balances and everything else that decides who's solvent. */
class PaperMarginTest {
	private static final String GOLD = "xyz:GOLD";
	private static final String HYPE = "HYPE/USDC";
	private final Map<String, Double> mids = new HashMap<>();
	private final Map<String, Double> marks = new HashMap<>();
	private final Map<String, Double> oracles = new HashMap<>();
	private final Map<String, MarketData.Asset> assets = Map.of(
			"BTC", new MarketData.Asset("BTC", 0, 5, 40),
			"ETH", new MarketData.Asset("ETH", 1, 4, 25),
			GOLD, new MarketData.Asset(GOLD, 110_000, 4, 25, false, GOLD, "GOLD", "xyz"),
			HYPE, new MarketData.Asset(HYPE, 10107, 2, 1, true, "@107", "HYPE", ""));
	private double fundingRate;
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
		public double mark(String coin) {
			return marks.containsKey(coin) ? marks.get(coin) : mid(coin);
		}

		@Override
		public double oracle(String coin) {
			return oracles.containsKey(coin) ? oracles.get(coin) : mark(coin);
		}

		@Override
		public double funding(String coin) {
			return coin.equals("BTC") ? fundingRate : 0;
		}
	};
	private PaperAccount acct;

	@BeforeEach
	void setUp() {
		mids.put("BTC", 80_000.0);
		mids.put("ETH", 2_000.0);
		mids.put(GOLD, 4_000.0);
		mids.put(HYPE, 80.0);
		acct = new PaperAccount(PaperAccount.fresh(), () -> quotes);
		acct.deposit(10_000);
	}

	private OrderResult submit(OrderRequest r) {
		return acct.submit(r).join();
	}

	private OrderRequest market(String coin, Side side, double usd) {
		return OrderRequest.market(coin, side, usd);
	}

	private void tick() {
		acct.tick(System.currentTimeMillis());
	}

	private JsonObject row(String coin) {
		for (var el : acct.snapshot().getAsJsonArray("positions")) {
			JsonObject o = el.getAsJsonObject();
			if (o.get("coin").getAsString().equals(coin)) return o;
		}
		throw new AssertionError("no row for " + coin);
	}

	// ---------------------------------------------------------------- the formulas

	@Test
	void maintenanceIsHalfOfInitialAtMaxLeverage() {
		assertEquals(1_000, MarginMath.maintenance("BTC", 40, 80_000), 1e-9);
		assertEquals(160, MarginMath.maintenance("ETH", 25, 8_000), 1e-9);
	}

	@Test
	void marginTiersRaiseMaintenanceContinuouslyAndCapLeverage() {
		// 40x under $150M, 20x above: continuous at the bound, then the higher rate
		assertEquals(150e6 * 0.0125, MarginMath.maintenance("BTC", 40, 150e6), 1e-6);
		assertEquals(150e6 * 0.0125 + 50e6 * 0.025, MarginMath.maintenance("BTC", 40, 200e6), 1e-6);
		assertEquals(40, MarginMath.maxLeverage("BTC", 40, 100e6));
		assertEquals(20, MarginMath.maxLeverage("BTC", 40, 200e6));
		assertEquals(200e6 / 20, MarginMath.initialMargin("BTC", 40, 40, 200e6), 1e-6);
		// a market with no published tiers has one rate all the way up
		assertEquals(1e9 / 50, MarginMath.maintenance("NEWCOIN", 25, 1e9), 1e-6);
	}

	@Test
	void liquidationPriceMatchesTheFormula() {
		// long 1 BTC at 40x: 80000 − 1000 / (1 − 0.0125); at that price, value equals maintenance margin
		double liq = MarginMath.liquidationPrice(80_000, 1, 1_000, 0.0125);
		assertEquals(80_000 - 1_000 / (1 - 0.0125), liq, 1e-6);
		assertEquals(liq * 0.0125, 2_000 - (80_000 - liq), 1e-6);
		// short: above the price, the same way
		double up = MarginMath.liquidationPrice(80_000, -1, 1_000, 0.0125);
		assertEquals(80_000 + 1_000 / (1 + 0.0125), up, 1e-6);
		assertEquals(up * 0.0125, 2_000 - (up - 80_000), 1e-6);
		// a long with more margin than the position's worth can't liquidate at any positive price
		assertTrue(Double.isNaN(MarginMath.liquidationPrice(100, 1, 500, 0.0125)));
	}

	@Test
	void aPositionsLiquidationPriceIsWhereItActuallyLiquidates() {
		assertTrue(acct.setLeverage("BTC", 10, true).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 80_000)).ok());
		double liq = acct.liquidationPrice("BTC");
		assertTrue(liq > 70_000 && liq < 80_000, "liq " + liq);
		assertEquals(liq, row("BTC").get("liq").getAsDouble(), 1e-6);
		mids.put("BTC", liq + 5);
		tick();
		assertEquals(1, acct.positionSize("BTC"), 1e-9);
		assertTrue(acct.marginRatio() < 1);
		mids.put("BTC", liq - 5);
		assertTrue(acct.marginRatio() >= 1);
	}

	// ---------------------------------------------------------------- cross liquidation

	@Test
	void marginRatioIsMaintenanceOverValue() {
		assertTrue(submit(market("BTC", Side.BUY, 16_000)).ok());
		double mm = 0.2 * 80_000 * 0.0125;
		assertEquals(mm / acct.perpEquity(), acct.marginRatio(), 1e-9);
		JsonObject snap = acct.snapshot();
		assertEquals(mm, snap.get("maint").getAsDouble(), 1e-9);
		assertEquals(16_000 / acct.perpEquity(), snap.get("accountLeverage").getAsDouble(), 1e-3);
	}

	@Test
	void underMaintenanceTheMarketOrdersCloseAndThePlayerKeepsTheRest() {
		assertTrue(acct.setLeverage("BTC", 10, true).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 80_000)).ok());
		// value ≈ 848 is under the 886 maintenance margin, and over 2/3 of it
		mids.put("BTC", 70_900.0);
		List<String> events = acct.tick(System.currentTimeMillis());
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
		assertTrue(events.stream().anyMatch(e -> e.contains("Liquidated")), events.toString());
		assertTrue(acct.perpEquity() > 0, "kept what was left: " + acct.perpEquity());
		assertEquals("Market Order Liquidation", acct.state().fills.peekFirst().kind());
	}

	@Test
	void over100kGoesAFifthAtATimeAndWaits() {
		acct.deposit(40_000);
		assertTrue(acct.setLeverage("BTC", 40, true).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 1_000_000)).ok());
		long now = System.currentTimeMillis();
		double mm = MarginMath.maintenance("BTC", 40, 12.5 * 76_850);
		double value = acct.state().balance + 12.5 * (76_850 - acct.state().positions.get("BTC").entry);
		assertTrue(value < mm && value > mm * MarginMath.BACKSTOP_SHARE, "value " + value + " mm " + mm);
		mids.put("BTC", 76_850.0);
		List<String> first = acct.tick(now);
		assertEquals(10, acct.positionSize("BTC"), 1e-6);
		assertTrue(first.stream().anyMatch(e -> e.contains("Margin call")), first.toString());
		assertFalse(first.stream().anyMatch(e -> e.contains("Liquidated")), "a step isn't the end: " + first);
		// further under water, but over the backstop, and it waits out the cooldown
		mids.put("BTC", 76_700.0);
		assertTrue(acct.marginRatio() >= 1);
		acct.tick(now + 1_000);
		assertEquals(10, acct.positionSize("BTC"), 1e-6);
		acct.tick(now + MarginMath.PARTIAL_COOLDOWN_MS + 1);
		assertEquals(8, acct.positionSize("BTC"), 1e-6);
	}

	@Test
	void belowTwoThirdsTheBackstopTakesEverythingOnCross() {
		assertTrue(acct.setLeverage("BTC", 20, true).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 160_000)).ok());
		mids.put("BTC", 75_500.0);
		List<String> events = acct.tick(System.currentTimeMillis());
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
		assertTrue(events.stream().anyMatch(e -> e.contains("backstop")), events.toString());
		assertEquals(0, acct.perpEquity(), 1e-6, "the margin went with it");
		assertEquals("Backstop Liquidation", acct.state().fills.peekFirst().kind());
	}

	@Test
	void spotIsSafeFromAManualLiquidation() {
		assertNull(acct.transfer(2_000, false));
		assertTrue(submit(market(HYPE, Side.BUY, 800)).ok());
		double spot = acct.spotEquity();
		assertTrue(acct.setLeverage("BTC", 20, true).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 120_000)).ok());
		mids.put("BTC", 70_000.0);
		tick();
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
		assertEquals(spot, acct.spotEquity(), 1e-6);
	}

	// ---------------------------------------------------------------- isolated

	@Test
	void isolatedHoldsItsOwnMarginAndLiquidatesOnItAlone() {
		assertTrue(acct.setLeverage("BTC", 10, false).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 40_000)).ok());
		double margin = acct.state().positions.get("BTC").margin;
		assertEquals(4_000, margin, 1);
		double freeBefore = acct.available("");
		assertTrue(freeBefore < 6_000 && freeBefore > 5_900, "free " + freeBefore);
		// a backstop on the isolated position takes its margin, and only its margin
		double balance = acct.state().balance;
		mids.put("BTC", 70_000.0);
		List<String> events = acct.tick(System.currentTimeMillis());
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
		assertTrue(events.stream().anyMatch(e -> e.contains("backstop")), events.toString());
		assertEquals(balance - margin, acct.state().balance, 1e-6);
		assertEquals(balance - margin, acct.perpEquity(), 1e-6);
	}

	@Test
	void anIsolatedLossDoesntTouchACrossPosition() {
		assertTrue(acct.setLeverage("BTC", 10, false).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 20_000)).ok());
		assertTrue(submit(market("ETH", Side.BUY, 4_000)).ok());
		mids.put("BTC", 72_000.0);
		tick();
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
		assertEquals(2, acct.positionSize("ETH"), 1e-9, "the cross position's still open");
	}

	@Test
	void addingAndRemovingIsolatedMargin() {
		assertFalse(acct.adjustMargin("BTC", 100).join().ok(), "no position");
		assertTrue(submit(market("ETH", Side.BUY, 2_000)).ok());
		assertFalse(acct.adjustMargin("ETH", 100).join().ok(), "cross has no margin of its own");
		assertTrue(acct.setLeverage("BTC", 10, false).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 40_000)).ok());
		double liqBefore = acct.liquidationPrice("BTC");
		assertTrue(acct.adjustMargin("BTC", 1_000).join().ok());
		assertEquals(5_000, acct.state().positions.get("BTC").margin, 1);
		assertTrue(acct.liquidationPrice("BTC") < liqBefore, "more margin, further away");
		assertFalse(acct.adjustMargin("BTC", 1_000_000).join().ok(), "more than is free");
		assertTrue(acct.adjustMargin("BTC", -900).join().ok());
		assertFalse(acct.adjustMargin("BTC", -500).join().ok(), "can't go under initial margin");
		assertTrue(row("BTC").get("removable").getAsDouble() < 500);
	}

	@Test
	void closingIsolatedPaysItsMarginAndProfitBack() {
		assertTrue(acct.setLeverage("BTC", 10, false).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 40_000)).ok());
		mids.put("BTC", 84_000.0);
		assertTrue(submit(market("BTC", Side.CLOSE, 0)).ok());
		assertEquals(0, acct.marginUsed(), 1e-9);
		assertTrue(acct.available("") > 11_900, "margin and profit back: " + acct.available(""));
	}

	@Test
	void switchingAnOpenPositionBetweenCrossAndIsolated() {
		assertTrue(acct.setLeverage("BTC", 10, true).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 40_000)).ok());
		assertTrue(acct.setLeverage("BTC", 10, false).join().ok());
		assertEquals(4_000, acct.state().positions.get("BTC").margin, 1);
		assertTrue(acct.setLeverage("BTC", 10, true).join().ok());
		assertEquals(0, acct.state().positions.get("BTC").margin, 1e-9);
	}

	// ---------------------------------------------------------------- withdrawals, marks, funding

	@Test
	void profitLeavesOnlyAboveTheTenPercentRule() {
		assertTrue(acct.setLeverage("BTC", 40, true).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 80_000)).ok());
		// initial margin 2,000; free cash is what's above it, losses counted
		double upnl = acct.unrealizedPnl("BTC");
		assertTrue(upnl < 0, "the fill slipped");
		double cash = acct.state().balance + upnl - 2_000;
		assertEquals(Math.floor(cash * 100) / 100, acct.withdrawable(), 1e-6);
		// a small profit: cash is what's bigger, with profit not counted, since 10% of value (8,100) is more than initial margin
		mids.put("BTC", 81_000.0);
		upnl = acct.unrealizedPnl("BTC");
		double cashNow = acct.state().balance - 81_000 / 40.0;
		assertTrue(cashNow > acct.state().balance + upnl - 8_100);
		assertEquals(Math.floor(cashNow * 100) / 100, acct.withdrawable(), 1e-6);
		// a big win: now it's the account value above 10% of position value
		mids.put("BTC", 130_000.0);
		upnl = acct.unrealizedPnl("BTC");
		double big = acct.state().balance + upnl - 13_000;
		assertTrue(big > acct.state().balance - 130_000 / 40.0);
		assertEquals(Math.floor(big * 100) / 100, acct.withdrawable(), 1e-6);
		assertTrue(acct.available("") > big, "more can back a new trade than can leave");
	}

	@Test
	void pnlAndTriggersRunOnTheMarkWhileFillsAreAtTheMid() {
		assertTrue(submit(market("BTC", Side.BUY, 8_000)).ok());
		OrderRequest stop = market("BTC", Side.CLOSE, 0);
		stop.type = OrderRequest.Type.STOP;
		stop.price = 75_000;
		assertTrue(submit(stop).ok());
		double entry = acct.state().positions.get("BTC").entry;
		marks.put("BTC", 76_000.0);
		assertEquals(0.1 * (76_000 - entry), acct.unrealizedPnl("BTC"), 1e-6);
		// the mid's through the stop, but the mark isn't
		mids.put("BTC", 74_000.0);
		tick();
		assertEquals(0.1, acct.positionSize("BTC"), 1e-9);
		// the mark gets there: fills at the mid
		marks.put("BTC", 74_900.0);
		tick();
		assertEquals(0, acct.positionSize("BTC"), 1e-9);
		assertEquals(74_000, acct.state().fills.peekFirst().price(), 74_000 * 0.001);
	}

	@Test
	void fundingIsPaidOnTheOracleAndCapped() {
		assertTrue(submit(market("BTC", Side.BUY, 8_000)).ok());
		oracles.put("BTC", 100_000.0);
		fundingRate = 0.0001;
		long now = System.currentTimeMillis();
		acct.state().lastFundingHour = now / 3_600_000L - 1;
		double before = acct.state().balance;
		acct.tick(now);
		assertEquals(-1.0, acct.state().balance - before, 1e-6);
		assertEquals(-1.0, row("BTC").get("funding").getAsDouble(), 1e-6);
		assertEquals(0.1, acct.snapshot().getAsJsonArray("funding").get(0).getAsJsonObject().get("size").getAsDouble(), 1e-9);
		fundingRate = 0.5;
		acct.state().lastFundingHour = now / 3_600_000L - 1;
		before = acct.state().balance;
		acct.tick(now);
		assertEquals(-0.1 * 100_000 * 0.04, acct.state().balance - before, 1e-6);
	}

	// ---------------------------------------------------------------- dex balances

	@Test
	void inManualADexMarginsFromItsOwnBalance() {
		OrderResult r = submit(market(GOLD, Side.BUY, 1_000));
		assertFalse(r.ok(), "no USDC on xyz yet");
		assertTrue(r.message().contains("xyz"), r.message());
		assertNotNull(acct.transferDex(100_000, "xyz", true));
		assertNotNull(acct.transferDex(100, "nosuchdex", true));
		assertNull(acct.transferDex(2_000, "xyz", true));
		assertEquals(8_000, acct.withdrawable(), 1e-6);
		assertTrue(submit(market(GOLD, Side.BUY, 1_000)).ok());
		// the perps account isn't touched by what xyz does
		assertEquals(10_000, acct.equity(), 2);
		assertEquals(8_000, acct.available(""), 1e-6);
		assertTrue(acct.available("xyz") < 2_000);
		assertNull(acct.transferDex(1_000, "xyz", false));
		assertEquals(9_000, acct.withdrawable(), 1e-6);
	}

	@Test
	void aDexLiquidatesWithoutTouchingThePerpsAccount() {
		assertNull(acct.transferDex(1_000, "xyz", true));
		assertTrue(acct.setLeverage(GOLD, 20, true).join().ok());
		assertTrue(submit(market(GOLD, Side.BUY, 16_000)).ok());
		assertTrue(submit(market("BTC", Side.BUY, 8_000)).ok());
		mids.put(GOLD, 3_600.0);
		tick();
		assertEquals(0, acct.positionSize(GOLD), 1e-9);
		assertEquals(0.1, acct.positionSize("BTC"), 1e-9);
		assertEquals(0, acct.available("xyz"), 1e-6);
		assertTrue(acct.available("") > 4_000);
	}

	@Test
	void unifiedSharesOneBalanceAcrossDexes() {
		assertNull(acct.transferDex(1_000, "xyz", true));
		assertNull(acct.transfer(500, false));
		acct.setUnified(true);
		assertEquals(10_000, acct.state().balance, 1e-6);
		assertNotNull(acct.transferDex(100, "xyz", true));
		assertTrue(submit(market(GOLD, Side.BUY, 1_000)).ok());
		assertTrue(submit(market("BTC", Side.BUY, 1_000)).ok());
		// back to Manual, the dex's open position is funded rather than left with nothing
		acct.setUnified(false);
		tick();
		assertEquals(0.25, acct.positionSize(GOLD), 1e-9);
		assertTrue(acct.state().dexUsdc.get("xyz") > 400);
	}

	@Test
	void anOldSavesDexPositionsAreFundedNotLiquidated() {
		PaperAccount.State old = PaperAccount.fresh();
		old.dexSplit = false;
		old.balance = 10_000;
		PaperAccount.Pos gold = new PaperAccount.Pos();
		gold.size = 1;
		gold.entry = 4_000;
		old.positions.put(GOLD, gold);
		old.leverage.put(GOLD, 2);
		PaperAccount a = new PaperAccount(old, () -> quotes);
		a.tick(System.currentTimeMillis());
		assertEquals(1, a.positionSize(GOLD), 1e-9);
		assertEquals(2_000, old.dexUsdc.get("xyz"), 1e-6);
		assertEquals(8_000, old.balance, 1e-6);
		assertEquals(10_000, a.equity(), 1e-6);
	}

	// ---------------------------------------------------------------- fees and history

	@Test
	void spotAndPerpFeesDifferAndMakersPayLess() {
		assertTrue(submit(market("BTC", Side.BUY, 10_000)).ok());
		PaperAccount.Fill perp = acct.state().fills.peekFirst();
		assertEquals(0.00045, perp.fee() / (Math.abs(perp.size()) * perp.price()), 1e-9);
		assertNull(acct.transfer(2_000, false));
		assertTrue(submit(market(HYPE, Side.BUY, 1_000)).ok());
		PaperAccount.Fill spot = acct.state().fills.peekFirst();
		assertEquals(0.0007, spot.fee() / (Math.abs(spot.size()) * spot.price()), 1e-9);
		OrderRequest limit = market("ETH", Side.BUY, 1_000);
		limit.type = OrderRequest.Type.LIMIT;
		limit.price = 1_900;
		assertTrue(submit(limit).ok());
		mids.put("ETH", 1_890.0);
		tick();
		PaperAccount.Fill maker = acct.state().fills.peekFirst();
		assertEquals(0.00015, maker.fee() / (Math.abs(maker.size()) * maker.price()), 1e-9);
	}

	@Test
	void feeTiersComeWithVolumeAndSpotCountsDouble() {
		assertEquals(0, FeeSchedule.tier(5e6));
		assertEquals(1, FeeSchedule.tier(5e6 + 1));
		assertEquals(6, FeeSchedule.tier(8e9));
		acct.deposit(1_000_000);
		assertTrue(acct.setLeverage("BTC", 40, true).join().ok());
		for (int i = 0; i < 3; i++) {
			assertTrue(submit(market("BTC", Side.BUY, 1_000_000)).ok());
			assertTrue(submit(market("BTC", Side.CLOSE, 0)).ok());
		}
		assertTrue(acct.volume14d(System.currentTimeMillis()) > 5e6);
		List<String> events = acct.tick(System.currentTimeMillis());
		assertTrue(events.stream().anyMatch(e -> e.contains("Fee tier 1")), events.toString());
		assertEquals(1, acct.snapshot().getAsJsonObject("feeInfo").get("tier").getAsInt());
		assertEquals(0.0004, acct.snapshot().getAsJsonObject("feeInfo").get("perpTaker").getAsDouble(), 1e-9);
		// two weeks later the volume's out of the window
		assertEquals(0, acct.volume14d(System.currentTimeMillis() + 15L * 86_400_000L), 1e-9);
	}

	@Test
	void historyKeepsOrdersAndFillsWithFees() {
		assertTrue(submit(market("BTC", Side.BUY, 8_000)).ok());
		OrderRequest limit = market("ETH", Side.BUY, 1_000);
		limit.type = OrderRequest.Type.LIMIT;
		limit.price = 1_500;
		assertTrue(submit(limit).ok());
		long oid = acct.snapshot().getAsJsonArray("orders").get(0).getAsJsonObject().get("oid").getAsLong();
		assertTrue(acct.cancel("ETH", oid).join().ok());
		assertFalse(submit(market("BTC", Side.BUY, 100_000_000)).ok());
		var hist = acct.history().getAsJsonArray("orderHistory");
		assertEquals("Rejected", hist.get(0).getAsJsonObject().get("status").getAsString());
		assertEquals("Cancelled", hist.get(1).getAsJsonObject().get("status").getAsString());
		assertEquals("Filled", hist.get(2).getAsJsonObject().get("status").getAsString());
		JsonObject fill = acct.history().getAsJsonArray("fills").get(0).getAsJsonObject();
		assertEquals(8_000, fill.get("value").getAsDouble(), 10);
		assertTrue(fill.get("fee").getAsDouble() > 3);
		// closed PnL has the fee in it
		mids.put("BTC", 80_000.0);
		assertTrue(submit(market("BTC", Side.CLOSE, 0)).ok());
		PaperAccount.Fill close = acct.state().fills.peekFirst();
		assertTrue(close.pnl() < 0, "flat price, so the fee's the loss: " + close.pnl());
	}

	@Test
	void historyGoesDeeperThanTheSnapshot() {
		acct.deposit(1_000_000);
		for (int i = 0; i < 30; i++) {
			assertTrue(submit(market("BTC", Side.BUY, 1_000)).ok());
			assertTrue(submit(market("BTC", Side.CLOSE, 0)).ok());
		}
		assertEquals(40, acct.snapshot().getAsJsonArray("fills").size());
		assertEquals(60, acct.history().getAsJsonArray("fills").size());
	}

	// ---------------------------------------------------------------- portfolio margin

	@Test
	void portfolioMarginCountsCollateralAtItsLtv() {
		assertNotNull(new PaperAccount(PaperAccount.fresh(), () -> quotes).setPortfolioMargin(true), "needs $10k");
		acct.deposit(1_000);
		assertNull(acct.transfer(8_000, false));
		assertTrue(submit(market(HYPE, Side.BUY, 7_900)).ok());
		double held = acct.spotBalance(HYPE) * 80;
		assertNull(acct.setPortfolioMargin(true));
		assertTrue(acct.unified());
		// USDC plus 65% of the HYPE
		assertEquals(acct.state().balance + held * 0.65, acct.available(""), 1e-6);
		assertTrue(acct.setLeverage("BTC", 10, true).join().ok());
		// more than the USDC alone could margin
		assertTrue(submit(market("BTC", Side.BUY, 50_000)).ok());
		// collateral buys no spot and doesn't leave
		assertFalse(submit(market(HYPE, Side.BUY, 5_000)).ok());
		assertEquals(0, acct.withdrawable(), 1e-9);
	}

	@Test
	void portfolioMarginLiquidatesInStepsAt95Percent() {
		acct.deposit(1_000);
		assertNull(acct.transfer(8_000, false));
		assertTrue(submit(market(HYPE, Side.BUY, 7_900)).ok());
		assertNull(acct.setPortfolioMargin(true));
		assertTrue(acct.setLeverage("BTC", 20, true).join().ok());
		assertTrue(submit(market("BTC", Side.BUY, 120_000)).ok());
		long now = System.currentTimeMillis();
		mids.put("BTC", 75_000.0);
		mids.put(HYPE, 10.0);
		List<String> events = acct.tick(now);
		assertTrue(events.stream().anyMatch(e -> e.contains("backstop")), events.toString());
		assertEquals(1.2, acct.positionSize("BTC"), 1e-6, "a fifth went");
		assertEquals("Backstop Liquidation", acct.state().fills.peekFirst().kind());
		acct.tick(now + 1_000);
		assertEquals(1.2, acct.positionSize("BTC"), 1e-6, "and it waits");
	}
}
