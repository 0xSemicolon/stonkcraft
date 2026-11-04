package dev.stonkcraft.hl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stonkcraft.Stonkcraft;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * One WebSocket to Hyperliquid's feed, shared by market data and every live-fed account. Messages are routed by
 * channel plus coin or user (e.g. "l2Book:btc", "openOrders:0xabc..."), it pings to stay open, and it reconnects
 * with backoff, re-subscribing everything.
 */
public final class HlSocket {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final long PING_MS = 50_000;
	/** No message at all for this long means the connection is dead even if nobody said so. */
	private static final long STALE_MS = 120_000;

	private record Sub(JsonObject subscription, Consumer<JsonObject> handler) {}

	/** An open connection. */
	public interface Connection {
		void send(String text);

		void abort();
	}

	/** What a transport reports back: complete text messages and the connection ending. */
	public interface Inbound {
		void onText(String message);

		void onClosed(String why);
	}

	/** Opens connections; the real one uses java.net.http, tests use a fake. */
	public interface Transport {
		java.util.concurrent.CompletableFuture<Connection> open(String url, Inbound inbound);
	}

	private final String url;
	private final ScheduledExecutorService exec;
	private final Transport transport;
	/** First reconnect delay; doubles per failure up to 30s. */
	private final long retryBaseMs;
	private final Map<String, Sub> subs = new ConcurrentHashMap<>();
	@Nullable
	private volatile Connection ws;
	private volatile boolean connecting;
	private volatile boolean stopped;
	private volatile long lastMessage;
	private volatile long connectedAt;
	private int failures;
	private final java.util.concurrent.atomic.AtomicInteger connects = new java.util.concurrent.atomic.AtomicInteger();

	public HlSocket(String apiBase, ScheduledExecutorService exec) {
		this(apiBase, exec, HlSocket::openJdk, 1000);
	}

	public HlSocket(String apiBase, ScheduledExecutorService exec, Transport transport, long retryBaseMs) {
		this.url = apiBase.replaceFirst("^http", "ws") + "/ws";
		this.exec = exec;
		this.transport = transport;
		this.retryBaseMs = retryBaseMs;
	}

	/** Successful connections so far (reconnects included). */
	public int connectCount() {
		return connects.get();
	}

	public void start() {
		connect();
		exec.scheduleWithFixedDelay(this::keepAlive, PING_MS, PING_MS, TimeUnit.MILLISECONDS);
	}

	public void stop() {
		stopped = true;
		Connection w = ws;
		if (w != null) w.abort();
	}

	public boolean connected() {
		return ws != null && System.currentTimeMillis() - lastMessage < STALE_MS;
	}

	public int subscriptionCount() {
		return subs.size();
	}

	/** Distinct users across user-specific subscriptions (Hyperliquid caps this per IP). */
	public int userCount() {
		Set<String> users = new HashSet<>();
		for (Sub s : subs.values()) if (s.subscription.has("user")) users.add(s.subscription.get("user").getAsString().toLowerCase(Locale.ROOT));
		return users.size();
	}

	public void subscribe(JsonObject subscription, Consumer<JsonObject> handler) {
		String id = identifier(subscription);
		boolean fresh = subs.put(id, new Sub(subscription, handler)) == null;
		if (fresh) send("subscribe", subscription);
	}

	public void unsubscribe(JsonObject subscription) {
		if (subs.remove(identifier(subscription)) != null) send("unsubscribe", subscription);
	}

