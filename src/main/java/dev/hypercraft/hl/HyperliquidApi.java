package dev.hypercraft.hl;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Thin async client for Hyperliquid's read-only /info endpoint. HyperCraft never sends orders to the exchange. */
public final class HyperliquidApi {
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private final String base;

	public HyperliquidApi(String base) {
		this.base = base;
	}

	public CompletableFuture<JsonElement> info(Map<String, Object> body) {
		return post("/info", GSON.toJson(body));
	}

	public CompletableFuture<JsonElement> info(String type) {
		return info(Map.of("type", type));
	}

	public CompletableFuture<JsonElement> info(String type, String userAddress) {
		return info(Map.of("type", type, "user", userAddress));
	}

	public CompletableFuture<JsonElement> candles(String coin, String interval, long startMs, long endMs) {
		Map<String, Object> req = new LinkedHashMap<>();
		req.put("coin", coin);
		req.put("interval", interval);
		req.put("startTime", startMs);
		req.put("endTime", endMs);
		return info(Map.of("type", "candleSnapshot", "req", req));
	}

	private CompletableFuture<JsonElement> post(String path, String json) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();
		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
			if (resp.statusCode() != 200) {
				throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
			}
			return JsonParser.parseString(resp.body());
		});
	}

	private static String truncate(String s) {
		return s.length() > 200 ? s.substring(0, 200) + "..." : s;
	}
}
