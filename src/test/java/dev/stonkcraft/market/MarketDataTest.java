package dev.stonkcraft.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stonkcraft.hl.HyperliquidApi;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** HIP-3 markets resolve by bare ticker unless a main-dex coin owns the name; Unit tokens track their reference perp. */
class MarketDataTest {
	private static MarketData md() {
		return new MarketData(new HyperliquidApi("https://example.invalid"));
	}

	@Test
	void bareTickersFindXyzMarkets() {
		MarketData md = md();
		md.putForTest(new MarketData.Asset("BTC", 0, 5, 40), 80_000);
		md.putForTest(new MarketData.Asset("xyz:GOLD", 110_003, 4, 25, false, "xyz:GOLD", "GOLD", "xyz"), 4100);
		md.putForTest(new MarketData.Asset("xyz:BTC", 110_009, 4, 25, false, "xyz:BTC", "BTC", "xyz"), 1);
		assertEquals("xyz:GOLD", md.resolve("gold"));
		assertEquals("xyz:GOLD", md.resolve("XYZ:gold"));
		assertEquals("BTC", md.resolve("btc"), "a main-dex coin keeps its name");
	}

	@Test
	void thousandsMarketsResolveByTheirCoin() {
		MarketData md = md();
		md.putForTest(new MarketData.Asset("kPEPE", 98, 0, 10), 0.0045);
		assertEquals("kPEPE", md.resolve("kpepe"));
		assertEquals("kPEPE", md.resolve("pepe"));
	}

	@Test
	void unitTokensArePricedOffTheirReference() {
		MarketData md = md();
		md.putForTest(new MarketData.Asset("xyz:GOLD", 110_003, 4, 25, false, "xyz:GOLD", "GOLD", "xyz"), 4100);
		md.setUnits(Map.of("UGOLD", "xyz:GOLD"));
		md.refreshUnitsForTest();
		MarketData.Asset u = md.asset("ugold");
		assertEquals("UGOLD/USDC", u.name());
		assertTrue(u.spot() && u.unit());
		assertEquals("xyz:GOLD", u.apiCoin());
		assertEquals(4100, md.mid("UGOLD/USDC"), 1e-9);
	}
}
