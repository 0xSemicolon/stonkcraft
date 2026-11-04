package dev.hypercraft.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hypercraft.market.MarketData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Client order ids on paper accounts: status tracking, amend in place, cancel by id. */
class PaperOrderIdsTest {
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
	private PaperAccount acct;
	private final String prefix = Cloids.blockPrefix("minecraft:overworld", 12345L);

	@BeforeEach
	void setUp() {
		acct = new PaperAccount(PaperAccount.fresh(), () -> quotes);
		acct.deposit(10_000);
	}

	private OrderRequest limit(double offsetPct, double usd) {
		OrderRequest r = OrderRequest.market("BTC", Side.BUY, usd);
		r.type = OrderRequest.Type.LIMIT;
		r.offsetPct = offsetPct;
		r.cloid = Cloids.next(prefix);
		return r;
	}

	@Test
	void cloidsAreStableAndOwned() {
		String id = Cloids.next(prefix);
		assertTrue(Cloids.valid(id));
		assertTrue(Cloids.ownedBy(id, prefix));
		assertEquals(prefix, Cloids.blockPrefix("minecraft:overworld", 12345L));
		assertFalse(Cloids.ownedBy(id, Cloids.blockPrefix("minecraft:overworld", 12346L)));
		assertFalse(Cloids.ownedBy(id, Cloids.playerPrefix(UUID.randomUUID())));
	}

	@Test
	void statusFollowsTheOrderThroughItsLife() {
		OrderRequest r = limit(-1, 1_000);
		assertEquals(OrderStatus.NONE, acct.orderStatus(r.cloid));
		assertTrue(acct.submit(r).join().ok());
		assertEquals(OrderStatus.RESTING, acct.orderStatus(r.cloid));
		mids.put("BTC", 79_000.0);
		acct.tick(System.currentTimeMillis());
		assertEquals(OrderStatus.FILLED, acct.orderStatus(r.cloid));

		OrderRequest c = limit(-1, 1_000);
		acct.submit(c).join();
		assertTrue(acct.cancelByCloid("BTC", c.cloid).join().ok());
		assertEquals(OrderStatus.CANCELLED, acct.orderStatus(c.cloid));

		OrderRequest d = limit(-1, 1_000);
		acct.submit(d).join();
		acct.cancelAll(null).join();
		assertEquals(OrderStatus.CANCELLED, acct.orderStatus(d.cloid));

		OrderRequest bad = limit(-1, 5_000_000); // far beyond margin at any leverage
		bad.type = OrderRequest.Type.MARKET;
		assertFalse(acct.submit(bad).join().ok());
		assertEquals(OrderStatus.REJECTED, acct.orderStatus(bad.cloid));
	}

	@Test
	void amendKeepsQueuePlaceAndMovesBrackets() {
		OrderRequest first = limit(-2, 1_000);
		first.tpPct = 5;
		first.slPct = 2;
		acct.submit(first).join();
		OrderRequest other = limit(-3, 1_000);
		acct.submit(other).join();
		JsonOrders before = new JsonOrders(acct);
		long oid = before.oidAt(0);

		OrderRequest moved = limit(-1, 2_000);
		moved.tpPct = 5;
		moved.slPct = 2;
		OrderResult res = acct.modify(first.cloid, moved).join();
		assertTrue(res.ok(), res.message());
		JsonOrders after = new JsonOrders(acct);
		assertEquals(oid, after.oidAt(0)); // same order, same place in the list
		assertEquals(79_200, after.pxAt(0), 1);
		assertEquals(2_000 / 79_200.0, after.sizeAt(0), 1e-4);
		// bracket children follow the new entry price
		assertEquals(79_200 * 1.05, after.pxOf("Take profit"), 2);
		assertEquals(79_200 * 0.98, after.pxOf("Stop"), 2);
		assertEquals(OrderStatus.RESTING, acct.orderStatus(first.cloid));
	}

	@Test
	void amendThatCrossesFillsAndUnknownIdsFail() {
		OrderRequest r = limit(-1, 1_000);
		acct.submit(r).join();
		OrderRequest through = limit(1, 1_000); // above the mid: crosses
		OrderResult res = acct.modify(r.cloid, through).join();
		assertTrue(res.ok(), res.message());
		assertEquals(OrderStatus.FILLED, acct.orderStatus(r.cloid));
		assertTrue(acct.positionSize("BTC") > 0);
		assertFalse(acct.modify(Cloids.next(prefix), through).join().ok());
		OrderRequest mkt = limit(0, 1_000);
		mkt.type = OrderRequest.Type.MARKET;
		OrderRequest rest = limit(-1, 1_000);
		acct.submit(rest).join();
		assertFalse(acct.modify(rest.cloid, mkt).join().ok()); // can't turn a limit into a market order
	}

	/** Small reader over the snapshot's order rows. */
	private record JsonOrders(com.google.gson.JsonArray rows) {
		JsonOrders(PaperAccount a) {
			this(a.snapshot().getAsJsonArray("orders"));
		}

		long oidAt(int i) {
			return rows.get(i).getAsJsonObject().get("oid").getAsLong();
		}

		double pxAt(int i) {
			return rows.get(i).getAsJsonObject().get("px").getAsDouble();
		}

		double sizeAt(int i) {
			return rows.get(i).getAsJsonObject().get("size").getAsDouble();
		}

		double pxOf(String type) {
			for (var el : rows) if (el.getAsJsonObject().get("type").getAsString().equals(type)) return el.getAsJsonObject().get("px").getAsDouble();
			return Double.NaN;
		}
	}
}
