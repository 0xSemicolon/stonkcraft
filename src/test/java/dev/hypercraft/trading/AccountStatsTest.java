package dev.hypercraft.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hypercraft.market.MarketData;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The figures shared by Account Displays and the sensor's account modes, plus watch-only refusals. */
class AccountStatsTest {
	private final Map<String, Double> mids = new HashMap<>(Map.of("BTC", 80_000.0));
	private final MarketData.Asset btc = new MarketData.Asset("BTC", 0, 5, 40);
	private final PaperAccount.Quotes quotes = new PaperAccount.Quotes() {
		@Override
		public MarketData.Asset asset(String coin) {
			return coin.equals("BTC") ? btc : null;
		}

		@Override
		public double mid(String coin) {
			return mids.getOrDefault(coin, Double.NaN);
		}

		@Override
		public double funding(String coin) {
			return 0;
		}
	};

	@Test
	void figuresTrackAPaperPosition() {
		PaperAccount acct = new PaperAccount(PaperAccount.fresh(), () -> quotes);
		acct.deposit(10_000);
		assertTrue(acct.setLeverage("BTC", 10, true).join().ok());
		assertTrue(acct.submit(OrderRequest.market("BTC", Side.LONG, 8_000)).join().ok());
		mids.put("BTC", 84_000.0); // +5%

		AccountStats st = new AccountStats(acct);
		double upnl = st.value(AccountMetric.UPNL, "");
		assertEquals(400, upnl, 15); // ~0.1 BTC * 4000, less slippage
		assertEquals(st.equity - 10_000, st.value(AccountMetric.PNL, ""), 1e-6);
		assertTrue(st.value(AccountMetric.PNL, "") < upnl); // fees come off PnL
		assertEquals(st.value(AccountMetric.PNL, "") / 100, st.value(AccountMetric.RETURN, ""), 1e-6);
		assertEquals(1, st.value(AccountMetric.POSITIONS, ""));
		assertEquals(st.gross / st.equity, st.value(AccountMetric.LEVERAGE, ""), 1e-9);
		assertTrue(st.value(AccountMetric.MARGIN_USAGE, "") > 7 && st.value(AccountMetric.MARGIN_USAGE, "") < 10);
		assertTrue(st.value(AccountMetric.POSITION, "BTC") > 0.09);
		assertEquals(0, st.value(AccountMetric.POSITION, ""));
		assertTrue(((String) st.display(AccountMetric.POSITION, "BTC")[0]).startsWith("LONG"));
		assertEquals(1, st.display(AccountMetric.UPNL, "")[2]);
	}

	@Test
	void watchOnlyAccountsRefuseEveryAction() {
		WatchAccount watch = new WatchAccount(null, "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303", null);
		OrderResult r = watch.submit(OrderRequest.market("BTC", Side.BUY, 100)).join();
		assertFalse(r.ok());
		assertTrue(r.message().contains("Watch-only"));
		assertFalse(watch.cancelAll(null).join().ok());
		assertFalse(watch.setLeverage("BTC", 5, true).join().ok());
		assertFalse(watch.scheduleCancel(System.currentTimeMillis() + 60_000).join().ok());
	}
}
