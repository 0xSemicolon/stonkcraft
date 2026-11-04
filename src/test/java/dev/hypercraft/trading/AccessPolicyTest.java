package dev.hypercraft.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hypercraft.market.MarketData;
import dev.hypercraft.registry.ModComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Who may trade, view or manage an account, through every path that reaches it. */
class AccessPolicyTest {
	private final UUID owner = UUID.randomUUID();
	private final UUID alice = UUID.randomUUID(); // trader with limits
	private final UUID bob = UUID.randomUUID();   // viewer
	private final UUID mallory = UUID.randomUUID(); // nobody
	private Account acct;
	private final long now = 1_000_000_000L;

	@BeforeEach
	void setUp() {
		acct = new Account();
		acct.id = UUID.randomUUID();
		acct.name = "Fund";
		acct.owner = owner;
		acct.ownerName = "Owner";
		acct.kind = Account.Kind.PAPER;
		acct.upgrade();
		Account.Grant a = new Account.Grant();
		a.player = alice;
		a.name = "Alice";
		a.role = Account.Role.TRADE;
		a.maxOrderUsd = 500;
		a.maxLeverage = 5;
		a.markets.add("BTC");
		acct.grants.add(a);
		Account.Grant b = new Account.Grant();
		b.player = bob;
		b.name = "Bob";
		b.role = Account.Role.VIEW;
		acct.grants.add(b);
	}

	private static OrderRequest order(String coin, Side side, double usd) {
		return OrderRequest.market(coin, side, usd);
	}

	@Test
	void levels() {
		assertEquals(AccessPolicy.Level.OWNER, AccessPolicy.level(acct, owner, now));
		assertEquals(AccessPolicy.Level.TRADE, AccessPolicy.level(acct, alice, now));
		assertEquals(AccessPolicy.Level.VIEW, AccessPolicy.level(acct, bob, now));
		assertEquals(AccessPolicy.Level.NONE, AccessPolicy.level(acct, mallory, now));
		assertEquals(AccessPolicy.Level.NONE, AccessPolicy.level(acct, null, now));
	}

	@Test
	void ownerTradesFreelyOthersWithinLimits() {
		assertNull(AccessPolicy.checkOrder(acct, owner, order("ETH", Side.BUY, 1_000_000), now));
		assertNull(AccessPolicy.checkOrder(acct, alice, order("BTC", Side.BUY, 500), now));
		assertNotNull(AccessPolicy.checkOrder(acct, alice, order("BTC", Side.BUY, 501), now)); // over the size cap
		assertNotNull(AccessPolicy.checkOrder(acct, alice, order("ETH", Side.BUY, 100), now)); // market not granted
		assertNull(AccessPolicy.checkOrder(acct, alice, order("BTC", Side.CLOSE, 0), now)); // closing ignores the size cap
		assertNotNull(AccessPolicy.checkOrder(acct, bob, order("BTC", Side.BUY, 10), now)); // viewer
		assertNotNull(AccessPolicy.checkOrder(acct, mallory, order("BTC", Side.BUY, 10), now));
		assertNull(AccessPolicy.checkLeverage(acct, alice, "BTC", 5, now));
		assertNotNull(AccessPolicy.checkLeverage(acct, alice, "BTC", 6, now));
		assertNotNull(AccessPolicy.checkLeverage(acct, alice, "ETH", 2, now));
		assertNull(AccessPolicy.checkLeverage(acct, owner, "ETH", 40, now));
	}

	@Test
	void reduceOnlyAndExpiry() {
		Account.Grant a = acct.grant(alice);
		a.reduceOnly = true;
		assertNotNull(AccessPolicy.checkOrder(acct, alice, order("BTC", Side.BUY, 100), now));
		OrderRequest ro = order("BTC", Side.SELL, 100);
		ro.reduceOnly = true;
		assertNull(AccessPolicy.checkOrder(acct, alice, ro, now));
		a.expiresAt = now;
		assertEquals(AccessPolicy.Level.NONE, AccessPolicy.level(acct, alice, now));
		assertTrue(AccessPolicy.checkOrder(acct, alice, ro, now).contains("expired"));
		assertNotNull(AccessPolicy.checkCancel(acct, alice, now));
	}

	@Test
	void freezeStopsEveryoneButTheOwnerAndAllBlocksButNotCancels() {
		acct.frozen = true;
		assertNull(AccessPolicy.checkOrder(acct, owner, order("BTC", Side.BUY, 100), now));
		assertNotNull(AccessPolicy.checkOrder(acct, alice, order("BTC", Side.BUY, 100), now));
		assertNull(AccessPolicy.checkCancel(acct, alice, now));
		assertNotNull(AccessPolicy.checkBlockTrading(acct, owner, now)); // even the owner's own executors stop
	}

