package dev.stonkcraft.trading;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/** A tradable account (paper or live). Every operation the terminal and redstone blocks can perform. */
public interface TradingAccount {
	/** PAPER or WATCH. */
	String mode();

	/** Places an order of any type. Completes off-thread for live accounts. */
	CompletableFuture<OrderResult> submit(OrderRequest request);

	default CompletableFuture<OrderResult> submit(String coin, Side side, double usd) {
		return submit(OrderRequest.market(coin, side, usd));
	}

	CompletableFuture<OrderResult> cancel(String coin, long oid);

	/**
	 * Amends a resting order (found by client order id) to the request's price and size, keeping its place in the
	 * queue. Brackets on a paper entry move with it. The order type can't change.
	 */
	CompletableFuture<OrderResult> modify(String cloid, OrderRequest request);

	CompletableFuture<OrderResult> cancelByCloid(String coin, String cloid);

	/** Status of an order placed with a client order id; NONE if unknown. */
	OrderStatus orderStatus(String cloid);

	/** Cancels every open order, or only those on {@code coin} when it is non-null. */
	CompletableFuture<OrderResult> cancelAll(@Nullable String coin);

	CompletableFuture<OrderResult> setLeverage(String coin, int leverage, boolean cross);

	CompletableFuture<OrderResult> cancelTwap(String coin, long twapId);

	/** Dead man's switch: cancel all open orders at {@code timeMs}; 0 clears it. */
	CompletableFuture<OrderResult> scheduleCancel(long timeMs);

	/** When the dead man's switch fires, 0 if not armed. */
	long scheduledCancel();

	/** Adds or removes USDC from an isolated position's margin. */
	CompletableFuture<OrderResult> adjustMargin(String coin, double usd);

	/** Sets a position's own take-profit and stop-loss, by price (0 = none), replacing any it has. */
	default CompletableFuture<OrderResult> setPositionTpSl(String coin, double tpPx, double slPx) {
		return CompletableFuture.completedFuture(OrderResult.fail(coin, "This account can't set TP/SL"));
	}

	/** Full trade and order history and the long equity record, for the terminal to ask for when it shows them. */
	default JsonObject history() {
		return new JsonObject();
	}

	/** Current leverage for a perp, or 0 when unknown. */
	int leverage(String coin);

	boolean isCross(String coin);

	/** Open orders, optionally only for one market. */
	int openOrders(@Nullable String coin);

	/** Time of the most recent fill on a market, 0 if none seen. */
	long lastFillTime(String coin);

	/** Signed perp size, or the base-token balance for spot markets. */
	double positionSize(String coin);

	/** Unrealized PnL in USD for a perp, 0 if flat. */
	double unrealizedPnl(String coin);

	double equity();

	double marginUsed();

	/** USD that can leave the account right now (free collateral). */
	double withdrawable();

	/** Full portfolio for the terminal UI. */
	JsonObject snapshot();

	/** Every perp position: coin -> {signed size, mark price}. Signals and copy desks read portfolios from this. */
	default java.util.Map<String, double[]> perpPositions() {
		java.util.Map<String, double[]> out = new java.util.LinkedHashMap<>();
		for (com.google.gson.JsonElement el : snapshot().getAsJsonArray("positions")) {
			JsonObject p = el.getAsJsonObject();
			out.put(p.get("coin").getAsString(), new double[]{p.get("size").getAsDouble(), p.get("mark").getAsDouble()});
		}
		return out;
	}
}
