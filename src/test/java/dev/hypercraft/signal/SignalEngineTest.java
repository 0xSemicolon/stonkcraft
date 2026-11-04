package dev.hypercraft.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignalEngineTest {
	private final Map<UUID, SignalNode> nodes = new LinkedHashMap<>();
	private final Map<String, SignalValue> accounts = new HashMap<>();
	private final Map<String, Double> mids = new HashMap<>();
	private long now = 1_000_000;
	private long lastFill;
	private boolean open;

	private final SignalEngine.Env env = new SignalEngine.Env() {
		@Override
		public SignalNode node(UUID id) {
			return nodes.get(id);
		}

		@Override
		public SignalValue account(SignalNode.Input in) {
			return accounts.getOrDefault(in.id, SignalValue.NONE);
		}

		@Override
		public double metric(SignalNode.Input in, String metric, String market) {
			if (metric.equals("POSITION")) return market.equals("BTC") ? 2 : 0;
			return metric.equals("EQUITY") ? 1234 : Double.NaN;
		}

		@Override
		public double position(SignalNode.Input in, String market) {
			if (!in.id.equals("whale")) return in.id.equals("friend") ? 0 : Double.NaN;
			return market.equals("BTC") ? 160_000 : market.equals("ETH") ? -3000 : 0;
		}

		@Override
		public double positionPnl(SignalNode.Input in, String market) {
			return market.equals("BTC") ? 420 : -15;
		}

		@Override
		public int openOrders(SignalNode.Input in, String market) {
			return in.id.equals("whale") ? (market.isEmpty() ? 5 : 3) : -1;
		}

		@Override
		public long lastFill(SignalNode.Input in, String market) {
			return in.id.equals("whale") ? lastFill : -1;
		}

		@Override
		public double spreadBps(String market) {
			return market.equals("BTC") ? 1.5 : Double.NaN;
		}

		@Override
		public double bookTilt(String market) {
			return 0.62;
		}

		@Override
		public SignalValue address(String address) {
			return accounts.getOrDefault(address, SignalValue.NONE);
		}

		@Override
		public String resolve(String market) {
			String m = market.toUpperCase();
			if (m.equals("GOLD")) return "xyz:GOLD";
			return mids.containsKey(m) || m.equals("XYZ:GOLD") ? (m.equals("XYZ:GOLD") ? "xyz:GOLD" : m) : null;
		}

		@Override
		public double mid(String market) {
			return mids.getOrDefault(market, Double.NaN);
		}

		@Override
		public double changePct(String market, long windowMs) {
			return 2.5;
		}

		@Override
		public double fundingApr(String market) {
			return 11;
		}

		@Override
		public boolean marketOpen(long nowMs) {
			return open;
		}

		@Override
		public long now() {
			return now;
		}
	};

	@BeforeEach
	void setUp() {
		mids.put("BTC", 80_000.0);
		mids.put("ETH", 3000.0);
		accounts.put("whale", new SignalValue.Portfolio(Map.of("BTC", 2.0, "ETH", -1.0), 1_000_000));
		accounts.put("friend", new SignalValue.Portfolio(Map.of("ETH", 1.0), 10_000));
	}

	private SignalNode node(SignalOp op, SignalNode.Input... inputs) {
		SignalNode n = new SignalNode();
		n.op = op;
		n.inputs.addAll(List.of(inputs));
		nodes.put(n.id, n);
		return n;
	}

	private static SignalNode.Input acct(String id, double weight) {
		SignalNode.Input in = new SignalNode.Input();
		in.kind = SignalNode.Input.ACCOUNT;
		in.id = id;
		in.weight = weight;
		return in;
	}

	private static SignalNode.Input sig(SignalNode n) {
		SignalNode.Input in = new SignalNode.Input();
		in.id = n.id.toString();
		return in;
	}

	private SignalValue eval(SignalNode n) {
		return SignalEngine.tick(nodes.values(), env).get(n.id);
	}

	@Test
	void blendAddsWeightedPortfolios() {
		SignalNode n = node(SignalOp.BLEND, acct("whale", 0.5), acct("friend", 1));
		SignalValue.Portfolio p = (SignalValue.Portfolio) eval(n);
		assertEquals(1.0, p.weight("BTC"), 1e-9);
		assertEquals(0.5, p.weight("ETH"), 1e-9);
		assertEquals((0.5 * 1_000_000 + 10_000) / 1.5, p.equity(), 1e-6);
	}

	@Test
	void invertFadesAndCapLimitsGross() {
		SignalNode inv = node(SignalOp.INVERT, acct("whale", 1));
		SignalNode cap = node(SignalOp.CAP, sig(inv));
		cap.a = 1.5;
		SignalValue.Portfolio p = (SignalValue.Portfolio) eval(cap);
		assertEquals(1.5, p.gross(), 1e-9);
		assertTrue(p.weight("BTC") < 0 && p.weight("ETH") > 0);
	}

	@Test
	void filterTopAndPick() {
		SignalNode f = node(SignalOp.FILTER, acct("whale", 1));
		f.text = "ETH";
		f.a = 1;
		assertEquals(Map.of("BTC", 2.0), ((SignalValue.Portfolio) eval(f)).weights());
		SignalNode longs = node(SignalOp.FILTER, acct("whale", 1));
		longs.b = 1;
		assertEquals(Map.of("BTC", 2.0), ((SignalValue.Portfolio) eval(longs)).weights());
		SignalNode top = node(SignalOp.TOP, acct("whale", 1));
		top.a = 1;
		assertEquals(1, ((SignalValue.Portfolio) eval(top)).weights().size());
		SignalNode pick = node(SignalOp.PICK, acct("whale", 1));
		pick.text = "NET";
		assertEquals(1.0, ((SignalValue.Scalar) eval(pick)).value(), 1e-9);
	}

	@Test
	void gateFollowsAScalar() {
		SignalNode hours = node(SignalOp.MARKET_HOURS);
		SignalNode gate = node(SignalOp.GATE, acct("whale", 1), sig(hours));
		gate.a = 0.5;
		assertTrue(((SignalValue.Portfolio) eval(gate)).weights().isEmpty());
		open = true;
		assertEquals(2.0, ((SignalValue.Portfolio) eval(gate)).weight("BTC"), 1e-9);
	}

	@Test
	void scalarsCompareAndMap() {
		SignalNode price = node(SignalOp.PRICE);
		price.text = "btc";
		SignalNode cmp = node(SignalOp.COMPARE, sig(price));
		cmp.a = 75_000;
		assertEquals(1.0, ((SignalValue.Scalar) eval(cmp)).value());
		SignalNode map = node(SignalOp.MAP, sig(price));
		map.a = 60_000;
		map.b = 100_000;
		assertEquals(0.5, ((SignalValue.Scalar) eval(map)).value(), 1e-9);
	}

	@Test
	void basketResolvesMarkets() {
		SignalNode b = node(SignalOp.BASKET);
		b.text = "BTC:0.5, gold:0.25, NOPE:1";
		SignalValue.Portfolio p = (SignalValue.Portfolio) eval(b);
		assertEquals(Map.of("BTC", 0.5, "xyz:GOLD", 0.25), p.weights());
	}

	@Test
	void unavailableInputsGiveNothing() {
		SignalNode n = node(SignalOp.BLEND, acct("whale", 1), acct("revoked", 1));
		assertTrue(eval(n).none());
		SignalNode s = node(SignalOp.SCALE, acct("whale", 1));
		s.a = 2;
		assertEquals(4.0, ((SignalValue.Portfolio) eval(s)).weight("BTC"), 1e-9);
	}

	@Test
	void delayReturnsTheValueNSecondsAgo() {
		SignalNode c = node(SignalOp.CONSTANT);
		SignalNode d = node(SignalOp.DELAY, sig(c));
		d.a = 10;
		c.a = 1;
		assertTrue(eval(d).none());
		now += 5_000;
		c.a = 2;
		assertTrue(eval(d).none());
		now += 5_000;
		c.a = 3;
		assertEquals(1.0, ((SignalValue.Scalar) eval(d)).value());
		now += 5_000;
		assertEquals(2.0, ((SignalValue.Scalar) eval(d)).value());
	}

	@Test
	void smoothConverges() {
		SignalNode c = node(SignalOp.CONSTANT);
		SignalNode s = node(SignalOp.SMOOTH, sig(c));
		s.a = 10;
		c.a = 0;
		eval(s);
		c.a = 100;
		now += 10_000;
		assertEquals(50, ((SignalValue.Scalar) eval(s)).value(), 1e-6);
	}

	@Test
	void cyclesAreRefusedAndEvaluateToNothing() {
		SignalNode a = node(SignalOp.INVERT);
		SignalNode b = node(SignalOp.INVERT, sig(a));
		assertTrue(SignalEngine.dependsOn(b, a.id, nodes::get));
		assertFalse(SignalEngine.dependsOn(a, b.id, nodes::get));
		a.inputs.add(sig(b));
		assertInstanceOf(SignalValue.None.class, eval(a));
	}

	@Test
	void metricReadsAnAccountFigure() {
		SignalNode m = node(SignalOp.METRIC, acct("whale", 1));
		m.text = "equity";
		assertEquals(1234, ((SignalValue.Scalar) eval(m)).value());
	}

	@Test
	void metricReadsPerCoinFiguresFromTheMarketOrTheText() {
		SignalNode m = node(SignalOp.METRIC, acct("whale", 1));
		m.text = "POSITION";
		m.market = "BTC";
		assertEquals(2, number(eval(m)));
		SignalNode t = node(SignalOp.METRIC, acct("whale", 1));
		t.text = "position:btc";
		assertEquals(2, number(eval(t)));
	}

	private static double number(SignalValue v) {
		return ((SignalValue.Scalar) v).value();
	}

	private SignalNode reads(SignalOp op, String who, String market) {
		SignalNode n = node(op, acct(who, 1));
		n.text = market;
		return n;
	}

	@Test
	void accountSourcesReadPositionsAndOrders() {
		assertEquals(160_000, number(eval(reads(SignalOp.POSITION, "whale", "BTC"))));
		assertEquals(1, number(eval(reads(SignalOp.SIDE, "whale", "BTC"))));
		assertEquals(-1, number(eval(reads(SignalOp.SIDE, "whale", "ETH"))));
		assertEquals(0, number(eval(reads(SignalOp.SIDE, "friend", "BTC"))));
		assertEquals(420, number(eval(reads(SignalOp.POSITION_PNL, "whale", "BTC"))));
		// flat has no PnL
		assertInstanceOf(SignalValue.None.class, eval(reads(SignalOp.POSITION_PNL, "friend", "BTC")));
		assertEquals(3, number(eval(reads(SignalOp.OPEN_ORDERS, "whale", "BTC"))));
		assertEquals(5, number(eval(reads(SignalOp.OPEN_ORDERS, "whale", ""))));
		// a revoked card reads nothing
		assertInstanceOf(SignalValue.None.class, eval(reads(SignalOp.POSITION, "stranger", "BTC")));
		assertInstanceOf(SignalValue.None.class, eval(reads(SignalOp.OPEN_ORDERS, "stranger", "BTC")));
	}

	@Test
	void bookSources() {
		SignalNode s = node(SignalOp.SPREAD);
		s.text = "btc";
		assertEquals(1.5, number(eval(s)));
		SignalNode none = node(SignalOp.SPREAD);
		none.text = "ETH";
		assertInstanceOf(SignalValue.None.class, eval(none));
		SignalNode t = node(SignalOp.BOOK_TILT);
		t.text = "BTC";
		assertEquals(0.62, number(eval(t)));
	}

	@Test
	void deltaNeedsAWindowOfHistory() {
		SignalNode c = node(SignalOp.CONSTANT);
		SignalNode d = node(SignalOp.DELTA, sig(c));
		d.a = 10;
		c.a = 100;
		assertInstanceOf(SignalValue.None.class, eval(d));
		now += 5_000;
		c.a = 104;
		assertInstanceOf(SignalValue.None.class, eval(d));
		now += 5_000;
		c.a = 110;
		assertEquals(10, number(eval(d)), 1e-9);
		now += 5_000;
		c.a = 111;
		// now against the sample 10 s back, 104
		assertEquals(7, number(eval(d)), 1e-9);
	}

	@Test
	void fillIsAnEventThatGatesAndWatchersSeeOnce() {
		SignalNode fill = reads(SignalOp.FILL, "whale", "BTC");
		EventWatch w = new EventWatch();
		SignalValue.Event first = (SignalValue.Event) eval(fill);
		assertEquals(0, first.at());
		assertFalse(w.next(first));
		lastFill = System.currentTimeMillis();
		SignalNode on = node(SignalOp.CONSTANT);
		on.a = 1;
		SignalNode gate = node(SignalOp.GATE, sig(fill), sig(on));
		SignalValue ev = eval(gate);
		assertInstanceOf(SignalValue.Event.class, ev);
		assertEquals(1, ev.asNumber(""));
		assertTrue(w.next(ev));
		assertFalse(w.next(eval(gate)));
		// a closed gate passes no event
		on.a = 0;
		assertEquals(0, ((SignalValue.Event) eval(gate)).at());
		// events don't add up
		assertInstanceOf(SignalValue.None.class, eval(node(SignalOp.BLEND, sig(fill), sig(fill))));
	}

	@Test
	void bellRingsWhenTheExchangeOpensAndCloses() {
		SignalNode bell = node(SignalOp.BELL);
		EventWatch w = new EventWatch();
		assertFalse(w.next(eval(bell)));
		open = true;
		SignalValue.Event ring = (SignalValue.Event) eval(bell);
		assertEquals("opening bell", ring.what());
		assertTrue(w.next(ring));
		assertFalse(w.next(eval(bell)));
	}

	@Test
	void anEventWatcherIgnoresTheEventItStartsOn() {
		long t = System.currentTimeMillis();
		EventWatch w = new EventWatch();
		assertFalse(w.next(new SignalValue.Event(t, t, "old")));
		assertTrue(w.next(new SignalValue.Event(t + 1, t + 1, "new")));
		assertFalse(w.next(SignalValue.NONE));
	}
}