	@Test
	void blocksActAsTheirOwner() {
		assertNull(AccessPolicy.checkBlockTrading(acct, owner, now));
		assertNull(AccessPolicy.checkBlockTrading(acct, alice, now));
		assertNotNull(AccessPolicy.checkBlockTrading(acct, mallory, now)); // a thief's executor with a stolen card
		assertNotNull(AccessPolicy.checkBlockTrading(acct, bob, now));
		assertNotNull(AccessPolicy.checkBlockTrading(acct, null, now)); // unowned block
	}

	@Test
	void cards() {
		Account.CardRecord trade = new Account.CardRecord();
		trade.id = "aaa";
		trade.level = "TRADE";
		Account.CardRecord read = new Account.CardRecord();
		read.id = "bbb";
		read.level = "READ";
		acct.cards.add(trade);
		acct.cards.add(read);
		String id = acct.id.toString();
		assertNull(AccessPolicy.checkCard(acct, new ModComponents.AccountRef(id, "Fund", "aaa", "TRADE"), true));
		assertNull(AccessPolicy.checkCard(acct, new ModComponents.AccountRef(id, "Fund", "bbb", "READ"), false));
		assertNotNull(AccessPolicy.checkCard(acct, new ModComponents.AccountRef(id, "Fund", "bbb", "READ"), true));
		assertNull(AccessPolicy.checkCard(acct, new ModComponents.AccountRef(id, "Fund"), true)); // legacy card
		assertNotNull(AccessPolicy.checkCard(acct, new ModComponents.AccountRef(id, "Fund", "zzz", "TRADE"), false)); // unknown id
		trade.revoked = true;
		assertNotNull(AccessPolicy.checkCard(acct, new ModComponents.AccountRef(id, "Fund", "aaa", "TRADE"), false));
		// a read card's id with "TRADE" forged onto the item still can't trade: the account's record decides
		trade.revoked = false;
		assertNotNull(AccessPolicy.checkCard(acct, new ModComponents.AccountRef(id, "Fund", "bbb", "TRADE"), true));
	}

	@Test
	void legacyCardsOnlyWorkForBlockOwnersWithAccess() {
		ModComponents.AccountRef legacy = new ModComponents.AccountRef(acct.id.toString(), "Fund");
		assertNull(AccessPolicy.checkBlockCard(acct, legacy, true, owner, now));
		assertNull(AccessPolicy.checkBlockCard(acct, legacy, false, bob, now));
		assertNotNull(AccessPolicy.checkBlockCard(acct, legacy, false, mallory, now)); // forged/old card in a stranger's sensor
	}

	@Test
	void ownerOnlyActions() {
		assertNull(AccessPolicy.checkOwner(acct, owner));
		assertNotNull(AccessPolicy.checkOwner(acct, alice));
		assertNotNull(AccessPolicy.checkOwner(acct, null));
	}

