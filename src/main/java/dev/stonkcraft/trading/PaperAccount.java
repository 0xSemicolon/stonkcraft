package dev.stonkcraft.trading;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.stonkcraft.config.HyperConfig;
import dev.stonkcraft.market.MarketData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Simulated Hyperliquid account matched against live mids: market, limit (GTC/ALO/IOC), stop and take-profit
 * orders, TP/SL brackets, per-market leverage, perps and spot. Mutated on the server thread only.
 */
public final class PaperAccount implements TradingAccount {
	/** Where prices and market metadata come from ({@link MarketData} in game, a stub in tests). */
	public interface Quotes {
		@Nullable
		MarketData.Asset asset(String coin);

		double mid(String coin);

		/** Current hourly funding rate for a perp, 0 when unknown. */
		default double funding(String coin) {
			return 0;
		}

		/** The price margin, PnL, liquidation and triggers run on: Hyperliquid's mark. */
		default double mark(String coin) {
			return mid(coin);
		}

		/** The price funding is paid on. */
		default double oracle(String coin) {
			return mark(coin);
		}
	}

	/** A TWAP sliced every 30 seconds until {@code total} is done or time runs out. */
	public static final class Twap {
		public long id;
		public String coin;
		public boolean isBuy;
		public double total;
		public double filled;
		public double notional;
		public long start;
		public int minutes;
		public boolean randomize;
		public boolean reduceOnly;
		public long lastSlice;
		/** A price it won't trade through: slices wait, and catch up (at most 3x) when the price comes back. 0 = none. */
		public double limit;

		long end() {
			return start + minutes * 60_000L;
		}
	}

	public record FundingEvent(long time, String coin, double usdc, double rate, double size) {}

	public static final class Pos {
		public double size;
		public double entry;
		/** An isolated position's own margin, set aside within its pool's USDC; 0 on cross. */
		public double margin;
		/** Funding paid (-) or received (+) since the position opened. */
		public double funding;
		public long opened;
	}

	public static final class Order {
		public long oid;
		public String coin;
		public boolean isBuy;
		public double size;
		public boolean trigger;
		public double limitPx;
		public double triggerPx;
		public String tpsl = "";
		public String tif = "GTC";
		public boolean reduceOnly;
		/** Bracket child waiting on this entry oid; 0 once active. */
		public long parent;
		/** Bracket id shared by an entry and its TP/SL; filling one child cancels the others. */
		public long group;
		public long time;
		/** Client order id of an entry order, or null. */
		@Nullable
		public String cloid;
		/** Size when placed: {@link #size} can shrink to fit what's left of a position. */
		public double origSize;
		/** Stop Limit / Take Limit: triggering rests a limit at {@link #limitPx} rather than sending a market order. */
		public boolean triggerLimit;
		/** A position's own TP/SL: covers the whole position as it grows and shrinks, and goes when it closes or flips. */
		public boolean wholePosition;
		/** Trailing stop: the retrace (%) from the best mark since it started. */
		public double trailPct;
		public double trailBest;
		/** Mark that starts a trailing stop; 0 once it's running. */
		public double activationPx;
		/** Not worth a line in Order History: one slice of a TWAP. */
		public transient boolean slice;

		public String label() {
			if (trailPct > 0) return "Trailing stop";
			if (trigger) return tpsl.equals("sl") ? (triggerLimit ? "Stop limit" : "Stop") : triggerLimit ? "Take limit" : "Take profit";
			return tif.equals("ALO") ? "Limit (post)" : "Limit";
		}
	}

	/** {@code pnl} is closed PnL including this fill's fee; {@code kind} marks a liquidation, "" otherwise. */
	public record Fill(long time, String coin, double size, double price, double pnl, double fee, @Nullable String kind) {}

	/** An order in Order History: how it ended. */
	public record OrderRecord(long time, long oid, String coin, boolean isBuy, String type, double size, double filled, double px,
			boolean reduceOnly, String status) {}

	/** Persistent state (serialized with Gson; new fields need initializers for old saves). */
	public static final class State {
		/** The perps account's USDC: margin for perp positions. Deposits and withdrawals go through it, as on Hyperliquid. */
		public double balance;
		/** The spot account's USDC: what spot buys spend and spot sells pay. Moves to and from perps only by a transfer. */
		public double spotUsdc;
		/**
		 * Hyperliquid's Unified account type: one USDC balance for spot and cross perps, no transfer. Off is its Manual
		 * type, separate spot and perps USDC. Spot holdings are collateral in neither.
		 */
		public boolean unified;
		/** Portfolio Margin, on top of Unified: spot HYPE and BTC count as collateral at a discount, and USDC can go negative against them. */
		public boolean portfolioMargin;
		/** In Manual, each HIP-3 dex's own USDC, margining only that dex's positions ("xyz" -> USDC). */
		public Map<String, Double> dexUsdc = new LinkedHashMap<>();
		/** This save has its dex positions funded from their own pools; false on saves from when one balance margined everything. */
		public boolean dexSplit;
		public double realized;
		public double fees;
		public double netDeposits;
		public Map<String, Pos> positions = new LinkedHashMap<>();
		public Map<String, Double> spot = new LinkedHashMap<>();
		public Map<String, Double> spotCost = new LinkedHashMap<>();
		public Map<String, Integer> leverage = new LinkedHashMap<>();
		public Map<String, Boolean> isolated = new LinkedHashMap<>();
		public List<Order> orders = new ArrayList<>();
		public long nextOid = 1;
		public Map<String, Long> lastFill = new LinkedHashMap<>();
		public List<Twap> twaps = new ArrayList<>();
		/** Dead man's switch deadline, 0 when not armed. */
		public long scheduledCancel;
		/** Last settled funding hour (epoch hours). */
		public long lastFundingHour;
		public Deque<FundingEvent> funding = new ArrayDeque<>();
		public Deque<Fill> fills = new ArrayDeque<>();
		public Deque<OrderRecord> orderHistory = new ArrayDeque<>();
		public List<double[]> equityHistory = new ArrayList<>();
		/** A longer record than {@link #equityHistory} for the Account view: {time, equity, net deposits} every half hour. */
		public List<double[]> equityLong = new ArrayList<>();
		/** Weighted volume by UTC day, for the fee tier. */
		public Map<Long, Double> volume = new LinkedHashMap<>();
		public int feeTier;
		/** When each pool (or isolated coin) may next be stepped down: a large position goes to the book a fifth at a time. */
		public Map<String, Long> liqCooldown = new LinkedHashMap<>();
		/** Recent client order ids and how they ended (RESTING while placed, then FILLED or REJECTED). */
		public LinkedHashMap<String, String> cloids = new LinkedHashMap<>();
	}

	private static final int MAX_CLOIDS = 256;

	/** What's kept; a snapshot carries the first {@link #SNAPSHOT_ROWS}, and the history packet the rest. */
	private static final int MAX_FILLS = 200;
	private static final int SNAPSHOT_ROWS = 40;
	private static final int MAX_EQUITY_POINTS = 240;
	private static final int MAX_EQUITY_LONG = 1440;
	private static final long EQUITY_LONG_EVERY_MS = 30 * 60_000L;
	private static final double PAPER_SLIP = 0.0002;
	/** Funding is capped at 4% an hour. */
	private static final double FUNDING_CAP = 0.04;
	/** Profit can only leave above this share of position value (or initial margin, if that's more). */
	private static final double TRANSFER_MARGIN_SHARE = 0.1;
	private static final double TWAP_MIN_USD = 100;
	private static final double PM_LIQUIDATES_AT = 0.95;
	private static final double PM_MIN_EQUITY = 10_000;
	/** Spot tokens Portfolio Margin takes as collateral, and the share of their value that counts. */
	private static final Map<String, Double> PM_LTV = Map.of("HYPE", 0.65, "BTC", 0.5, "UBTC", 0.5);

	private final State state;
	private final Supplier<? extends Quotes> quotes;
	/** Said once at the next tick: things that happen on an order, not on the clock. */
	private final List<String> pendingEvents = new ArrayList<>();

	public PaperAccount(State state, Supplier<? extends Quotes> quotes) {
		this.state = state;
		this.quotes = quotes;
		if (state.spot == null) state.spot = new LinkedHashMap<>();
		if (state.spotCost == null) state.spotCost = new LinkedHashMap<>();
		if (state.leverage == null) state.leverage = new LinkedHashMap<>();
		if (state.isolated == null) state.isolated = new LinkedHashMap<>();
		if (state.orders == null) state.orders = new ArrayList<>();
		if (state.lastFill == null) state.lastFill = new LinkedHashMap<>();
		if (state.nextOid <= 0) state.nextOid = 1;
		if (state.twaps == null) state.twaps = new ArrayList<>();
		if (state.funding == null) state.funding = new ArrayDeque<>();
		if (state.cloids == null) state.cloids = new LinkedHashMap<>();
		if (state.dexUsdc == null) state.dexUsdc = new LinkedHashMap<>();
		if (state.orderHistory == null) state.orderHistory = new ArrayDeque<>();
		if (state.equityLong == null) state.equityLong = new ArrayList<>();
		if (state.volume == null) state.volume = new LinkedHashMap<>();
		if (state.liqCooldown == null) state.liqCooldown = new LinkedHashMap<>();
	}

	private void markCloid(@Nullable String cloid, OrderStatus status) {
		if (cloid == null) return;
		state.cloids.remove(cloid);
		state.cloids.put(cloid, status.name());
		while (state.cloids.size() > MAX_CLOIDS) state.cloids.remove(state.cloids.keySet().iterator().next());
	}

	@Nullable
	private Order byCloid(String cloid) {
		for (Order o : state.orders) if (cloid.equalsIgnoreCase(o.cloid)) return o;
		return null;
	}

	@Override
	public OrderStatus orderStatus(String cloid) {
		if (byCloid(cloid) != null) return OrderStatus.RESTING;
		String s = state.cloids.get(cloid);
		if (s == null) return OrderStatus.NONE;
		// still marked resting but no longer on the book: cancelled (by hand, dead man's switch, OCO...)
		return s.equals("RESTING") ? OrderStatus.CANCELLED : OrderStatus.valueOf(s);
	}

	@Override
	public CompletableFuture<OrderResult> cancelByCloid(String coin, String cloid) {
		Order o = byCloid(cloid);
		if (o == null) return CompletableFuture.completedFuture(OrderResult.fail(coin, "No open order with that id"));
		dropOrders(x -> x == o || x.parent == o.oid, "Cancelled");
		return CompletableFuture.completedFuture(new OrderResult(true, "Cancelled order #" + o.oid, o.coin, 0, 0));
	}

	@Override
	public CompletableFuture<OrderResult> modify(String cloid, OrderRequest r) {
		return CompletableFuture.completedFuture(amend(cloid, r));
	}

