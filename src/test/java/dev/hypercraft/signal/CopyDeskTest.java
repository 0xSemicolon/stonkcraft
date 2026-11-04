package dev.hypercraft.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CopyDeskTest {
	private static final SignalValue.Portfolio WHALE = new SignalValue.Portfolio(Map.of("BTC", 2.0, "ETH", -1.0), 1_000_000);

	private static CopyDesk.Plan plan(CopyDesk d, SignalValue.Portfolio target, double equity, Map<String, double[]> current) {
		return d.plan(target, equity, current, Map.of(), c -> 40, 4);
	}

	@Test
	void proportionalSizesOffTheFollowersEquity() {
		CopyDesk d = new CopyDesk();
		d.maxLeverage = 10;
		d.maxStep = 1e9;
		CopyDesk.Plan p = plan(d, WHALE, 10_000, Map.of());
		assertEquals(20_000, p.targets().get("BTC"), 1e-6);
		assertEquals(-10_000, p.targets().get("ETH"), 1e-6);
		assertEquals(2, p.trades().size());
		assertEquals(20_000, p.trades().get(0).usd(), 1e-6);
		assertEquals("BTC", p.trades().get(0).coin());
	}

	@Test
	void mirrorUsesTheLeadersSizeTimesRatioAndTheLeverageCap() {
		CopyDesk d = new CopyDesk();
		d.sizing = CopyDesk.Sizing.MIRROR;
		d.ratio = 0.01;
		d.maxLeverage = 2;
		d.maxStep = 1e9;
		CopyDesk.Plan p = plan(d, WHALE, 10_000, Map.of());
		// 1% of a $2M/$1M book is $20k/$10k: $30k gross, capped to 2x of $10k
		double gross = Math.abs(p.targets().get("BTC")) + Math.abs(p.targets().get("ETH"));
		assertEquals(20_000, gross, 1e-6);
		assertEquals(2.0, p.targets().get("BTC") / -p.targets().get("ETH"), 1e-9);
	}

	@Test
	void smallDifferencesAreIgnoredAndStepsAreCapped() {
		CopyDesk d = new CopyDesk();
		d.maxLeverage = 10;
		d.maxStep = 5000;
		Map<String, double[]> cur = new HashMap<>();
		cur.put("BTC", new double[]{0.1, 80_000});
		cur.put("ETH", new double[]{-3.3, 3000});
		CopyDesk.Plan p = plan(d, WHALE, 10_000, cur);
		// ETH -9900 vs -10000 is inside the 2% band
		assertEquals(1, p.trades().size());
		assertEquals("BTC", p.trades().get(0).coin());
		assertEquals(5000, p.trades().get(0).usd(), 1e-9);
	}

	@Test
	void shrinkingIsReduceOnlyAndFlippingClosesFirst() {
		CopyDesk d = new CopyDesk();
		d.maxLeverage = 10;
		d.maxStep = 1e9;
		Map<String, double[]> cur = Map.of("BTC", new double[]{0.5, 80_000});
		CopyDesk.Plan shrink = plan(d, new SignalValue.Portfolio(Map.of("BTC", 1.0), 1), 10_000, cur);
		assertTrue(shrink.trades().get(0).reduceOnly());
		assertEquals(-30_000, shrink.trades().get(0).usd(), 1e-6);
		CopyDesk.Plan flip = plan(d, new SignalValue.Portfolio(Map.of("BTC", -1.0), 1), 10_000, cur);
		assertTrue(flip.trades().get(0).reduceOnly());
		assertEquals(-40_000, flip.trades().get(0).usd(), 1e-6);
		CopyDesk.Plan exit = plan(d, SignalValue.Portfolio.empty(1), 10_000, cur);
		assertTrue(exit.trades().get(0).reduceOnly());
	}

	@Test
	void leverageIsRaisedBeforeOpening() {
		CopyDesk d = new CopyDesk();
		d.maxLeverage = 3;
		d.maxStep = 1e9;
		CopyDesk.Plan p = d.plan(WHALE, 10_000, Map.of(), Map.of("BTC", 1), c -> 40, 4);
		assertEquals(4, p.leverage().get(0).leverage());
	}

	@Test
	void pauses() {
		CopyDesk d = new CopyDesk();
		assertEquals("stopped", d.paused());
		d.running = true;
		d.redstone = CopyDesk.Redstone.RUN;
		assertEquals("waiting for redstone", d.paused());
		d.powered = true;
		assertEquals(null, d.paused());
	}
}