	@Test
	void guardedAccountRefusesAndLogsWithoutTouchingTheAccount() {
		Map<String, Double> mids = new java.util.HashMap<>(Map.of("BTC", 80_000.0, "ETH", 2_400.0));
		MarketData.Asset btc = new MarketData.Asset("BTC", 0, 5, 40);
		MarketData.Asset eth = new MarketData.Asset("ETH", 1, 4, 25);
		PaperAccount.Quotes q = new PaperAccount.Quotes() {
			@Override
			public MarketData.Asset asset(String coin) {
				return coin.equals("BTC") ? btc : coin.equals("ETH") ? eth : null;
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
		PaperAccount paper = new PaperAccount(PaperAccount.fresh(), () -> q);
		paper.deposit(10_000);
		List<String> log = new ArrayList<>();
		GuardedAccount.Auditor auditor = (a, actor, text, ok) -> log.add(actor.name() + "|" + actor.via() + "|" + ok + "|" + text);

		GuardedAccount asAlice = new GuardedAccount(paper, acct, new GuardedAccount.Actor(alice, "Alice", "terminal", false), auditor);
		assertTrue(asAlice.submit(order("BTC", Side.BUY, 400)).join().ok());
		assertFalse(asAlice.submit(order("BTC", Side.BUY, 900)).join().ok());
		assertFalse(asAlice.setLeverage("BTC", 20, true).join().ok());
		assertEquals(5, paper.snapshot().getAsJsonArray("fills").size() + 4); // exactly one fill happened

		GuardedAccount asThiefBlock = new GuardedAccount(paper, acct, new GuardedAccount.Actor(mallory, "Mallory", "executor @ 1,2,3", true), auditor);
		assertFalse(asThiefBlock.submit(order("BTC", Side.SELL, 100)).join().ok());
		assertFalse(asThiefBlock.cancelAll(null).join().ok());
		assertEquals(1, paper.snapshot().getAsJsonArray("fills").size());

		assertEquals(5, log.size());
		assertTrue(log.get(0).startsWith("Alice|terminal|true|Bought"));
		assertTrue(log.get(1).startsWith("Alice|terminal|false|Refused"));
		assertTrue(log.get(3).startsWith("Mallory|executor @ 1,2,3|false|Refused"));
	}

	private Account.CardRecord card(String id, Account.Scope scope) {
		Account.CardRecord c = new Account.CardRecord();
		c.id = id;
		c.level = "TRADE";
		c.scope = scope;
		acct.cards.add(c);
		return c;
	}

	@Test
	void aScopedCardAddsItsOwnLimits() {
		Account.Scope s = new Account.Scope();
		s.markets.add("BTC");
		s.maxOrderUsd = 300;
		s.maxLeverage = 3;
		card("scoped", s);
		card("plain", null);
		// the owner's own block, through a scoped card
		assertNull(AccessPolicy.checkScope(acct, "scoped", order("BTC", Side.BUY, 250), now));
		assertEquals("This card doesn't include ETH", AccessPolicy.checkScope(acct, "scoped", order("ETH", Side.BUY, 50), now));
		assertEquals("This card is capped at $300 per order", AccessPolicy.checkScope(acct, "scoped", order("BTC", Side.BUY, 400), now));
		// closing is never capped by size
		assertNull(AccessPolicy.checkScope(acct, "scoped", order("BTC", Side.CLOSE, 0), now));
		assertEquals("This card is capped at 3x", AccessPolicy.checkScopeLeverage(acct, "scoped", "BTC", 5, now));
		assertNull(AccessPolicy.checkScopeLeverage(acct, "scoped", "BTC", 3, now));
		// an unscoped card, or no card, adds nothing
		assertNull(AccessPolicy.checkScope(acct, "plain", order("ETH", Side.BUY, 1e6), now));
		assertNull(AccessPolicy.checkScope(acct, null, order("ETH", Side.BUY, 1e6), now));
	}

	@Test
	void reduceOnlyAndExpiryOnACard() {
		Account.Scope s = new Account.Scope();
		s.reduceOnly = true;
		s.expiresAt = now + 1000;
		card("ro", s);
		assertEquals("This card is reduce-only", AccessPolicy.checkScope(acct, "ro", order("BTC", Side.BUY, 10), now));
		OrderRequest reduce = order("BTC", Side.SELL, 10);
		reduce.reduceOnly = true;
		assertNull(AccessPolicy.checkScope(acct, "ro", reduce, now));
		assertEquals("This card has expired", AccessPolicy.checkScope(acct, "ro", reduce, now + 1000));
	}

	@Test
	void cardLimitsIntersectWithAGrant() {
		// Alice may trade BTC up to $500; her block's card is capped at $300: the tighter one wins
		Account.Scope s = new Account.Scope();
		s.maxOrderUsd = 300;
		card("alice", s);
		OrderRequest r = order("BTC", Side.BUY, 400);
		assertNull(AccessPolicy.checkOrder(acct, alice, r, now));
		assertNotNull(AccessPolicy.checkScope(acct, "alice", r, now));
		OrderRequest eth = order("ETH", Side.BUY, 100);
		assertNotNull(AccessPolicy.checkOrder(acct, alice, eth, now));
		assertNull(AccessPolicy.checkScope(acct, "alice", eth, now));
	}

	@Test
	void anExpiredCardStopsReadingToo() {
		Account.Scope s = new Account.Scope();
		s.expiresAt = 1; // long gone
		card("old", s);
		ModComponents.AccountRef ref = new ModComponents.AccountRef(acct.id.toString(), acct.name, "old", "READ");
		assertEquals("This card has expired", AccessPolicy.checkCard(acct, ref, false));
	}

	@Test
	void limitsOnlyGetTighter() {
		Account.Scope old = new Account.Scope();
		old.markets.add("BTC");
		old.markets.add("ETH");
		old.maxOrderUsd = 500;
		Account.Scope tighter = new Account.Scope();
		tighter.markets.add("BTC");
		tighter.maxOrderUsd = 200;
		tighter.reduceOnly = true;
		assertTrue(tighter.within(old));
		assertFalse(old.within(tighter));
		Account.Scope everyMarket = new Account.Scope();
		everyMarket.maxOrderUsd = 100;
		assertFalse(everyMarket.within(old));
		Account.Scope noCap = new Account.Scope();
		noCap.markets.add("BTC");
		assertFalse(noCap.within(old));
		assertEquals("BTC, ETH · $500/order", old.summary());
	}
}
