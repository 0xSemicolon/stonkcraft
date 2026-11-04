package dev.stonkcraft.trading;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * An account as one particular actor may use it. Every write is checked against {@link AccessPolicy} for that
 * actor (their role, the grant's limits, a freeze) and recorded in the account's activity log; reads pass through.
 * The terminal, commands and every trading block reach accounts only through this.
 */
public final class GuardedAccount implements TradingAccount {
	/**
	 * @param id    the player acting (for blocks: the block's owner), or null when unknown
	 * @param via   "terminal", "command", "executor @ x,y,z"...
	 * @param block true for block-driven actions, which also stop while the account is frozen
	 * @param cardId the card a block acts through, whose own limits apply too; null for none
	 */
	public record Actor(@Nullable UUID id, String name, String via, boolean block, @Nullable String cardId) {
		public Actor(@Nullable UUID id, String name, String via, boolean block) {
			this(id, name, via, block, null);
		}
	}

	/** Where outcomes are recorded and the owner is told about other people's trades. */
	public interface Auditor {
		void record(Account account, Actor actor, String text, boolean ok);
	}

	private final TradingAccount inner;
	private final Account account;
	private final Actor actor;
	private final Auditor auditor;

	public GuardedAccount(TradingAccount inner, Account account, Actor actor, Auditor auditor) {
		this.inner = inner;
		this.account = account;
		this.actor = actor;
		this.auditor = auditor;
	}

	public Account account() {
		return account;
	}

	private long now() {
		return System.currentTimeMillis();
	}

	@Nullable
	private String tradeCheck() {
		return actor.block ? AccessPolicy.checkBlockTrading(account, actor.id, now()) : AccessPolicy.checkTrading(account, actor.id, now());
	}

	private CompletableFuture<OrderResult> guarded(@Nullable String denied, String coin, String what, Function<TradingAccount, CompletableFuture<OrderResult>> op) {
		if (denied != null) {
			auditor.record(account, actor, "Refused " + what + ": " + denied, false);
			return CompletableFuture.completedFuture(OrderResult.fail(coin, denied));
		}
		return op.apply(inner).whenComplete((r, err) -> {
			if (r != null) auditor.record(account, actor, r.message(), r.ok());
			else if (err != null) auditor.record(account, actor, what + " failed: " + err.getMessage(), false);
		});
	}

	@Override
	public CompletableFuture<OrderResult> submit(OrderRequest r) {
		settleNotional(r);
		String denied = tradeCheck();
		if (denied == null) denied = AccessPolicy.checkOrder(account, actor.id, r, now());
		if (denied == null) denied = AccessPolicy.checkScope(account, actor.cardId, r, now());
		return guarded(denied, r.coin, r.side.label + " " + r.coin, a -> a.submit(r));
	}

	/**
	 * Limits check {@link OrderRequest#usd}, so an order sized another way says what it's worth: in coin, or a Reverse,
	 * which is twice the position. The client's own dollar figure doesn't count when this one's larger.
	 */
	private void settleNotional(OrderRequest r) {
		double px = inner.perpPositions().containsKey(r.coin) ? inner.perpPositions().get(r.coin)[1] : Double.NaN;
		if (Double.isNaN(px) && dev.stonkcraft.Stonkcraft.market() != null) px = dev.stonkcraft.Stonkcraft.market().mid(r.coin);
		if (Double.isNaN(px)) {
			// nothing to price it with: refuse to guess in the player's favour
			if (r.sizeCoin > 0 || r.side == Side.REVERSE) r.usd = Double.MAX_VALUE;
			return;
		}
		if (r.sizeCoin > 0) r.usd = Math.max(r.usd, r.sizeCoin * px);
		if (r.side == Side.REVERSE) r.usd = 2 * Math.abs(inner.positionSize(r.coin)) * px;
	}

	@Override
	public CompletableFuture<OrderResult> setPositionTpSl(String coin, double tpPx, double slPx) {
		return guarded(tradeCheck(), coin, "TP/SL on " + coin, a -> a.setPositionTpSl(coin, tpPx, slPx));
	}

	@Override
	public JsonObject history() {
		return inner.history();
	}

	@Override
	public CompletableFuture<OrderResult> modify(String cloid, OrderRequest r) {
		String denied = tradeCheck();
		if (denied == null) denied = AccessPolicy.checkOrder(account, actor.id, r, now());
		if (denied == null) denied = AccessPolicy.checkScope(account, actor.cardId, r, now());
		return guarded(denied, r.coin, "amend " + r.coin, a -> a.modify(cloid, r));
	}

	@Override
	public CompletableFuture<OrderResult> setLeverage(String coin, int leverage, boolean cross) {
		String denied = actor.block ? AccessPolicy.checkBlockTrading(account, actor.id, now()) : null;
		if (denied == null) denied = AccessPolicy.checkLeverage(account, actor.id, coin, leverage, now());
		if (denied == null) denied = AccessPolicy.checkScopeLeverage(account, actor.cardId, coin, leverage, now());
		return guarded(denied, coin, leverage + "x on " + coin, a -> a.setLeverage(coin, leverage, cross));
	}

	@Override
	public CompletableFuture<OrderResult> adjustMargin(String coin, double usd) {
		return guarded(tradeCheck(), coin, "margin on " + coin, a -> a.adjustMargin(coin, usd));
	}

	@Override
	public CompletableFuture<OrderResult> cancel(String coin, long oid) {
		return guarded(AccessPolicy.checkCancel(account, actor.id, now()), coin, "cancel", a -> a.cancel(coin, oid));
	}

	@Override
	public CompletableFuture<OrderResult> cancelByCloid(String coin, String cloid) {
		return guarded(AccessPolicy.checkCancel(account, actor.id, now()), coin, "cancel", a -> a.cancelByCloid(coin, cloid));
	}

	@Override
	public CompletableFuture<OrderResult> cancelAll(@Nullable String coin) {
		return guarded(AccessPolicy.checkCancel(account, actor.id, now()), coin == null ? "" : coin, "cancel all", a -> a.cancelAll(coin));
	}

	@Override
	public CompletableFuture<OrderResult> cancelTwap(String coin, long twapId) {
		return guarded(AccessPolicy.checkCancel(account, actor.id, now()), coin, "cancel TWAP", a -> a.cancelTwap(coin, twapId));
	}

	@Override
	public CompletableFuture<OrderResult> scheduleCancel(long timeMs) {
		return guarded(AccessPolicy.checkCancel(account, actor.id, now()), "", "dead man's switch", a -> a.scheduleCancel(timeMs));
	}

	// ------------------------------------------------------------------ reads

	@Override
	public String mode() {
		return inner.mode();
	}

	@Override
	public OrderStatus orderStatus(String cloid) {
		return inner.orderStatus(cloid);
	}

	@Override
	public long scheduledCancel() {
		return inner.scheduledCancel();
	}

	@Override
	public int leverage(String coin) {
		return inner.leverage(coin);
	}

	@Override
	public boolean isCross(String coin) {
		return inner.isCross(coin);
	}

	@Override
	public int openOrders(@Nullable String coin) {
		return inner.openOrders(coin);
	}

	@Override
	public long lastFillTime(String coin) {
		return inner.lastFillTime(coin);
	}

	@Override
	public double positionSize(String coin) {
		return inner.positionSize(coin);
	}

	@Override
	public double unrealizedPnl(String coin) {
		return inner.unrealizedPnl(coin);
	}

	@Override
	public double equity() {
		return inner.equity();
	}

	@Override
	public double marginUsed() {
		return inner.marginUsed();
	}

	@Override
	public double withdrawable() {
		return inner.withdrawable();
	}

	@Override
	public JsonObject snapshot() {
		return inner.snapshot();
	}

	@Override
	public java.util.Map<String, double[]> perpPositions() {
		return inner.perpPositions();
	}
}