	private OrderResult amend(String cloid, OrderRequest r) {
		Order o = byCloid(cloid);
		if (o == null) return OrderResult.fail(r.coin, "No resting order to amend");
		MarketData.Asset a = asset(o.coin);
		double mid = mid(o.coin);
		if (a == null || Double.isNaN(mid)) return OrderResult.fail(o.coin, "No price for " + o.coin);
		OrderRequest req = r.copyFor(o.coin);
		OrderPlanner.Plan plan;
		try {
			plan = OrderPlanner.plan(req, a, mid, positionSize(o.coin), slippage(req));
		} catch (OrderPlanner.PlanException e) {
			return OrderResult.fail(o.coin, e.getMessage());
		}
		OrderPlanner.Leg e = plan.entry();
		if (e.trigger() != o.trigger || e.immediate()) return OrderResult.fail(o.coin, "An amend can't change the order type");
		if (!o.trigger) {
			boolean crosses = e.isBuy() ? e.limitPx() >= mid : e.limitPx() <= mid;
			if (crosses && o.tif.equals("ALO")) return OrderResult.fail(o.coin, "Post-only amend would cross the book");
		}
		o.isBuy = e.isBuy();
		o.size = e.size();
		o.origSize = e.size();
		o.limitPx = e.limitPx();
		o.triggerPx = e.triggerPx();
		o.reduceOnly = e.reduceOnly();
		// brackets follow their entry at the request's TP/SL offsets
		for (Order child : state.orders) {
			if (child.parent != o.oid) continue;
			OrderPlanner.Leg leg = child.tpsl.equals("tp") ? plan.tp() : plan.sl();
			if (leg == null) continue;
			child.size = leg.size();
			child.triggerPx = leg.triggerPx();
			child.limitPx = leg.limitPx();
		}
		if (!o.trigger && (o.isBuy ? o.limitPx >= mid : o.limitPx <= mid)) {
			state.orders.remove(o);
			Result res = fill(o, mid * (1 + Math.copySign(PAPER_SLIP, o.isBuy ? 1 : -1)), false);
			if (res.error != null) return OrderResult.fail(o.coin, res.error);
			return new OrderResult(true, "Amend crossed: " + res.message, o.coin, o.isBuy ? o.size : -o.size, 0);
		}
		return new OrderResult(true, "Amended #" + o.oid + " to " + Fmt.size(o.size) + " @ " + Fmt.price(o.trigger ? o.triggerPx : o.limitPx), o.coin, 0, 0);
	}

	/** New accounts start empty; fund them by depositing coins. */
	public static State fresh() {
		State s = new State();
		s.dexSplit = true;
		return s;
	}

	public State state() {
		return state;
	}

	@Override
	public String mode() {
		return "PAPER";
	}

	// ------------------------------------------------------------------ valuation

	private double mid(String coin) {
		return quotes.get().mid(coin);
	}

	private double mark(String coin, Pos p) {
		double m = quotes.get().mark(coin);
		return Double.isNaN(m) ? p.entry : m;
	}

	/** The market's own maximum leverage, which sets its maintenance margin. */
	private int marketMax(String coin) {
		MarketData.Asset a = asset(coin);
		return a == null ? 20 : a.maxLeverage();
	}

	/**
	 * Whose USDC margins a perp: "" is the perps account, a dex name is that dex's own balance. Unified has one balance
	 * for everything; Manual keeps each HIP-3 dex apart, as Hyperliquid does.
	 */
	private String poolOf(String coin) {
		if (state.unified) return "";
		MarketData.Asset a = asset(coin);
		return a == null || a.spot() ? "" : a.dex();
	}

	private double poolUsdc(String pool) {
		return pool.isEmpty() ? state.balance : state.dexUsdc.getOrDefault(pool, 0.0);
	}

	private void addPool(String pool, double usd) {
		if (pool.isEmpty()) state.balance += usd;
		else state.dexUsdc.merge(pool, usd, Double::sum);
	}

	private double notional(String coin, Pos p) {
		return Math.abs(p.size) * mark(coin, p);
	}

	/** Initial margin at the player's leverage: an isolated position's is its own {@link Pos#margin}. */
	private double initialMargin(String coin, Pos p) {
		return MarginMath.initialMargin(coin, marketMax(coin), leverage(coin), notional(coin, p));
	}

	private double maintenance(String coin, Pos p) {
		return MarginMath.maintenance(coin, marketMax(coin), notional(coin, p));
	}