	public static JsonObject sub(String type, @Nullable String coin, @Nullable String user) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		if (coin != null) o.addProperty("coin", coin);
		if (user != null) o.addProperty("user", user.toLowerCase(Locale.ROOT));
		return o;
	}

	/** A subscription on a HIP-3 dex, e.g. {@code allMids} for "xyz". */
	public static JsonObject subDex(String type, String dex) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("dex", dex);
		return o;
	}

	/** Routing key for a subscription: type, plus its coin or user. */
	static String identifier(JsonObject sub) {
		String type = sub.get("type").getAsString();
		if (sub.has("dex")) return type + ":dex:" + sub.get("dex").getAsString().toLowerCase(Locale.ROOT);
		if (sub.has("coin")) return type + ":" + sub.get("coin").getAsString().toLowerCase(Locale.ROOT);
		if (sub.has("user")) return type + ":" + sub.get("user").getAsString().toLowerCase(Locale.ROOT);
		return type;
	}

	/** Routing key for an incoming message, or null for control messages. */
	@Nullable
	static String identifierOf(JsonObject msg) {
		if (!msg.has("channel")) return null;
		String ch = msg.get("channel").getAsString();
		JsonElement d = msg.get("data");
		if (d == null || !d.isJsonObject()) return null;
		JsonObject data = d.getAsJsonObject();
		return switch (ch) {
			case "allMids" -> data.has("dex") && !data.get("dex").getAsString().isEmpty()
					? "allMids:dex:" + data.get("dex").getAsString().toLowerCase(Locale.ROOT) : "allMids";
			case "subscriptionResponse", "pong", "error" -> null;
			default -> {
				if (data.has("coin")) yield ch + ":" + data.get("coin").getAsString().toLowerCase(Locale.ROOT);
				if (data.has("user")) yield ch + ":" + data.get("user").getAsString().toLowerCase(Locale.ROOT);
				yield ch;
			}
		};
	}

	/** Parses one complete message and hands its data to the matching subscription. Public for tests. */
	public void dispatch(String text) {
		JsonObject msg;
		try {
			msg = JsonParser.parseString(text).getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}
		if ("error".equals(msg.has("channel") ? msg.get("channel").getAsString() : "")) {
			Stonkcraft.LOG.warn("Hyperliquid feed error: {}", msg.get("data"));
			return;
		}
		String id = identifierOf(msg);
		Sub s = id == null ? null : subs.get(id);
		if (s == null) return;
		try {
			s.handler.accept(msg.getAsJsonObject("data"));
		} catch (RuntimeException e) {
			Stonkcraft.LOG.debug("Feed handler for {} failed", id, e);
		}
	}

	private void send(String method, JsonObject subscription) {
		Connection w = ws;
		if (w == null) return; // sent on (re)connect
		JsonObject o = new JsonObject();
		o.addProperty("method", method);
		o.add("subscription", subscription);
		synchronized (this) {
			w.send(o.toString());
		}
	}

	private void connect() {
		if (stopped || connecting) return;
		connecting = true;
		Inbound inbound = new Inbound() {
			@Override
			public void onText(String message) {
				lastMessage = System.currentTimeMillis();
				dispatch(message);
			}

			@Override
			public void onClosed(String why) {
				retry(why);
			}
		};
		java.util.concurrent.CompletableFuture<Connection> opening;
		try {
			opening = transport.open(url, inbound);
		} catch (RuntimeException e) {
			opening = java.util.concurrent.CompletableFuture.failedFuture(e);
		}
		opening.whenComplete((w, err) -> {
			connecting = false;
			if (err != null) {
				retry("connect failed: " + err.getMessage());
				return;
			}
			ws = w;
			lastMessage = connectedAt = System.currentTimeMillis();
			failures = 0;
			connects.incrementAndGet();
			Stonkcraft.LOG.info("Hyperliquid feed connected ({} subscriptions)", subs.size());
			for (Sub s : subs.values()) send("subscribe", s.subscription);
		});
	}

	private void retry(String why) {
		ws = null;
		if (stopped) return;
		long delay = Math.min(30_000, retryBaseMs << Math.min(5, failures++));
		Stonkcraft.LOG.debug("Hyperliquid feed down ({}), retrying in {}ms", why, delay);
		exec.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
	}

	private void keepAlive() {
		Connection w = ws;
		if (w == null) {
			if (!connecting) connect();
			return;
		}
		if (System.currentTimeMillis() - lastMessage > STALE_MS) {
			w.abort();
			retry("stale");
			return;
		}
		synchronized (this) {
			w.send("{\"method\":\"ping\"}");
		}
	}

	/** The real transport: java.net.http WebSocket, reassembling fragmented text frames. */
	private static java.util.concurrent.CompletableFuture<Connection> openJdk(String url, Inbound inbound) {
		StringBuilder partial = new StringBuilder();
		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				partial.append(data);
				if (last) {
					String text = partial.toString();
					partial.setLength(0);
					inbound.onText(text);
				}
				webSocket.request(1);
				return null;
			}

			@Override
			public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
				inbound.onClosed("closed " + statusCode + " " + reason);
				return null;
			}

			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				inbound.onClosed(String.valueOf(error.getMessage()));
			}
		};
		return HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(url), listener)
				.thenApply(w -> new Connection() {
					@Override
					public void send(String text) {
						w.sendText(text, true);
					}

					@Override
					public void abort() {
						w.abort();
					}
				});
	}
}
