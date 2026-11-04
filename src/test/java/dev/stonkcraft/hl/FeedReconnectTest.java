package dev.stonkcraft.hl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The feed survives dropped connections and failed connects, re-subscribing everything, against a fake transport. */
class FeedReconnectTest {
	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

	/** A fake server side: records what each connection was sent and lets the test drop it or push messages. */
	private static final class FakeConn implements HlSocket.Connection {
		final HlSocket.Inbound inbound;
		final List<String> sent = new CopyOnWriteArrayList<>();
		volatile boolean aborted;

		FakeConn(HlSocket.Inbound inbound) {
			this.inbound = inbound;
		}

		@Override
		public void send(String text) {
			sent.add(text);
		}

		@Override
		public void abort() {
			aborted = true;
		}

		long subscribes() {
			return sent.stream().filter(s -> s.contains("\"method\":\"subscribe\"")).count();
		}
	}

	private final List<FakeConn> conns = new CopyOnWriteArrayList<>();
	/** How many upcoming connect attempts should fail. */
	private volatile int failNext;
	private volatile int attempts;

	private final HlSocket.Transport transport = (url, inbound) -> {
		attempts++;
		if (failNext > 0) {
			failNext--;
			return CompletableFuture.failedFuture(new java.io.IOException("connection refused"));
		}
		FakeConn c = new FakeConn(inbound);
		conns.add(c);
		return CompletableFuture.completedFuture(c);
	};

	@AfterEach
	void tearDown() {
		exec.shutdownNow();
	}

	private static void await(BooleanSupplier cond) throws InterruptedException {
		long until = System.currentTimeMillis() + 5_000;
		while (!cond.getAsBoolean()) {
			if (System.currentTimeMillis() > until) throw new AssertionError("timed out");
			Thread.sleep(10);
		}
	}

	@Test
	void reconnectsAndResubscribesAfterADrop() throws Exception {
		HlSocket socket = new HlSocket("https://example.invalid", exec, transport, 20);
		List<String> mids = new ArrayList<>();
		socket.subscribe(HlSocket.sub("allMids", null, null), d -> mids.add(d.getAsJsonObject("mids").get("BTC").getAsString()));
		socket.subscribe(HlSocket.sub("openOrders", null, "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"), d -> {});
		socket.start();
		await(() -> conns.size() == 1);
		FakeConn first = conns.get(0);
		await(() -> first.subscribes() == 2);
		assertTrue(socket.connected());
		assertEquals(1, socket.connectCount());

		// a subscription added while connected goes out straight away
		socket.subscribe(HlSocket.sub("l2Book", "BTC", null), d -> {});
		assertEquals(3, first.subscribes());

		first.inbound.onText("{\"channel\":\"allMids\",\"data\":{\"mids\":{\"BTC\":\"80000\"}}}");
		assertEquals(List.of("80000"), mids);

		// the server drops us: a new connection appears and gets every subscription again
		first.inbound.onClosed("closed 1006 abnormal");
		await(() -> conns.size() == 2);
		FakeConn second = conns.get(1);
		await(() -> second.subscribes() == 3);
		assertEquals(2, socket.connectCount());
		second.inbound.onText("{\"channel\":\"allMids\",\"data\":{\"mids\":{\"BTC\":\"80100\"}}}");
		assertEquals(List.of("80000", "80100"), mids);

		// unsubscribed feeds stay gone after the next reconnect
		socket.unsubscribe(HlSocket.sub("l2Book", "BTC", null));
		assertTrue(second.sent.stream().anyMatch(s -> s.contains("\"method\":\"unsubscribe\"")));
		second.inbound.onClosed("closed 1001 going away");
		await(() -> conns.size() == 3);
		FakeConn third = conns.get(2);
		await(() -> third.subscribes() == 2);
		assertTrue(third.sent.stream().noneMatch(s -> s.contains("l2Book")));
		socket.stop();
	}

	@Test
	void failedConnectsBackOffAndEventuallyConnect() throws Exception {
		failNext = 3;
		HlSocket socket = new HlSocket("https://example.invalid", exec, transport, 20);
		socket.subscribe(HlSocket.sub("allMids", null, null), d -> {});
		long start = System.currentTimeMillis();
		socket.start();
		await(() -> conns.size() == 1);
		long took = System.currentTimeMillis() - start;
		assertEquals(4, attempts);
		// 3 failures back off 20 + 40 + 80 ms before the 4th attempt succeeds
		assertTrue(took >= 140, "backoff too short: " + took + "ms");
		await(() -> conns.get(0).subscribes() == 1);
		socket.stop();
	}

	@Test
	void stoppedSocketDoesNotReconnect() throws Exception {
		HlSocket socket = new HlSocket("https://example.invalid", exec, transport, 20);
		socket.start();
		await(() -> conns.size() == 1);
		socket.stop();
		assertTrue(conns.get(0).aborted);
		conns.get(0).inbound.onClosed("closed by us");
		Thread.sleep(150);
		assertEquals(1, conns.size());
	}
}