	/** A pool's cross account value: its USDC less what isolated positions hold, plus cross PnL (and, in Portfolio Margin, collateral). */
	private double crossEquity(String pool) {
		double eq = poolUsdc(pool);
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) {
			if (!poolOf(e.getKey()).equals(pool)) continue;
			if (isCross(e.getKey())) eq += unrealizedPnl(e.getKey());
			else eq -= e.getValue().margin;
		}
		if (pool.isEmpty() && state.portfolioMargin) eq += collateral();
		return eq;
	}

	private double crossInitial(String pool) {
		double m = 0;
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) {
			if (poolOf(e.getKey()).equals(pool) && isCross(e.getKey())) m += initialMargin(e.getKey(), e.getValue());
		}
		return m;
	}

	private double crossMaintenance(String pool) {
		double m = 0;
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) {
			if (poolOf(e.getKey()).equals(pool) && isCross(e.getKey())) m += maintenance(e.getKey(), e.getValue());
		}
		return m;
	}

	private double crossValue(String pool) {
		double v = 0;
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) {
			if (poolOf(e.getKey()).equals(pool) && isCross(e.getKey())) v += notional(e.getKey(), e.getValue());
		}
		return v;
	}

	/** Spot tokens counted as Portfolio Margin collateral, at their LTV. */
	private double collateral() {
		double v = 0;
		for (Map.Entry<String, Double> e : state.spot.entrySet()) {
			MarketData.Asset a = asset(e.getKey());
			Double ltv = a == null ? null : PM_LTV.get(a.base());
			double m = mid(e.getKey());
			if (ltv != null && !Double.isNaN(m)) v += e.getValue() * m * ltv;
		}
		return v;
	}

	/** What new margin in this pool can come from. */
	public double available(String pool) {
		return Math.max(0, crossEquity(pool) - crossInitial(pool));
	}

	/**
	 * What can leave a pool (§1.3): its free USDC, not counting profit; or, when there's profit, the account value above
	 * max(initial margin, 10% of position value). Portfolio Margin's collateral never leaves as USDC.
	 */
	private double withdrawable(String pool) {
		double im = crossInitial(pool);
		double eq = crossEquity(pool) - (pool.isEmpty() && state.portfolioMargin ? collateral() : 0);
		double upnl = 0;
		for (String coin : state.positions.keySet()) if (poolOf(coin).equals(pool) && isCross(coin)) upnl += unrealizedPnl(coin);
		double cash = eq - Math.max(0, upnl) - im;
		double withProfit = eq - Math.max(im, TRANSFER_MARGIN_SHARE * crossValue(pool));
		return Math.max(0, Math.floor(Math.max(cash, withProfit) * 100) / 100);
	}

	/** Fee at this account's tier, scaled with the server's taker fee setting (Hyperliquid's by default). */
	private double feeRate(boolean spot, boolean maker) {
		return FeeSchedule.rate(state.feeTier, spot, maker) * HyperConfig.get().paperTakerFee / FeeSchedule.rate(0, false, false);
	}

	private double slippage(OrderRequest r) {
		if (r.side == Side.CLOSE) return OrderPlanner.CLOSE_SLIPPAGE;
		return r.maxSlippage > 0 ? r.maxSlippage : HyperConfig.get().slippage;
	}

	/** Rolling 14-day volume, spot counted double. */
	public double volume14d(long now) {
		long today = now / 86_400_000L;
		double v = 0;
		for (Map.Entry<Long, Double> e : state.volume.entrySet()) if (e.getKey() > today - FeeSchedule.WINDOW_DAYS) v += e.getValue();
		return v;
	}

	private void addVolume(long now, double notional, boolean spot) {
		long today = now / 86_400_000L;
		state.volume.merge(today, notional * (spot ? FeeSchedule.SPOT_VOLUME_WEIGHT : 1), Double::sum);
		state.volume.keySet().removeIf(d -> d <= today - FeeSchedule.WINDOW_DAYS);
		int tier = FeeSchedule.tier(volume14d(now));
		if (tier > state.feeTier) pendingEvents.add("\u00a76Fee tier " + tier + " reached: perps taker " + String.format(java.util.Locale.ROOT, "%.3f%%", FeeSchedule.rate(tier, false, false) * 100));
		state.feeTier = tier;
	}

	/** Removes orders matching the test, recording how each ended. */
	private void dropOrders(java.util.function.Predicate<Order> test, String status) {
		state.orders.removeIf(o -> {
			if (!test.test(o)) return false;
			archive(o, status, 0);
			return true;
		});
	}

	private void archive(Order o, String status, double filled) {
		if (o.slice) return;
		state.orderHistory.addFirst(new OrderRecord(System.currentTimeMillis(), o.oid, o.coin, o.isBuy, o.label(),
				o.origSize > 0 ? o.origSize : o.size, filled, o.trigger ? o.triggerPx : o.limitPx, o.reduceOnly, status));
		while (state.orderHistory.size() > MAX_FILLS) state.orderHistory.removeLast();
	}

	@Nullable
	private MarketData.Asset asset(String coin) {
		return quotes.get().asset(coin);
	}

	@Override
	public double positionSize(String coin) {
		MarketData.Asset a = asset(coin);
		String name = a == null ? coin : a.name();
		if (a != null && a.spot()) return state.spot.getOrDefault(name, 0.0);
		Pos p = state.positions.get(name);
		return p == null ? 0 : p.size;
	}

	@Override
	public double unrealizedPnl(String coin) {
		Pos p = state.positions.get(coin);
		return p == null ? 0 : p.size * (mark(coin, p) - p.entry);
	}

	private double spotValue() {
		double v = 0;
		for (Map.Entry<String, Double> e : state.spot.entrySet()) {
			double m = mid(e.getKey());
			if (!Double.isNaN(m)) v += e.getValue() * m;
		}
		return v;
	}

	/** Everything: both accounts' USDC, spot holdings at mid, and open perp PnL. */
	@Override
	public double equity() {
		return perpEquity() + spotEquity();
	}

	/** The perps side alone, every dex's: USDC and open PnL. Spot USDC and spot holdings aren't part of it. */
	public double perpEquity() {
		double eq = state.balance;
		for (double d : state.dexUsdc.values()) eq += d;
		for (String coin : state.positions.keySet()) eq += unrealizedPnl(coin);
		return eq;
	}

	/** The spot account: its USDC and its holdings at mid. */
	public double spotEquity() {
		return state.spotUsdc + spotValue();
	}

	public double spotUsdc() {
		return state.spotUsdc;
	}

	public boolean unified() {
		return state.unified;
	}

	public boolean portfolioMargin() {
		return state.portfolioMargin;
	}

	/** Switches account type. Into Unified, spot and dex USDC join the one balance; into Manual, it all starts on the perps side. */
	public void setUnified(boolean on) {
		if (on && !state.unified) {
			state.balance += state.spotUsdc;
			state.spotUsdc = 0;
			for (double d : state.dexUsdc.values()) state.balance += d;
			state.dexUsdc.clear();
		}
		boolean toManual = !on && state.unified;
		state.unified = on;
		if (!on) state.portfolioMargin = false;
		if (toManual) splitDexes();
	}

	/** Portfolio Margin on or off. Returns why not, or null. */
	@Nullable
	public String setPortfolioMargin(boolean on) {
		if (!on) {
			if (state.balance < 0) return "USD is borrowed against your collateral: repay " + Fmt.usd(-state.balance) + " first";
			state.portfolioMargin = false;
			return null;
		}
		if (equity() < PM_MIN_EQUITY) return "Portfolio Margin takes " + Fmt.usd(PM_MIN_EQUITY) + " of account value";
		setUnified(true);
		state.portfolioMargin = true;
		return null;
	}

	/**
	 * Gives each dex with open positions its own USDC, from the one balance: its initial margin less its PnL, scaled
	 * down if the account can't cover all of its margin. Without this those positions would have no collateral at all.
	 */
	private void splitDexes() {
		state.dexSplit = true;
		if (state.unified) return;
		// the sums below need every position's dex and price: wait for the market if it isn't up yet
		for (String coin : state.positions.keySet()) {
			if (asset(coin) == null) {
				state.dexSplit = false;
				return;
			}
		}
		Map<String, Double> im = new LinkedHashMap<>(), upnl = new LinkedHashMap<>();
		double total = 0;
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) {
			double m = isCross(e.getKey()) ? initialMargin(e.getKey(), e.getValue()) : e.getValue().margin;
			total += m;
			String pool = poolOf(e.getKey());
			if (pool.isEmpty()) continue;
			im.merge(pool, m, Double::sum);
			upnl.merge(pool, unrealizedPnl(e.getKey()), Double::sum);
		}
		double scale = total <= 0 ? 1 : Math.min(1, Math.max(0, perpEquity()) / total);
		for (Map.Entry<String, Double> e : im.entrySet()) {
			double move = e.getValue() * scale - upnl.getOrDefault(e.getKey(), 0.0) - poolUsdc(e.getKey());
			addPool(e.getKey(), move);
			state.balance -= move;
		}
	}

	/** USDC a spot buy may spend: the spot balance, or in Unified the USDC not held as margin. Collateral buys no spot. */
	private double spotSpendable() {
		if (!state.unified) return state.spotUsdc;
		return Math.max(0, available("") - (state.portfolioMargin ? collateral() : 0));
	}

	@Override
	public int leverage(String coin) {
		Integer set = state.leverage.get(coin);
		if (set != null) return set;
		MarketData.Asset a = asset(coin);
		int max = a == null ? 20 : a.maxLeverage();
		return (int) Math.max(1, Math.min(Math.min(HyperConfig.get().paperDefaultLeverage, HyperConfig.get().paperMaxLeverage), max));
	}

	@Override
	public boolean isCross(String coin) {
		return !state.isolated.getOrDefault(coin, false);
	}

	@Override
	public double marginUsed() {
		double m = 0;
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) {
			m += isCross(e.getKey()) ? initialMargin(e.getKey(), e.getValue()) : e.getValue().margin;
		}
		return m;
	}

	/** What can leave the perps account: withdrawn, moved to spot or to a dex. */
	@Override
	public double withdrawable() {
		return withdrawable("");
	}

	/**
	 * The mark price a position liquidates at, from §6's formula, or NaN when no positive price does. Cross looks at its
	 * whole pool's margin, assuming other positions' PnL stands still; isolated, at its own.
	 */
	public double liquidationPrice(String coin) {
		Pos p = state.positions.get(coin);
		if (p == null) return Double.NaN;
		double avail = isCross(coin) ? crossEquity(poolOf(coin)) - crossMaintenance(poolOf(coin)) : p.margin + unrealizedPnl(coin) - maintenance(coin, p);
		return MarginMath.liquidationPrice(mark(coin, p), p.size, avail, MarginMath.maintenanceRate(coin, marketMax(coin), notional(coin, p)));
	}

	/** Cross margin ratio: maintenance margin over account value, across pools the worst; 100% and over gets liquidated. */
	public double marginRatio() {
		double worst = 0;
		for (String pool : pools()) {
			double mm = crossMaintenance(pool);
			if (mm <= 0) continue;
			double eq = crossEquity(pool);
			worst = Math.max(worst, eq <= 0 ? 1 : mm / eq);
		}
		return worst;
	}

	private List<String> pools() {
		List<String> pools = new ArrayList<>();
		pools.add("");
		for (String coin : state.positions.keySet()) {
			String pool = poolOf(coin);
			if (!pools.contains(pool)) pools.add(pool);
		}
		for (String pool : state.dexUsdc.keySet()) if (!pools.contains(pool)) pools.add(pool);
		return pools;
	}

	/**
	 * Moves USDC between the spot and perps accounts, as Hyperliquid's Transfer does. To spot, only free perps
	 * USDC can go (not margin in use); to perps, any spot USDC. Returns why not, or null.
	 */
	@Nullable
	public String transfer(double usd, boolean toPerps) {
		if (state.unified) return "A Unified account has one USD balance: switch it to Manual to keep spot and perps apart";
		if (!(usd > 0) || !Double.isFinite(usd)) return "Enter an amount";
		if (toPerps) {
			if (usd > state.spotUsdc + 1e-9) return "Only " + Fmt.usd(state.spotUsdc) + " USD in spot";
			state.spotUsdc -= usd;
			state.balance += usd;
		} else {
			if (usd > withdrawable() + 1e-9) return "Only " + Fmt.usd(withdrawable()) + " of perps USD is free (the rest is margin)";
			state.balance -= usd;
			state.spotUsdc += usd;
		}
		return null;
	}

	/**
	 * Moves USDC between the perps account and a HIP-3 dex's own balance. In Manual each dex margins itself, so stocks and
	 * commodities need USDC moved over first. Returns why not, or null.
	 */
	@Nullable
	public String transferDex(double usd, String dex, boolean toDex) {
		if (state.unified) return "A Unified account has one USD balance: switch it to Manual to keep dexes apart";
		if (!(usd > 0) || !Double.isFinite(usd)) return "Enter an amount";
		if (dex == null || !MarketData.isExtraDex(dex)) return "No such dex";
		String from = toDex ? "" : dex;
		double free = withdrawable(from);
		if (usd > free + 1e-9) return "Only " + Fmt.usd(free) + " of " + (toDex ? "perps" : Fmt.pool(dex)) + " USD is free (the rest is margin)";
		addPool(from, -usd);
		addPool(toDex ? dex : "", usd);
		return null;
	}

	@Override
	public int openOrders(@Nullable String coin) {
		int n = 0;
		for (Order o : state.orders) if (coin == null || o.coin.equals(coin)) n++;
		for (Twap t : state.twaps) if (coin == null || t.coin.equals(coin)) n++;
		return n;
	}

	@Override
	public long lastFillTime(String coin) {
		return state.lastFill.getOrDefault(coin, 0L);
	}

	public void deposit(double usd) {
		state.balance += usd;
		state.netDeposits += usd;
	}

	/** Removes whole dollars if free collateral allows it. */
	public boolean withdraw(double usd) {
		if (usd <= 0 || usd > withdrawable()) return false;
		state.balance -= usd;
		state.netDeposits -= usd;
		return true;
	}

	// ------------------------------------------------------------------ physical settlement (Commodity Exchange)

	/** Metal bridged in as a spot balance: {@code qty} of a Unit token worth {@code px} each. */
	public void creditSpot(String market, double qty, double px) {
		state.spot.merge(market, qty, Double::sum);
		state.spotCost.merge(market, qty * px, Double::sum);
		state.netDeposits += qty * px;
	}

	/** Takes {@code qty} of a spot balance out to be handed over as metal. Returns why not, or null. */
	@Nullable
	public String debitSpot(String market, double qty, double px) {
		double held = state.spot.getOrDefault(market, 0.0);
		if (qty <= 0) return "Nothing to withdraw";
		if (qty > held + 1e-9) return "Only " + Fmt.size(held) + " held";
		double left = held - qty;
		if (left <= 1e-9) {
			state.spot.remove(market);
			state.spotCost.remove(market);
		} else {
			state.spotCost.put(market, state.spotCost.getOrDefault(market, 0.0) * left / held);
			state.spot.put(market, left);
		}
		state.netDeposits -= qty * px;
		return null;
	}

	/**
	 * Metal bridged in as a fully collateralised (1x) long on {@code coin}: the deposit's value goes into the balance
	 * and the same quantity is bought at {@code px}, fee-free. A new position is set to 1x. Into an existing short, it
	 * settles the short.
	 */
	public void depositLong(String coin, double qty, double px) {
		// a fresh position is literally 1x; topping up an existing one keeps the leverage the player chose
		if (!state.positions.containsKey(coin) && qty > 0) state.leverage.put(coin, 1);
		addPool(poolOf(coin), qty * px);
		state.netDeposits += qty * px;
		double pnl = applyPerpFill(coin, qty, px, 0);
		settled(coin, qty, px, pnl);
	}

	/**
	 * How much of a long can leave as metal: the position's own equity in units, {@code (S·E/L + S·(P − E)) / P}.
	 * At 1x that's the whole position; at Nx about a 1/N share.
	 */
	public double longWithdrawable(String coin, double px) {
		Pos p = state.positions.get(coin);
		if (p == null || p.size <= 0 || !(px > 0)) return 0;
		double e = p.size * p.entry / leverage(coin) + p.size * (px - p.entry);
		return Math.max(0, Math.min(p.size, e / px));
	}

	/**
	 * Withdraws {@code k} units of metal out of a long: closes the same share of the position as {@code k} is of
	 * {@link #longWithdrawable}, fee-free at {@code px}, then pays {@code k·px} out of the balance. The rest of the
	 * position keeps its leverage. Returns why not, or null.
	 */
	@Nullable
	public String withdrawLong(String coin, double k, double px) {
		Pos p = state.positions.get(coin);
		double kmax = longWithdrawable(coin, px);
		if (p == null || p.size <= 0) return "No long " + coin + " position";
		if (k <= 0) return "Nothing to withdraw";
		if (k > kmax + 1e-9) return "Only " + Fmt.size(kmax) + " of the position is yours to take at " + leverage(coin) + "x";
		double close = Math.min(p.size, p.size * k / kmax);
		double freed = close * px / leverage(coin);
		String pool = poolOf(coin);
		double poolEquity = poolUsdc(pool);
		double poolUsed = 0;
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) {
			if (!poolOf(e.getKey()).equals(pool)) continue;
			poolEquity += unrealizedPnl(e.getKey());
			poolUsed += isCross(e.getKey()) ? initialMargin(e.getKey(), e.getValue()) : e.getValue().margin;
		}
		if (poolEquity - k * px < poolUsed - freed - 1e-6) return "The rest of the account would fall below its margin";
		double pnl = applyPerpFill(coin, -close, px, 0);
		addPool(pool, -k * px);
		state.netDeposits -= k * px;
		settled(coin, -close, px, pnl);
		return null;
	}

	/** Records a physical settlement in the fills list (it's a trade, but not an order fill sensors listen for). */
	private void settled(String coin, double signed, double px, double pnl) {
		state.fills.addFirst(new Fill(System.currentTimeMillis(), coin, signed, px, pnl, 0, ""));
		while (state.fills.size() > MAX_FILLS) state.fills.removeLast();
		if (positionSize(coin) == 0) dropOrders(x -> x.coin.equals(coin) && x.reduceOnly && x.parent == 0, "Cancelled");
	}

	@Override
	public Map<String, double[]> perpPositions() {
		Map<String, double[]> out = new LinkedHashMap<>();
		state.positions.forEach((coin, p) -> out.put(coin, new double[]{p.size, mark(coin, p)}));
		return out;
	}

	/** Spot balance of a market, 0 if none. */
	public double spotBalance(String market) {
		return state.spot.getOrDefault(market, 0.0);
	}

	// ------------------------------------------------------------------ orders

	@Override
	public CompletableFuture<OrderResult> submit(OrderRequest r) {
		OrderResult res = execute(r);
		if (!res.ok() && r.cloid != null && !state.cloids.containsKey(r.cloid)) markCloid(r.cloid, OrderStatus.REJECTED);
		return CompletableFuture.completedFuture(res);
	}

	private OrderResult execute(OrderRequest r) {
		MarketData.Asset a = asset(r.coin);
		if (a == null) return OrderResult.fail(r.coin, "Unknown market " + r.coin);
		String coin = a.name();
		double mid = mid(coin);
		if (r.type == OrderRequest.Type.TWAP) return startTwap(r, a, mid);
		if (r.type == OrderRequest.Type.SCALE) return startScale(r, a, mid);
		if (r.type == OrderRequest.Type.TRAILING) return startTrailing(r, a, mid);
		OrderPlanner.Plan plan;
		try {
			plan = OrderPlanner.plan(r, a, mid, positionSize(coin), slippage(r));
		} catch (OrderPlanner.PlanException e) {
			return OrderResult.fail(coin, e.getMessage());
		}
		// a paper market order slips a fixed hair: a tolerance under that can't match, as Hyperliquid says when it's too tight
		if (plan.entry().immediate() && r.type == OrderRequest.Type.MARKET && slippage(r) < PAPER_SLIP) {
			return OrderResult.fail(coin, "Order could not match against any resting orders. Consider increasing max slippage.");
		}
		String desc = OrderPlanner.describe(plan);
		long group = plan.tp() != null || plan.sl() != null ? state.nextOid : 0;
		Order entry = toOrder(plan.entry(), coin, 0, group);
		entry.cloid = r.cloid;

		boolean filled = false;
		String fillMsg = null;
		if (!entry.trigger) {
			boolean crosses = entry.isBuy ? entry.limitPx >= mid : entry.limitPx <= mid;
			if (plan.entry().immediate() || crosses) {
				if (!crosses) return OrderResult.fail(coin, "IOC limit would not fill at " + Fmt.price(entry.limitPx));
				if (entry.tif.equals("ALO") && !plan.entry().immediate()) return OrderResult.fail(coin, "Post-only order would cross the book");
				double px = mid * (1 + Math.copySign(PAPER_SLIP, entry.isBuy ? 1 : -1));
				Result res = fill(entry, px, false);
				if (res.error != null) {
					archive(entry, "Rejected", 0);
					return OrderResult.fail(coin, res.error);
				}
				filled = true;
				fillMsg = res.message;
			}
		}
		if (!filled) {
			state.orders.add(entry);
			markCloid(entry.cloid, OrderStatus.RESTING);
		}
		for (OrderPlanner.Leg leg : new OrderPlanner.Leg[]{plan.tp(), plan.sl()}) {
			if (leg != null) state.orders.add(toOrder(leg, coin, filled ? 0 : entry.oid, group));
		}
		if (filled) {
			double signed = entry.isBuy ? entry.size : -entry.size;
			return new OrderResult(true, fillMsg + (group != 0 ? " + TP/SL armed" : ""), coin, signed, 0);
		}
		return new OrderResult(true, "Placed " + desc, coin, 0, 0);
	}

	private Order toOrder(OrderPlanner.Leg leg, String coin, long parent, long group) {
		Order o = new Order();
		o.oid = state.nextOid++;
		o.coin = coin;
		o.isBuy = leg.isBuy();
		o.size = leg.size();
		o.origSize = leg.size();
		o.triggerLimit = leg.triggerLimit();
		o.trigger = leg.trigger();
		o.limitPx = leg.limitPx();
		o.triggerPx = leg.triggerPx();
		o.tpsl = leg.tpsl();
		o.tif = leg.tif().name();
		o.reduceOnly = leg.reduceOnly();
		o.parent = parent;
		o.group = group;
		o.time = System.currentTimeMillis();
		return o;
	}

	private record Result(@Nullable String error, String message) {
		static Result err(String e) {
			return new Result(e, "");
		}
	}

	/** Fills an order now at px. Handles reduce-only clipping, margin/balance checks, brackets and OCO. */
	private Result fill(Order o, double px, boolean maker) {
		MarketData.Asset a = asset(o.coin);
		if (a == null) return Result.err("Unknown market " + o.coin);
		double fee = feeRate(a.spot(), maker);
		// a position's own TP/SL covers whatever the position is now
		if (o.wholePosition) o.size = Math.abs(positionSize(o.coin));
		double size = o.size;
		double feePaid = 0;
		String coin = o.coin;
		double pnl = 0;

		if (a.spot()) {
			double held = state.spot.getOrDefault(coin, 0.0);
			if (o.isBuy) {
				double cost = size * px * (1 + fee);
				if (cost > spotSpendable() + 1e-9) return Result.err(state.unified ? "Insufficient USD (" + Fmt.usd(spotSpendable()) + " free)"
						: "Insufficient spot USD (" + Fmt.usd(state.spotUsdc) + "): transfer some from Perps");
				if (state.unified) state.balance -= cost;
				else state.spotUsdc -= cost;
				state.fees += size * px * fee;
				state.spot.put(coin, held + size);
				state.spotCost.merge(coin, cost, Double::sum);
			} else {
				size = Math.min(size, held);
				if (size <= 0) return Result.err("No " + a.base() + " to sell");
				double avg = held > 0 ? state.spotCost.getOrDefault(coin, 0.0) / held : 0;
				double proceeds = size * px * (1 - fee);
				// a sale pays the spot account: in Manual it needs a transfer before it can margin a perp
				if (state.unified) state.balance += proceeds;
				else state.spotUsdc += proceeds;
				state.fees += size * px * fee;
				pnl = proceeds - avg * size;
				state.realized += pnl;
				double left = held - size;
				if (left <= 1e-12) {
					state.spot.remove(coin);
					state.spotCost.remove(coin);
				} else {
					state.spot.put(coin, left);
					state.spotCost.put(coin, avg * left);
				}
			}
		} else {
			Pos p = state.positions.get(coin);
			double current = p == null ? 0 : p.size;
			if (o.reduceOnly) {
				if (current == 0 || (current > 0) == o.isBuy) return Result.err("Reduce-only: no " + coin + " position to reduce");
				size = Math.min(size, Math.abs(current));
			}
			double signed = o.isBuy ? size : -size;
			double after = Math.abs(current + signed);
			feePaid = size * px * fee;
			if (after > Math.abs(current) + 1e-12) {
				String why = marginCheck(coin, p, current, signed, px, feePaid);
				if (why != null) return Result.err(why);
			}
			double realized = applyPerpFill(coin, signed, px, fee);
			// closed PnL includes the fee, as Trade History has it; an opening fill closes nothing
			pnl = realized != 0 ? realized - feePaid : 0;
		}

		double signed = o.isBuy ? size : -size;
		long now = System.currentTimeMillis();
		if (a.spot()) feePaid = size * px * fee;
		state.fills.addFirst(new Fill(now, coin, signed, px, pnl, feePaid, ""));
		while (state.fills.size() > MAX_FILLS) state.fills.removeLast();
		state.lastFill.put(coin, now);
		markCloid(o.cloid, OrderStatus.FILLED);
		addVolume(now, size * px, a.spot());
		archive(o, "Filled", size);

		// arm bracket children, and make TP/SL one-cancels-other
		for (Order child : state.orders) {
			if (child.parent == o.oid) {
				child.parent = 0;
				child.size = Math.min(child.size, size);
			}
		}
		if (o.group != 0 && o.reduceOnly) dropOrders(x -> x.group == o.group && x.oid != o.oid, "Cancelled");
		if (!a.spot() && positionSize(coin) == 0) dropOrders(x -> x.coin.equals(coin) && x.reduceOnly && x.parent == 0, "Cancelled");

		String verb = o.isBuy ? "Bought" : "Sold";
		String msg = String.format("%s %s %s @ %s", verb, Fmt.size(size), a.spot() ? a.base() : Fmt.market(coin), Fmt.price(px));
		if (pnl != 0) msg += " (PnL " + Fmt.usdSigned(pnl) + ")";
		return new Result(null, msg);
	}

	/** Why a position can't grow by this fill, or null. */
	@Nullable
	private String marginCheck(String coin, @Nullable Pos p, double current, double signed, double px, double fee) {
		String pool = poolOf(coin);
		int lev = leverage(coin);
		int max = marketMax(coin);
		double after = current + signed;
		double initialAfter = MarginMath.initialMargin(coin, max, lev, Math.abs(after) * px);
		boolean over;
		if (isCross(coin)) {
			double others = crossInitial(pool) - (p == null ? 0 : initialMargin(coin, p));
			over = others + initialAfter + fee > crossEquity(pool) + 1e-9;
		} else {
			// an isolated position takes what it adds from the pool's free USDC; a flip gets back what the old side held first
			boolean flip = current != 0 && Math.signum(after) != Math.signum(current);
			double add = flip ? initialAfter - p.margin - unrealizedPnl(coin)
					: initialAfter - MarginMath.initialMargin(coin, max, lev, Math.abs(current) * px);
			over = add + fee > available(pool) + 1e-9;
		}
		if (!over) return null;
		if (!pool.isEmpty()) return "Insufficient " + Fmt.pool(pool) + " margin at " + lev + "x (" + Fmt.usd(available(pool)) + " free): transfer USD to " + Fmt.pool(pool);
		return String.format(java.util.Locale.ROOT, "Insufficient margin at %dx", lev);
	}

	/** Perp fill bookkeeping, for one pool and (when isolated) the position's own margin; returns realized PnL. */
	private double applyPerpFill(String coin, double signed, double px, double feeRate) {
		String pool = poolOf(coin);
		boolean iso = !isCross(coin);
		double fee = Math.abs(signed) * px * feeRate;
		addPool(pool, -fee);
		state.fees += fee;
		Pos p = state.positions.computeIfAbsent(coin, k -> new Pos());
		double realized = 0;
		if (p.size == 0 || Math.signum(p.size) == Math.signum(signed)) {
			double newSize = p.size + signed;
			p.entry = (p.entry * Math.abs(p.size) + px * Math.abs(signed)) / Math.abs(newSize);
			if (p.size == 0) p.opened = System.currentTimeMillis();
			p.size = newSize;
			if (iso) p.margin += MarginMath.initialMargin(coin, marketMax(coin), leverage(coin), Math.abs(signed) * px);
		} else {
			double before = Math.abs(p.size);
			double closing = Math.min(Math.abs(signed), before);
			realized = closing * (px - p.entry) * Math.signum(p.size);
			double newSize = p.size + signed;
			if (Math.abs(newSize) < 1e-12) newSize = 0;
			if (iso) {
				// the closed share's margin goes back to the pool, and a loss bigger than it comes out of what's left
				double released = p.margin * closing / before;
				p.margin -= released;
				if (realized + released < 0) p.margin = Math.max(0, p.margin + realized + released);
			}
			boolean flipped = newSize != 0 && Math.signum(newSize) != Math.signum(p.size);
			if (flipped) {
				p.entry = px;
				p.funding = 0;
				p.opened = System.currentTimeMillis();
				if (iso) p.margin = MarginMath.initialMargin(coin, marketMax(coin), leverage(coin), Math.abs(newSize) * px);
				dropOrders(x -> x.coin.equals(coin) && x.wholePosition, "Cancelled");
			}
			p.size = newSize;
		}
		addPool(pool, realized);
		state.realized += realized;
		if (p.size == 0) state.positions.remove(coin);
		return realized;
	}

	/** A resting set of limit orders from start to end, the end's size {@code scaleSkew} times the start's. */
	private OrderResult startScale(OrderRequest r, MarketData.Asset a, double mid) {
		String coin = a.name();
		if (r.side != Side.BUY && r.side != Side.SELL) return OrderResult.fail(coin, "Scale orders are a buy or a sell");
		if (Double.isNaN(mid) || mid <= 0) return OrderResult.fail(coin, "No price for " + coin + " yet");
		if (r.scaleStart <= 0 || r.scaleEnd <= 0) return OrderResult.fail(coin, "Enter a start and an end price");
		if (r.scaleStart == r.scaleEnd) return OrderResult.fail(coin, "Start and end prices must differ");
		boolean isBuy = r.side == Side.BUY;
		int n = r.scaleOrders;
		double total = r.sizeCoin > 0 ? r.sizeCoin : r.usd / ((r.scaleStart + r.scaleEnd) / 2);
		if (!(total > 0)) return OrderResult.fail(coin, "Amount must be positive");
		double weights = 0;
		for (int i = 0; i < n; i++) weights += 1 + (r.scaleSkew - 1) * i / (n - 1);
		// every leg is checked before any is placed: nothing's left half-done
		double[] pxs = new double[n], sizes = new double[n];
		for (int i = 0; i < n; i++) {
			pxs[i] = a.roundPrice(r.scaleStart + (r.scaleEnd - r.scaleStart) * i / (n - 1));
			sizes[i] = dev.stonkcraft.hl.HlFormat.roundSize(total * (1 + (r.scaleSkew - 1) * i / (n - 1)) / weights, a.szDecimals());
			if (sizes[i] <= 0) return OrderResult.fail(coin, "Order too small to split " + n + " ways for " + coin + " lot size");
			boolean crosses = isBuy ? pxs[i] >= mid : pxs[i] <= mid;
			if (crosses && r.tif == OrderRequest.Tif.ALO) return OrderResult.fail(coin, "Post-only order would cross the book at " + Fmt.price(pxs[i]));
		}
		int taken = 0;
		for (int i = 0; i < n; i++) {
			Order o = toOrder(new OrderPlanner.Leg(isBuy, sizes[i], pxs[i], false, 0, "", r.tif, !a.spot() && r.reduceOnly, false), coin, 0, 0);
			boolean crosses = isBuy ? o.limitPx >= mid : o.limitPx <= mid;
			if (crosses) {
				Result res = fill(o, mid * (1 + Math.copySign(PAPER_SLIP, isBuy ? 1 : -1)), false);
				if (res.error != null) archive(o, "Rejected", 0);
				else taken++;
			} else {
				state.orders.add(o);
			}
		}
		return new OrderResult(true, String.format(java.util.Locale.ROOT, "Scale: %d %s orders from %s to %s%s", n, isBuy ? "buy" : "sell",
				Fmt.price(r.scaleStart), Fmt.price(r.scaleEnd), taken > 0 ? " (" + taken + " filled at once)" : ""), coin, 0, 0);
	}

	/** A stop that follows the mark's best: a sell retraces from the high, a buy from the low. */
	private OrderResult startTrailing(OrderRequest r, MarketData.Asset a, double mid) {
		String coin = a.name();
		if (a.spot()) return OrderResult.fail(coin, "Trailing stops are perps-only");
		if (r.trailPct <= 0) return OrderResult.fail(coin, "Enter the retrace, e.g. 5%");
		OrderPlanner.Plan plan;
		try {
			plan = OrderPlanner.plan(r.asMarket(), a, mid, positionSize(coin), slippage(r));
		} catch (OrderPlanner.PlanException e) {
			return OrderResult.fail(coin, e.getMessage());
		}
		double m = quotes.get().mark(coin);
		boolean isBuy = plan.entry().isBuy();
		Order o = toOrder(new OrderPlanner.Leg(isBuy, plan.entry().size(), 0, true, 0, "sl", OrderRequest.Tif.GTC, plan.entry().reduceOnly(), false), coin, 0, 0);
		o.trailPct = r.trailPct;
		o.activationPx = r.activationPx;
		o.trailBest = r.activationPx > 0 ? r.activationPx : m;
		o.triggerPx = o.trailBest * (isBuy ? 1 + r.trailPct / 100 : 1 - r.trailPct / 100);
		o.cloid = r.cloid;
		state.orders.add(o);
		markCloid(o.cloid, OrderStatus.RESTING);
		return new OrderResult(true, String.format(java.util.Locale.ROOT, "Trailing stop: %s %s %s on a %.2f%% retrace", isBuy ? "buy" : "sell", Fmt.size(o.size), Fmt.market(coin), r.trailPct), coin, 0, 0);
	}

	private OrderResult startTwap(OrderRequest r, MarketData.Asset a, double mid) {
		OrderPlanner.Plan plan;
		try {
			plan = OrderPlanner.plan(r.asMarket(), a, mid, positionSize(a.name()), slippage(r));
		} catch (OrderPlanner.PlanException e) {
			return OrderResult.fail(a.name(), e.getMessage());
		}
		if (r.minutes < 5 || r.minutes > OrderRequest.MAX_TWAP_MINUTES) return OrderResult.fail(a.name(), "TWAP must run 5 minutes to 7 days");
		if (plan.entry().size() * mid < TWAP_MIN_USD) return OrderResult.fail(a.name(), "A TWAP's total must be at least " + Fmt.usd(TWAP_MIN_USD));
		Twap t = new Twap();
		t.id = state.nextOid++;
		t.coin = a.name();
		t.isBuy = plan.entry().isBuy();
		t.total = plan.entry().size();
		t.start = System.currentTimeMillis();
		t.minutes = r.minutes;
		t.randomize = r.randomize;
		t.reduceOnly = plan.entry().reduceOnly();
		t.limit = r.twapLimit;
		state.twaps.add(t);
		return new OrderResult(true, String.format("TWAP %s %s %s over %d min", t.isBuy ? "buy" : "sell", Fmt.size(t.total), a.name(), t.minutes),
				a.name(), 0, 0);
	}

	@Override
	public CompletableFuture<OrderResult> cancelTwap(String coin, long twapId) {
		boolean removed = state.twaps.removeIf(t -> t.id == twapId);
		return CompletableFuture.completedFuture(removed ? new OrderResult(true, "Stopped TWAP #" + twapId, coin, 0, 0)
				: OrderResult.fail(coin, "No running TWAP #" + twapId));
	}

	@Override
	public CompletableFuture<OrderResult> scheduleCancel(long timeMs) {
		if (timeMs != 0 && timeMs < System.currentTimeMillis() + 5_000) {
			return CompletableFuture.completedFuture(OrderResult.fail("", "Schedule at least 5 seconds ahead"));
		}
		state.scheduledCancel = timeMs;
		return CompletableFuture.completedFuture(new OrderResult(true, timeMs == 0 ? "Dead man's switch cleared"
				: "Orders cancel in " + Math.round((timeMs - System.currentTimeMillis()) / 1000.0) + "s unless renewed", "", 0, 0));
	}

	@Override
	public long scheduledCancel() {
		return state.scheduledCancel;
	}

	@Override
	public CompletableFuture<OrderResult> adjustMargin(String rawCoin, double usd) {
		return CompletableFuture.completedFuture(moveMargin(rawCoin, usd));
	}

	/** Adds to (usd above 0) or takes from an isolated position's margin. */
	private OrderResult moveMargin(String rawCoin, double usd) {
		MarketData.Asset a = asset(rawCoin);
		String coin = a == null ? rawCoin : a.name();
		Pos p = state.positions.get(coin);
		if (p == null) return OrderResult.fail(coin, "No " + coin + " position");
		if (isCross(coin)) return OrderResult.fail(coin, "Cross positions margin from the shared balance: switch to Isolated to set its own margin");
		if (usd == 0 || !Double.isFinite(usd)) return OrderResult.fail(coin, "Enter an amount");
		if (usd > 0) {
			double free = withdrawable(poolOf(coin));
			if (usd > free + 1e-9) return OrderResult.fail(coin, "Only " + Fmt.usd(free) + " is free to add");
			p.margin += usd;
			return new OrderResult(true, "Added " + Fmt.usd(usd) + " margin to " + Fmt.market(coin), coin, 0, 0);
		}
		double removable = removableMargin(coin, p);
		if (-usd > removable + 1e-9) return OrderResult.fail(coin, "Only " + Fmt.usd(removable) + " of the margin is above what the position needs");
		p.margin += usd;
		return new OrderResult(true, "Took " + Fmt.usd(-usd) + " margin from " + Fmt.market(coin), coin, 0, 0);
	}

	/** Isolated margin above initial margin, with any loss counted against it. */
	private double removableMargin(String coin, Pos p) {
		return Math.max(0, p.margin + Math.min(0, unrealizedPnl(coin)) - initialMargin(coin, p));
	}

	@Override
	public CompletableFuture<OrderResult> setPositionTpSl(String rawCoin, double tpPx, double slPx) {
		MarketData.Asset a = asset(rawCoin);
		String coin = a == null ? rawCoin : a.name();
		Pos p = state.positions.get(coin);
		if (a == null || p == null) return CompletableFuture.completedFuture(OrderResult.fail(coin, "No " + coin + " position"));
		boolean isLong = p.size > 0;
		double ref = mark(coin, p);
		if (tpPx > 0 && (isLong ? tpPx <= ref : tpPx >= ref)) return CompletableFuture.completedFuture(OrderResult.fail(coin, "TP price must be " + (isLong ? "above " : "below ") + Fmt.price(ref)));
		if (slPx > 0 && (isLong ? slPx >= ref : slPx <= ref)) return CompletableFuture.completedFuture(OrderResult.fail(coin, "SL price must be " + (isLong ? "below " : "above ") + Fmt.price(ref)));
		dropOrders(x -> x.coin.equals(coin) && x.wholePosition, "Cancelled");
		for (int i = 0; i < 2; i++) {
			double px = i == 0 ? tpPx : slPx;
			if (px <= 0) continue;
			double rounded = a.roundPrice(px);
			Order o = toOrder(new OrderPlanner.Leg(!isLong, Math.abs(p.size), a.roundPrice(rounded * (isLong ? 1 - OrderPlanner.TPSL_SLIPPAGE : 1 + OrderPlanner.TPSL_SLIPPAGE)),
					true, rounded, i == 0 ? "tp" : "sl", OrderRequest.Tif.GTC, true, false), coin, 0, 0);
			o.wholePosition = true;
			state.orders.add(o);
		}
		String msg = tpPx <= 0 && slPx <= 0 ? "Cleared " + Fmt.market(coin) + "'s TP/SL"
				: Fmt.market(coin) + " TP " + (tpPx > 0 ? Fmt.price(tpPx) : "--") + " / SL " + (slPx > 0 ? Fmt.price(slPx) : "--");
		return CompletableFuture.completedFuture(new OrderResult(true, msg, coin, 0, 0));
	}

	@Override
	public CompletableFuture<OrderResult> cancel(String coin, long oid) {
		int before = state.orders.size();
		dropOrders(o -> o.oid == oid || o.parent == oid, "Cancelled");
		boolean removed = state.orders.size() < before;
		return CompletableFuture.completedFuture(removed ? new OrderResult(true, "Cancelled order #" + oid, coin, 0, 0)
				: OrderResult.fail(coin, "No open order #" + oid));
	}

	@Override
	public CompletableFuture<OrderResult> cancelAll(@Nullable String coin) {
		int before = state.orders.size() + state.twaps.size();
		dropOrders(o -> coin == null || o.coin.equals(coin), "Cancelled");
		state.twaps.removeIf(t -> coin == null || t.coin.equals(coin));
		int n = before - state.orders.size() - state.twaps.size();
		return CompletableFuture.completedFuture(new OrderResult(n > 0, n > 0 ? "Cancelled " + n + " order(s)" : "No open orders", coin == null ? "" : coin, 0, 0));
	}

	@Override
	public CompletableFuture<OrderResult> setLeverage(String rawCoin, int lev, boolean cross) {
		MarketData.Asset a = asset(rawCoin);
		if (a == null) return CompletableFuture.completedFuture(OrderResult.fail(rawCoin, "Unknown market " + rawCoin));
		if (a.spot()) return CompletableFuture.completedFuture(OrderResult.fail(a.name(), "Spot has no leverage"));
		String coin = a.name();
		int max = a.maxLeverage();
		if (lev < 1 || lev > max) return CompletableFuture.completedFuture(OrderResult.fail(coin, coin + " leverage must be 1-" + max + "x"));
		if (MarginMath.maxLeverage(coin, max, positionNotional(coin)) < lev) {
			return CompletableFuture.completedFuture(OrderResult.fail(coin, "A position this large can't exceed " + MarginMath.maxLeverage(coin, max, positionNotional(coin)) + "x"));
		}
		String pool = poolOf(coin);
		Pos p = state.positions.get(coin);
		double oldMargin = p == null ? 0 : p.margin;
		Integer old = state.leverage.put(coin, lev);
		Boolean oldIso = state.isolated.put(coin, !cross);
		boolean ok = true;
		if (p != null) {
			if (cross) {
				p.margin = 0;
			} else {
				// isolated: hold at least initial margin at the new leverage; raising leverage doesn't hand any back
				double needed = initialMargin(coin, p);
				p.margin = Boolean.TRUE.equals(oldIso) ? Math.max(oldMargin, needed) : needed;
				ok = p.margin + unrealizedPnl(coin) > 0;
			}
		}
		if (!ok || crossEquity(pool) < crossInitial(pool) - 1e-9) {
			if (old == null) state.leverage.remove(coin);
			else state.leverage.put(coin, old);
			if (oldIso == null) state.isolated.remove(coin);
			else state.isolated.put(coin, oldIso);
			if (p != null) p.margin = oldMargin;
			return CompletableFuture.completedFuture(OrderResult.fail(coin, "Not enough margin for " + lev + "x" + (cross ? "" : " isolated")));
		}
		return CompletableFuture.completedFuture(new OrderResult(true, coin + " leverage " + lev + "x " + (cross ? "cross" : "isolated"), coin, 0, 0));
	}

	private double positionNotional(String coin) {
		Pos p = state.positions.get(coin);
		return p == null ? 0 : notional(coin, p);
	}

	// ------------------------------------------------------------------ matching

	/** Once a second: rests limit orders, fires triggers, liquidates, samples equity. Returns events for the owner. */
	public List<String> tick(long now) {
		List<String> events = new ArrayList<>(pendingEvents);
		pendingEvents.clear();
		if (!state.dexSplit) {
			splitDexes();
			// no market to place this save's positions yet: leave them alone until there is
			if (!state.dexSplit) return events;
		}
		for (Order o : new ArrayList<>(state.orders)) {
			if (o.parent != 0 || !state.orders.contains(o)) continue;
			double m = mid(o.coin);
			if (Double.isNaN(m)) continue;
			if (o.trigger) {
				// triggers run on the mark, as Hyperliquid's do; what fills them is the book, here the mid
				double tm = quotes.get().mark(o.coin);
				if (Double.isNaN(tm)) tm = m;
				boolean hit;
				if (o.trailPct > 0) {
					if (o.activationPx > 0) {
						if (o.isBuy ? tm > o.activationPx : tm < o.activationPx) continue;
						o.activationPx = 0;
						o.trailBest = tm;
					}
					o.trailBest = o.isBuy ? Math.min(o.trailBest, tm) : Math.max(o.trailBest, tm);
					o.triggerPx = o.trailBest * (o.isBuy ? 1 + o.trailPct / 100 : 1 - o.trailPct / 100);
					hit = o.isBuy ? tm >= o.triggerPx : tm <= o.triggerPx;
				} else {
					hit = o.tpsl.equals("sl") ? (o.isBuy ? tm >= o.triggerPx : tm <= o.triggerPx)
							: (o.isBuy ? tm <= o.triggerPx : tm >= o.triggerPx);
				}
				if (!hit) continue;
				if (o.triggerLimit) {
					String kind = o.label();
					o.trigger = false;
					o.triggerLimit = false;
					o.tpsl = "";
					boolean marketable = o.isBuy ? m <= o.limitPx : m >= o.limitPx;
					if (!marketable) {
						events.add("\u00a7a" + kind + " triggered: resting at " + Fmt.price(o.limitPx));
						continue;
					}
				}
				String label = o.label();
				state.orders.remove(o);
				Result r = fill(o, m * (1 + Math.copySign(PAPER_SLIP, o.isBuy ? 1 : -1)), false);
				if (r.error != null) archive(o, "Rejected", 0);
				events.add((r.error == null ? "§a" + label + " triggered: " + r.message : "§c" + label + " on " + o.coin + " failed: " + r.error));
			} else {
				boolean cross = o.isBuy ? m <= o.limitPx : m >= o.limitPx;
				if (!cross) continue;
				state.orders.remove(o);
				Result r = fill(o, o.limitPx, true);
				if (r.error != null) archive(o, "Rejected", 0);
				events.add(r.error == null ? "§aLimit filled: " + r.message : "§cLimit on " + o.coin + " cancelled: " + r.error);
			}
		}
		tickTwaps(now, events);
		if (state.scheduledCancel > 0 && now >= state.scheduledCancel) {
			int n = state.orders.size() + state.twaps.size();
			dropOrders(o -> true, "Dead man's switch");
			state.twaps.clear();
			state.scheduledCancel = 0;
			events.add("\u00a7eDead man's switch fired: cancelled " + n + " order(s)");
		}
		settleFunding(now, events);
		liquidate(now, events);
		if (state.equityHistory.isEmpty() || now - (long) state.equityHistory.get(state.equityHistory.size() - 1)[0] >= 15_000) {
			state.equityHistory.add(new double[]{now, equity()});
			while (state.equityHistory.size() > MAX_EQUITY_POINTS) state.equityHistory.remove(0);
		}
		if (state.equityLong.isEmpty() || now - (long) state.equityLong.get(state.equityLong.size() - 1)[0] >= EQUITY_LONG_EVERY_MS) {
			state.equityLong.add(new double[]{now, equity(), state.netDeposits});
			while (state.equityLong.size() > MAX_EQUITY_LONG) state.equityLong.remove(0);
		}
		return events;
	}

	// ------------------------------------------------------------------ liquidation

	/**
	 * §6: isolated positions on their own margin, then each pool's cross positions on its account value. At 100% margin
	 * ratio, market orders go to the book (a fifth at a time over $100k, every 30 s), and the player keeps what's left.
	 * Under 2/3 of maintenance margin the backstop takes over and the margin is gone. Spot is only at risk where it's
	 * collateral: Unified's one balance, and Portfolio Margin.
	 */
	private void liquidate(long now, List<String> events) {
		if (state.positions.isEmpty() && !(state.portfolioMargin && state.balance < 0)) return;
		for (String coin : new ArrayList<>(state.positions.keySet())) {
			if (isCross(coin)) continue;
			Pos p = state.positions.get(coin);
			double value = p.margin + unrealizedPnl(coin);
			double mm = maintenance(coin, p);
			if (value > mm) continue;
			if (value < mm * MarginMath.BACKSTOP_SHARE) {
				backstopIsolated(coin, p);
				events.add("\u00a7cLiquidated: " + Fmt.market(coin) + " was taken over by the backstop; its margin is gone");
			} else if (now >= state.liqCooldown.getOrDefault(coin, 0L)) {
				liquidateStep(coin, p, now, coin, events);
			}
		}
		for (String pool : pools()) {
			if (pool.isEmpty() && state.portfolioMargin) {
				liquidatePortfolio(now, events);
				continue;
			}
			double mm = crossMaintenance(pool);
			if (mm <= 0) continue;
			double eq = crossEquity(pool);
			if (eq > mm) continue;
			if (eq < mm * MarginMath.BACKSTOP_SHARE) {
				backstopCross(pool);
				events.add("\u00a7cLiquidated: the backstop took " + (pool.isEmpty() ? "your cross positions" : Fmt.pool(pool) + "'s cross positions") + " and their margin");
				continue;
			}
			if (now < state.liqCooldown.getOrDefault("pool:" + pool, 0L)) continue;
			// the biggest maintenance margin first, stopping as soon as the account's healthy again
			List<String> coins = new ArrayList<>();
			for (String coin : state.positions.keySet()) if (poolOf(coin).equals(pool) && isCross(coin)) coins.add(coin);
			coins.sort(java.util.Comparator.comparingDouble((String c) -> -maintenance(c, state.positions.get(c))));
			for (String coin : coins) {
				liquidateStep(coin, state.positions.get(coin), now, "pool:" + pool, events);
				double left = crossMaintenance(pool);
				if (left <= 0 || crossEquity(pool) > left) break;
			}
		}
	}

	/** One market-order step: all of it, or a fifth when it's over $100k and the rest waits out a cooldown. */
	private void liquidateStep(String coin, Pos p, long now, String cooldownKey, List<String> events) {
		double notional = notional(coin, p);
		boolean partial = notional > MarginMath.PARTIAL_OVER_USD;
		double px = mark(coin, p);
		double size = p.size;
		if (partial) {
			MarketData.Asset a = asset(coin);
			double step = Math.abs(p.size) * MarginMath.PARTIAL_STEP;
			size = Math.copySign(a == null ? step : dev.stonkcraft.hl.HlFormat.roundSize(step, a.szDecimals()), p.size);
			if (size == 0) size = p.size;
			state.liqCooldown.put(cooldownKey, now + MarginMath.PARTIAL_COOLDOWN_MS);
		}
		double fee = Math.abs(size) * px * feeRate(false, false);
		double realized = applyPerpFill(coin, -size, px, feeRate(false, false));
		recordLiquidation(now, coin, -size, px, realized - fee, fee, "Market Order Liquidation");
		if (partial) {
			events.add("\u00a7eMargin call: " + Fmt.size(Math.abs(size)) + " " + Fmt.market(coin) + " sent to the book at " + Fmt.price(px));
		} else {
			events.add("\u00a7cLiquidated: " + Fmt.market(coin) + " closed at " + Fmt.price(px));
		}
	}

	private void recordLiquidation(long now, String coin, double signed, double px, double pnl, double fee, String kind) {
		state.fills.addFirst(new Fill(now, coin, signed, px, pnl, fee, kind));
		while (state.fills.size() > MAX_FILLS) state.fills.removeLast();
		state.lastFill.put(coin, now);
		if (positionSize(coin) == 0) dropOrders(x -> x.coin.equals(coin) && x.reduceOnly && x.parent == 0, "Cancelled");
	}

	private void backstopIsolated(String coin, Pos p) {
		long now = System.currentTimeMillis();
		String pool = poolOf(coin);
		double margin = p.margin, size = p.size, px = mark(coin, p);
		double realized = applyPerpFill(coin, -size, px, 0);
		// everything the position held is gone, and only that: its loss never reaches the rest of the pool
		double settle = -margin - realized;
		addPool(pool, settle);
		state.realized += settle;
		recordLiquidation(now, coin, -size, px, -margin, 0, "Backstop Liquidation");
	}

	private void backstopCross(String pool) {
		long now = System.currentTimeMillis();
		for (String coin : new ArrayList<>(state.positions.keySet())) {
			if (!poolOf(coin).equals(pool) || !isCross(coin)) continue;
			Pos p = state.positions.get(coin);
			double size = p.size, px = mark(coin, p);
			double realized = applyPerpFill(coin, -size, px, 0);
			recordLiquidation(now, coin, -size, px, realized, 0, "Backstop Liquidation");
		}
		// the backstop keeps the cross margin; isolated positions' margin stays theirs
		double held = 0;
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) if (poolOf(e.getKey()).equals(pool)) held += e.getValue().margin;
		double forfeited = held - poolUsdc(pool);
		addPool(pool, forfeited);
		state.realized += forfeited;
	}

	/** Portfolio Margin has no market phase: above 95% the backstop takes 20% steps, selling collateral once positions run out. */
	private void liquidatePortfolio(long now, List<String> events) {
		double mm = crossMaintenance("");
		double eq = crossEquity("");
		if (mm > 0 && eq * PM_LIQUIDATES_AT <= mm && now >= state.liqCooldown.getOrDefault("pool:", 0L)) {
			state.liqCooldown.put("pool:", now + MarginMath.PARTIAL_COOLDOWN_MS);
			for (String coin : new ArrayList<>(state.positions.keySet())) {
				if (!poolOf(coin).isEmpty() || !isCross(coin)) continue;
				Pos p = state.positions.get(coin);
				MarketData.Asset a = asset(coin);
				double step = Math.abs(p.size) * MarginMath.PARTIAL_STEP;
				double size = Math.copySign(a == null ? step : dev.stonkcraft.hl.HlFormat.roundSize(step, a.szDecimals()), p.size);
				if (size == 0 || notional(coin, p) <= MarginMath.PARTIAL_OVER_USD) size = p.size;
				double px = mark(coin, p);
				double realized = applyPerpFill(coin, -size, px, 0);
				recordLiquidation(now, coin, -size, px, realized, 0, "Backstop Liquidation");
			}
			events.add(state.positions.isEmpty() ? "\u00a7cLiquidated: the backstop closed your positions" : "\u00a7eMargin call: the backstop took a fifth of your positions");
		}
		// flat with USDC still borrowed and the collateral no longer covering it: sell what's held to repay it
		if (state.balance < 0 && state.positions.isEmpty() && state.balance + collateral() < 0) {
			for (String market : new ArrayList<>(state.spot.keySet())) {
				if (state.balance >= 0) break;
				double m = mid(market);
				if (Double.isNaN(m) || m <= 0) continue;
				double held = state.spot.get(market);
				double sell = Math.min(held, -state.balance / m);
				state.balance += sell * m;
				debitHolding(market, held, sell);
			}
			if (state.balance < 0) {
				state.realized -= state.balance;
				state.balance = 0;
			}
			events.add("\u00a7cLiquidated: collateral sold to repay borrowed USD");
		}
	}

	private void debitHolding(String market, double held, double sell) {
		double left = held - sell;
		if (left <= 1e-12) {
			state.spot.remove(market);
			state.spotCost.remove(market);
		} else {
			state.spot.put(market, left);
			state.spotCost.put(market, state.spotCost.getOrDefault(market, 0.0) * left / held);
		}
	}

	private void tickTwaps(long now, List<String> events) {
		for (Twap t : new ArrayList<>(state.twaps)) {
			if (now - t.lastSlice < 30_000 && t.lastSlice != 0) continue;
			MarketData.Asset a = asset(t.coin);
			double m = mid(t.coin);
			if (a == null || Double.isNaN(m)) continue;
			double remaining = t.total - t.filled;
			// where the schedule says it should be by now; a slice catches up to that, at most 3x a normal one
			long slices = Math.max(1, (long) Math.ceil(t.minutes * 60_000L / 30_000.0));
			long due = Math.min(slices, (now - t.start) / 30_000L + 1);
			double normal = t.total / slices;
			double slice = t.total * due / slices - t.filled;
			if (t.randomize && due < slices) slice *= 0.8 + 0.4 * ((now / 1000 + t.id) % 101) / 100.0;
			slice = Math.min(slice, 3 * normal);
			slice = Math.min(remaining, dev.stonkcraft.hl.HlFormat.roundSize(slice, a.szDecimals()));
			if (due >= slices && remaining <= 3 * normal) slice = remaining;
			t.lastSlice = now;
			// outside its price limit it waits, and catches up when the price comes back
			if (t.limit > 0 && (t.isBuy ? m > t.limit : m < t.limit)) slice = 0;
			if (slice > 0) {
				Order o = new Order();
				o.slice = true;
				o.oid = t.id;
				o.coin = t.coin;
				o.isBuy = t.isBuy;
				o.size = slice;
				o.reduceOnly = t.reduceOnly;
				double px = m * (1 + Math.copySign(PAPER_SLIP, t.isBuy ? 1 : -1));
				Result r = fill(o, px, false);
				if (r.error != null) {
					state.twaps.remove(t);
					events.add("\u00a7cTWAP on " + t.coin + " stopped: " + r.error);
					continue;
				}
				t.filled += slice;
				t.notional += slice * px;
			}
			if (t.total - t.filled <= 1e-12 || now >= t.end() + 30_000) {
				state.twaps.remove(t);
				state.orderHistory.addFirst(new OrderRecord(now, t.id, t.coin, t.isBuy, "TWAP", t.total, t.filled, t.filled > 0 ? t.notional / t.filled : 0, t.reduceOnly, t.total - t.filled <= 1e-9 ? "Filled" : "Expired"));
				while (state.orderHistory.size() > MAX_FILLS) state.orderHistory.removeLast();
				events.add(String.format("\u00a7aTWAP done: %s %s %s, avg %s", t.isBuy ? "bought" : "sold", Fmt.size(t.filled), t.coin,
						Fmt.price(t.filled > 0 ? t.notional / t.filled : 0)));
			}
		}
	}

	/** Hourly funding on the oracle price at the live rate, capped at 4% an hour: longs pay positive funding, shorts receive it. */
	private void settleFunding(long now, List<String> events) {
		long hour = now / 3_600_000L;
		if (state.lastFundingHour == 0) {
			state.lastFundingHour = hour;
			return;
		}
		if (hour <= state.lastFundingHour) return;
		state.lastFundingHour = hour;
		double total = 0;
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) {
			double rate = quotes.get().funding(e.getKey());
			if (rate == 0 || Double.isNaN(rate)) continue;
			rate = Math.max(-FUNDING_CAP, Math.min(FUNDING_CAP, rate));
			Pos p = e.getValue();
			double oracle = quotes.get().oracle(e.getKey());
			double pay = -p.size * (Double.isNaN(oracle) ? mark(e.getKey(), p) : oracle) * rate;
			addPool(poolOf(e.getKey()), pay);
			if (!isCross(e.getKey())) p.margin = Math.max(0, p.margin + pay);
			p.funding += pay;
			state.realized += pay;
			total += pay;
			state.funding.addFirst(new FundingEvent(now, e.getKey(), pay, rate, p.size));
			while (state.funding.size() > 60) state.funding.removeLast();
		}
		if (total != 0) events.add("\u00a77Funding: " + Fmt.usdSigned(total));
	}

	// ------------------------------------------------------------------ snapshot

	@Override
	public JsonObject snapshot() {
		JsonObject o = new JsonObject();
		o.addProperty("mode", mode());
		double eq = equity();
		double upnl = 0;
		for (String coin : state.positions.keySet()) upnl += unrealizedPnl(coin);
		o.addProperty("equity", eq);
		o.addProperty("upnl", upnl);
		o.addProperty("realized", state.realized);
		o.addProperty("fees", state.fees);
		o.addProperty("margin", marginUsed());
		o.addProperty("start", state.netDeposits);
		o.addProperty("withdrawable", withdrawable());
		o.addProperty("cash", state.balance);
		o.addProperty("spotUsdc", state.unified ? spotSpendable() : state.spotUsdc);
		o.addProperty("unified", state.unified);
		o.addProperty("portfolioMargin", state.portfolioMargin);
		o.addProperty("perpEquity", perpEquity());
		o.addProperty("spotEquity", spotEquity());
		o.addProperty("available", available(""));
		// the sums the order form needs for its estimates, and the account summary
		double crossEq = crossEquity("");
		o.addProperty("crossEquity", crossEq);
		o.addProperty("crossMaint", crossMaintenance(""));
		o.addProperty("marginRatio", marginRatio());
		o.addProperty("maint", maintenanceTotal());
		o.addProperty("accountLeverage", crossEq <= 0 ? 0 : crossValue("") / crossEq);
		JsonObject dexes = new JsonObject();
		// a Manual account can always fund a dex, whether or not it's used one yet
		if (!state.unified) {
			for (String dex : MarketData.EXTRA_DEXES) {
				JsonObject d = new JsonObject();
				d.addProperty("usdc", poolUsdc(dex));
				d.addProperty("available", available(dex));
				d.addProperty("withdrawable", withdrawable(dex));
				d.addProperty("crossEquity", crossEquity(dex));
				d.addProperty("crossMaint", crossMaintenance(dex));
				dexes.add(dex, d);
			}
		}
		o.add("dexes", dexes);
		long nowMs = System.currentTimeMillis();
		double vol = volume14d(nowMs);
		int tier = FeeSchedule.tier(vol);
		double next = FeeSchedule.nextTierVolume(tier);
		JsonObject fees = new JsonObject();
		fees.addProperty("tier", tier);
		fees.addProperty("volume", vol);
		fees.addProperty("next", Double.isNaN(next) ? 0 : next);
		fees.addProperty("perpTaker", feeRate(false, false));
		fees.addProperty("perpMaker", feeRate(false, true));
		fees.addProperty("spotTaker", feeRate(true, false));
		fees.addProperty("spotMaker", feeRate(true, true));
		o.add("feeInfo", fees);
		JsonArray positions = new JsonArray();
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) {
			Pos p = e.getValue();
			String coin = e.getKey();
			double mark = mark(coin, p);
			double pnl = p.size * (mark - p.entry);
			JsonObject row = Fmt.position(coin, p.size, p.entry, mark, pnl, liquidationPrice(coin));
			boolean cross = isCross(coin);
			row.addProperty("lev", leverage(coin));
			row.addProperty("cross", cross);
			row.addProperty("value", Math.abs(p.size) * mark);
			// isolated margin shows what's there, with PnL, as Hyperliquid's column does
			row.addProperty("margin", cross ? initialMargin(coin, p) : p.margin + pnl);
			row.addProperty("isoMargin", p.margin);
			if (!cross) row.addProperty("removable", removableMargin(coin, p));
			double entryMargin = Math.abs(p.size) * p.entry / Math.max(1, leverage(coin));
			row.addProperty("roe", entryMargin <= 0 ? 0 : pnl / entryMargin * 100);
			row.addProperty("funding", p.funding);
			row.addProperty("maint", maintenance(coin, p));
			row.addProperty("maxLev", marketMax(coin));
			for (Order ord : state.orders) {
				if (!ord.coin.equals(coin) || !ord.wholePosition) continue;
				row.addProperty(ord.tpsl.equals("tp") ? "tp" : "sl", ord.triggerPx);
			}
			positions.add(row);
		}
		o.add("positions", positions);
		JsonArray balances = new JsonArray();
		for (Map.Entry<String, Double> e : state.spot.entrySet()) {
			double m = mid(e.getKey());
			MarketData.Asset a = asset(e.getKey());
			balances.add(Fmt.balance(a == null ? e.getKey() : a.base(), e.getKey(), e.getValue(),
					Double.isNaN(m) ? 0 : e.getValue() * m, state.spotCost.getOrDefault(e.getKey(), 0.0)));
		}
		o.add("balances", balances);
		JsonArray orders = new JsonArray();
		for (Order ord : state.orders) {
			JsonObject row = Fmt.order(ord.oid, ord.coin, ord.isBuy, ord.size, ord.label(), ord.trigger ? ord.triggerPx : ord.limitPx,
					ord.reduceOnly, ord.parent != 0, ord.time);
			row.addProperty("orig", ord.origSize > 0 ? ord.origSize : ord.size);
			// a trigger's a condition, as the Open Orders table shows it
			if (ord.trigger) row.addProperty("trigger", (ord.isBuy == ord.tpsl.equals("sl") ? "≥ " : "≤ ") + Fmt.price(ord.triggerPx));
			if (ord.triggerLimit) row.addProperty("limit", ord.limitPx);
			orders.add(row);
		}
		o.add("orders", orders);
		JsonArray twaps = new JsonArray();
		for (Twap t : state.twaps) twaps.add(Fmt.twap(t.id, t.coin, t.isBuy, t.total, t.filled, t.start, t.minutes));
		o.add("twaps", twaps);
		o.addProperty("scheduledCancel", state.scheduledCancel);
		JsonArray fund = new JsonArray();
		for (FundingEvent f : state.funding) {
			JsonObject row = Fmt.funding(f.time(), f.coin(), f.usdc(), f.rate());
			row.addProperty("size", f.size());
			fund.add(row);
		}
		o.add("funding", fund);
		JsonObject lev = new JsonObject();
		for (String coin : state.leverage.keySet()) lev.add(coin, Fmt.leverage(leverage(coin), isCross(coin)));
		o.add("leverage", lev);
		o.addProperty("defaultLev", (int) Math.min(HyperConfig.get().paperDefaultLeverage, HyperConfig.get().paperMaxLeverage));
		o.add("fills", fillRows(SNAPSHOT_ROWS));
		o.add("orderHistory", orderRows(SNAPSHOT_ROWS));
		o.addProperty("drawdown", maxDrawdown());
		JsonArray hist = new JsonArray();
		JsonArray histT = new JsonArray();
		for (double[] pt : state.equityHistory) {
			hist.add(pt[1]);
			histT.add((long) pt[0]);
		}
		hist.add(eq);
		histT.add(System.currentTimeMillis());
		o.add("equityHistory", hist);
		o.add("equityHistoryT", histT);
		return o;
	}

	private double maintenanceTotal() {
		double m = 0;
		for (Map.Entry<String, Pos> e : state.positions.entrySet()) m += maintenance(e.getKey(), e.getValue());
		return m;
	}

	private JsonArray fillRows(int limit) {
		JsonArray rows = new JsonArray();
		int n = 0;
		for (Fill f : state.fills) {
			if (n++ >= limit) break;
			JsonObject row = Fmt.fill(f.time(), f.coin(), f.size(), f.price(), f.pnl());
			row.addProperty("fee", f.fee());
			row.addProperty("value", Math.abs(f.size()) * f.price());
			if (f.kind() != null && !f.kind().isEmpty()) row.addProperty("kind", f.kind());
			rows.add(row);
		}
		return rows;
	}

	private JsonArray orderRows(int limit) {
		JsonArray rows = new JsonArray();
		int n = 0;
		for (OrderRecord r : state.orderHistory) {
			if (n++ >= limit) break;
			JsonObject row = new JsonObject();
			row.addProperty("t", r.time());
			row.addProperty("oid", r.oid());
			row.addProperty("coin", r.coin());
			row.addProperty("buy", r.isBuy());
			row.addProperty("type", r.type());
			row.addProperty("size", r.size());
			row.addProperty("filled", r.filled());
			row.addProperty("px", r.px());
			row.addProperty("reduceOnly", r.reduceOnly());
			row.addProperty("status", r.status());
			rows.add(row);
		}
		return rows;
	}

	/** The largest fall in PnL from a high, on the half-hourly record: deposits and withdrawals don't count. */
	public double maxDrawdown() {
		double peak = Double.NEGATIVE_INFINITY, worst = 0;
		for (double[] pt : state.equityLong) {
			double pnl = pt[1] - pt[2];
			peak = Math.max(peak, pnl);
			worst = Math.max(worst, peak - pnl);
		}
		if (!state.equityLong.isEmpty()) worst = Math.max(worst, peak - (equity() - state.netDeposits));
		return worst;
	}

	/** What the terminal asks for on demand: the full Trade and Order History, and the long equity record. */
	@Override
	public JsonObject history() {
		JsonObject o = new JsonObject();
		o.add("fills", fillRows(MAX_FILLS));
		o.add("orderHistory", orderRows(MAX_FILLS));
		JsonArray t = new JsonArray(), eq = new JsonArray(), dep = new JsonArray();
		int step = Math.max(1, state.equityLong.size() / 240);
		for (int i = 0; i < state.equityLong.size(); i += step) {
			double[] pt = state.equityLong.get(i);
			t.add((long) pt[0]);
			eq.add(pt[1]);
			dep.add(pt[2]);
		}
		t.add(System.currentTimeMillis());
		eq.add(equity());
		dep.add(state.netDeposits);
		o.add("longT", t);
		o.add("longEquity", eq);
		o.add("longDeposits", dep);
		return o;
	}
}
