package dev.hypercraft.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hypercraft.market.MarketData;
import org.junit.jupiter.api.Test;

class OrderPlannerTest {
	private static final MarketData.Asset BTC = new MarketData.Asset("BTC", 0, 5, 40);
	private static final MarketData.Asset HYPE_SPOT = new MarketData.Asset("HYPE/USDC", 10107, 2, 1, true, "@107", "HYPE");

	private static OrderRequest req(Side side, double usd, OrderRequest.Type type) {
		OrderRequest r = OrderRequest.market("BTC", side, usd);
		r.type = type;
		return r;
	}

	@Test
	void marketBuyCapsPriceWithSlippage() throws Exception {
		var p = OrderPlanner.plan(req(Side.BUY, 8000, OrderRequest.Type.MARKET), BTC, 80000, 0, 0.01);
		assertTrue(p.entry().isBuy());
		assertEquals(0.1, p.entry().size(), 1e-12);
		assertEquals(80800, p.entry().limitPx(), 1e-9);
		assertTrue(p.entry().immediate());
	}

	@Test
	void limitUsesOffsetFromMidAndSizesAtLimitPrice() throws Exception {
		OrderRequest r = req(Side.BUY, 7600, OrderRequest.Type.LIMIT);
		r.offsetPct = -5;
		var p = OrderPlanner.plan(r, BTC, 80000, 0, 0.01);
		assertEquals(76000, p.entry().limitPx(), 1e-9);
		assertEquals(0.1, p.entry().size(), 1e-12);
		assertFalse(p.entry().immediate());
	}

	@Test
	void stopSellTriggersBelowAndIsReduceOnlyWhenAsked() throws Exception {
		OrderRequest r = req(Side.CLOSE, 0, OrderRequest.Type.STOP);
		r.price = 75000;
		var p = OrderPlanner.plan(r, BTC, 80000, 0.2, 0.01);
		assertTrue(p.entry().trigger());
		assertEquals("sl", p.entry().tpsl());
		assertFalse(p.entry().isBuy());
		assertTrue(p.entry().reduceOnly());
		assertEquals(0.2, p.entry().size(), 1e-12);
		assertEquals(75000, p.entry().triggerPx(), 1e-9);
	}

	@Test
	void bracketLegsSitOnTheRightSides() throws Exception {
		OrderRequest r = req(Side.SHORT, 8000, OrderRequest.Type.MARKET);
		r.tpPct = 10;
		r.slPct = 5;
		var p = OrderPlanner.plan(r, BTC, 80000, 0, 0.01);
		assertFalse(p.entry().isBuy());
		assertEquals(72000, p.tp().triggerPx(), 1e-9);
		assertEquals(84000, p.sl().triggerPx(), 1e-9);
		assertTrue(p.tp().isBuy() && p.tp().reduceOnly());
		assertEquals(p.entry().size(), p.sl().size(), 1e-12);
	}

	@Test
	void noBracketOnReduceOnly() throws Exception {
		OrderRequest r = req(Side.CLOSE, 0, OrderRequest.Type.MARKET);
		r.tpPct = 10;
		var p = OrderPlanner.plan(r, BTC, 80000, -0.1, 0.01);
		assertNull(p.tp());
		assertTrue(p.entry().isBuy());
	}

	@Test
	void spotRules() throws Exception {
		OrderRequest buy = OrderRequest.market("HYPE/USDC", Side.BUY, 100);
		var p = OrderPlanner.plan(buy, HYPE_SPOT, 80, 0, 0.01);
		assertEquals(1.25, p.entry().size(), 1e-12);
		assertFalse(p.entry().reduceOnly());
		OrderRequest sell = OrderRequest.market("HYPE/USDC", Side.SELL, 1000);
		assertEquals(3.0, OrderPlanner.plan(sell, HYPE_SPOT, 80, 3.0, 0.01).entry().size(), 1e-12);
		assertThrows(OrderPlanner.PlanException.class, () -> OrderPlanner.plan(OrderRequest.market("HYPE/USDC", Side.SHORT, 10), HYPE_SPOT, 80, 0, 0.01));
		OrderRequest stop = OrderRequest.market("HYPE/USDC", Side.SELL, 10);
		stop.type = OrderRequest.Type.STOP;
		assertThrows(OrderPlanner.PlanException.class, () -> OrderPlanner.plan(stop, HYPE_SPOT, 80, 3, 0.01));
	}

	@Test
	void goLongIsIdempotent() {
		assertThrows(OrderPlanner.PlanException.class, () -> OrderPlanner.plan(req(Side.LONG, 1000, OrderRequest.Type.MARKET), BTC, 80000, 0.5, 0.01));
	}
}
