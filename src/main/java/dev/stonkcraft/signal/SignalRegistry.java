package dev.stonkcraft.signal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.game.Advancements;
import dev.stonkcraft.game.MarketClock;
import dev.stonkcraft.market.MarketData;
import dev.stonkcraft.registry.ModComponents;
import dev.stonkcraft.registry.ModItems;
import dev.stonkcraft.trading.AccessPolicy;
import dev.stonkcraft.trading.Account;
import dev.stonkcraft.trading.AccountMetric;
import dev.stonkcraft.trading.AccountRegistry;
import dev.stonkcraft.trading.AccountStats;
import dev.stonkcraft.trading.Fmt;
import dev.stonkcraft.trading.GuardedAccount;
import dev.stonkcraft.trading.OrderRequest;
import dev.stonkcraft.trading.Side;
import dev.stonkcraft.trading.TradingAccount;
import dev.stonkcraft.trading.WatchAccount;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Every signal and Copy Desk on the server, saved in the world (stonkcraft_signals.json). Signals are evaluated once
 * a second whether or not their Signal Mixer is loaded; desks rebalance on their own interval. Mixer and desk
 * blocks are just handles onto these.
 */
public final class SignalRegistry implements SignalEngine.Env {
	private static final Gson GSON = new GsonBuilder().create();

	private static final class Saved {
		List<SignalNode> nodes = new ArrayList<>();
		List<CopyDesk> desks = new ArrayList<>();
		List<ScoreTap> scores = new ArrayList<>();
	}

	/** A signal written to a scoreboard objective every second, as {@code round(value × scale)}. */
	private static final class ScoreTap {
		String signal = "";
		String holder = "";
		String objective = "";
		double scale = 1;
	}

	private final MinecraftServer server;
	private final AccountRegistry accounts;
	private final Map<UUID, SignalNode> nodes = new LinkedHashMap<>();
	private final Map<UUID, CopyDesk> desks = new LinkedHashMap<>();
	private final List<ScoreTap> scores = new ArrayList<>();
	private Map<UUID, SignalValue> values = new HashMap<>();
	private long lastSave;
	private boolean dirty;

	public SignalRegistry(MinecraftServer server, AccountRegistry accounts) {
		this.server = server;
		this.accounts = accounts;
		load();
	}

	private Path file() {
		return server.getWorldPath(LevelResource.ROOT).resolve("stonkcraft_signals.json");
	}

	private void load() {
		try {
			if (!Files.exists(file())) return;
			Saved s = GSON.fromJson(Files.readString(file()), Saved.class);
			if (s == null) return;
			if (s.nodes != null) for (SignalNode n : s.nodes) {
				n.upgrade();
				nodes.put(n.id, n);
			}
			if (s.desks != null) for (CopyDesk d : s.desks) {
				d.upgrade();
				desks.put(d.id, d);
			}
			if (s.scores != null) scores.addAll(s.scores);
		} catch (Exception e) {
			Stonkcraft.LOG.error("Failed to load signals", e);
		}
	}

	public void save() {
		try {
			Saved s = new Saved();
			s.nodes = new ArrayList<>(nodes.values());
			s.desks = new ArrayList<>(desks.values());
			s.scores = new ArrayList<>(scores);
			Files.writeString(file(), GSON.toJson(s));
			dirty = false;
		} catch (Exception e) {
			Stonkcraft.LOG.error("Failed to save signals", e);
		}
	}

	public void markDirty() {
		dirty = true;
	}

	// ------------------------------------------------------------------ nodes and desks

	@Override
	@Nullable
	public SignalNode node(UUID id) {
		return nodes.get(id);
	}

	@Nullable
	public CopyDesk desk(@Nullable UUID id) {
		return id == null ? null : desks.get(id);
	}

	public SignalNode createNode(@Nullable UUID owner, String ownerName, BlockPos pos, String dim) {
		SignalNode n = new SignalNode();
		n.owner = owner;
		n.ownerName = ownerName;
		n.pos = pos.asLong();
		n.dim = dim;
		nodes.put(n.id, n);
		dirty = true;
		return n;
	}

	public CopyDesk createDesk(@Nullable UUID owner, String ownerName, BlockPos pos, String dim) {
		CopyDesk d = new CopyDesk();
		d.owner = owner;
		d.ownerName = ownerName;
		d.pos = pos.asLong();
		d.dim = dim;
		desks.put(d.id, d);
		dirty = true;
		return d;
	}

	public void removeNode(UUID id) {
		if (nodes.remove(id) != null) dirty = true;
	}

	public void removeDesk(UUID id) {
		if (desks.remove(id) != null) dirty = true;
	}

	public SignalValue value(@Nullable UUID id) {
		SignalValue v = id == null ? null : values.get(id);
		return v == null ? SignalValue.NONE : v;
	}

	public List<SignalNode> ownedNodes(UUID owner) {
		List<SignalNode> out = new ArrayList<>();
		for (SignalNode n : nodes.values()) if (owner.equals(n.owner)) out.add(n);
		return out;
	}

	public List<CopyDesk> ownedDesks(UUID owner) {
		List<CopyDesk> out = new ArrayList<>();
		for (CopyDesk d : desks.values()) if (owner.equals(d.owner)) out.add(d);
		return out;
	}

	/** Copy Desks driving an account. */
	public List<CopyDesk> desksFor(UUID accountId) {
		List<CopyDesk> out = new ArrayList<>();
		for (CopyDesk d : desks.values()) if (d.running && d.follower != null && accountId.toString().equals(d.follower.id)) out.add(d);
		return out;
	}

	/** An input from a card: a Trade or Watch Card (reading needs no more), or a Signal Card. Null if the stack is neither. */
	@Nullable
	public static SignalNode.Input inputFrom(ItemStack card) {
		if (card.is(ModItems.SIGNAL_CARD) && card.has(ModComponents.SIGNAL)) {
			ModComponents.SignalRef ref = card.get(ModComponents.SIGNAL);
			SignalNode.Input in = new SignalNode.Input();
			in.kind = SignalNode.Input.SIGNAL;
			in.id = ref.id();
			in.name = ref.name();
			return in;
		}
		if (dev.stonkcraft.item.AccountCardItem.isCard(card)) {
			ModComponents.AccountRef ref = card.get(ModComponents.ACCOUNT);
			SignalNode.Input in = new SignalNode.Input();
			in.kind = SignalNode.Input.ACCOUNT;
			in.id = ref.id();
			in.cardId = ref.cardId();
			in.level = ref.level();
			in.name = ref.name();
			return in;
		}
		return null;
	}

	/** Why this input can't feed {@code into}, or null if it can. */
	@Nullable
	public String checkInput(SignalNode into, SignalNode.Input in) {
		if (in.account()) {
			Account a = accounts.get(in.uuid());
			if (a == null) return "That card's account doesn't exist";
			String err = AccessPolicy.checkCard(a, ref(in), false);
			return err;
		}
		UUID id = in.uuid();
		SignalNode src = id == null ? null : nodes.get(id);
		if (src == null) return "That signal is gone (its mixer was broken)";
		if (src.id.equals(into.id) || SignalEngine.dependsOn(src, into.id, nodes::get)) return "That would make a loop";
		return null;
	}

	private static ModComponents.AccountRef ref(SignalNode.Input in) {
		return new ModComponents.AccountRef(in.id, in.name, in.cardId, in.level);
	}

	// ------------------------------------------------------------------ Env

	@Nullable
	private TradingAccount readable(SignalNode.Input in) {
		Account a = accounts.get(in.uuid());
		if (a == null || AccessPolicy.checkCard(a, ref(in), false) != null) return null;
		accounts.noteUse(a.id);
		return accounts.trading(a.id);
	}

	static SignalValue portfolio(@Nullable TradingAccount t) {
		if (t == null) return SignalValue.NONE;
		double eq = t.equity();
		if (!(eq > 0)) return SignalValue.NONE;
		Map<String, Double> w = new LinkedHashMap<>();
		t.perpPositions().forEach((c, p) -> {
			double x = p[0] * p[1] / eq;
			if (Double.isFinite(x) && x != 0) w.put(c, x);
		});
		return new SignalValue.Portfolio(w, eq);
	}

	@Override
	public SignalValue account(SignalNode.Input in) {
		return portfolio(readable(in));
	}

	@Override
	public double metric(SignalNode.Input in, String metric, String market) {
		TradingAccount t = readable(in);
		if (t == null) return Double.NaN;
		return new AccountStats(t).value(AccountMetric.parse(metric), market);
	}

	@Override
	public double position(SignalNode.Input in, String market) {
		TradingAccount t = readable(in);
		double mid = mid(market);
		return t == null || Double.isNaN(mid) ? Double.NaN : t.positionSize(market) * mid;
	}

	@Override
	public double positionPnl(SignalNode.Input in, String market) {
		TradingAccount t = readable(in);
		return t == null ? Double.NaN : t.unrealizedPnl(market);
	}

	@Override
	public int openOrders(SignalNode.Input in, String market) {
		TradingAccount t = readable(in);
		return t == null ? -1 : t.openOrders(market.isEmpty() ? null : market);
	}

	@Override
	public long lastFill(SignalNode.Input in, String market) {
		TradingAccount t = readable(in);
		return t == null ? -1 : t.lastFillTime(market);
	}

	@Override
	public double spreadBps(String market) {
		MarketData md = Stonkcraft.market();
		MarketData.Book b = md == null ? null : md.book(market);
		return b == null ? Double.NaN : b.spreadBps();
	}

	@Override
	public double bookTilt(String market) {
		MarketData md = Stonkcraft.market();
		MarketData.Book b = md == null ? null : md.book(market);
		return b == null ? Double.NaN : b.bidShare(10);
	}

	@Override
	public double deskTracking(String deskId) {
		CopyDesk d;
		try {
			d = desks.get(UUID.fromString(deskId));
		} catch (IllegalArgumentException e) {
			return Double.NaN;
		}
		return d == null || !d.running ? Double.NaN : d.tracking;
	}

	/** An executor's latest order, for its tap's ORDER op; shows up at once rather than on the next tick. */
	public void push(@Nullable UUID nodeId, SignalValue v) {
		SignalNode n = nodeId == null ? null : nodes.get(nodeId);
		if (n == null) return;
		n.pushed = v;
		n.last = v;
		values.put(n.id, v);
	}

	@Override
	public SignalValue address(String address) {
		WatchAccount w = accounts.mirror(address);
		return portfolio(w);
	}

	@Override
	@Nullable
	public String resolve(String market) {
		MarketData md = Stonkcraft.market();
		return md == null ? null : md.resolve(market);
	}

	@Override
	public double mid(String market) {
		MarketData md = Stonkcraft.market();
		return md == null ? Double.NaN : md.mid(market);
	}

	@Override
	public double changePct(String market, long windowMs) {
		MarketData md = Stonkcraft.market();
		MarketData.Ticker t = md == null ? null : md.ticker(market);
		return t == null ? Double.NaN : t.changePct(windowMs);
	}

	@Override
	public double fundingApr(String market) {
		MarketData md = Stonkcraft.market();
		MarketData.Ticker t = md == null ? null : md.ticker(market);
		return t == null || t.asset.spot() ? Double.NaN : t.funding * 24 * 365 * 100;
	}

	@Override
	public boolean marketOpen(long nowMs) {
		return MarketClock.open(nowMs);
	}

	@Override
	public long now() {
		return MarketClock.now();
	}

	// ------------------------------------------------------------------ ticking

	/**
	 * Sends a signal to a scoreboard objective each second; the score holder is the signal's name as one word
	 * ("Tour_BTC_price"), which is returned. Replaces an earlier tap of the same signal into the same objective.
	 */
	public String scoreTap(String signalId, String name, String objective, double scale) {
		scores.removeIf(t -> t.signal.equals(signalId) && t.objective.equals(objective));
		ScoreTap t = new ScoreTap();
		t.signal = signalId;
		t.holder = name.replaceAll("[^A-Za-z0-9_.+-]+", "_").replaceAll("^_+|_+$", "");
		if (t.holder.isEmpty()) t.holder = "signal_" + signalId.substring(0, 6);
		if (t.holder.length() > 40) t.holder = t.holder.substring(0, 40);
		t.objective = objective;
		t.scale = scale;
		scores.add(t);
		dirty = true;
		return t.holder;
	}

	public int clearScoreTaps(String signalId) {
		int before = scores.size();
		scores.removeIf(t -> t.signal.equals(signalId));
		if (scores.size() != before) dirty = true;
		return before - scores.size();
	}

	private void writeScores() {
		if (scores.isEmpty()) return;
		net.minecraft.world.scores.Scoreboard board = server.getScoreboard();
		for (ScoreTap t : scores) {
			net.minecraft.world.scores.Objective obj = board.getObjective(t.objective);
			SignalValue v;
			try {
				v = value(UUID.fromString(t.signal));
			} catch (IllegalArgumentException e) {
				continue;
			}
			// nothing writes nothing: the score keeps its last value
			if (obj == null || v.none()) continue;
			double x = v.asNumber("") * t.scale;
			if (!Double.isFinite(x)) continue;
			int n = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(x)));
			board.getOrCreatePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(t.holder), obj).set(n);
		}
	}

	/** Once a second on the server thread. */
	public void tick() {
		values = SignalEngine.tick(nodes.values(), this);
		writeScores();
		long now = System.currentTimeMillis();
		for (CopyDesk d : desks.values()) {
			if (now - d.lastStep < d.interval * 1000L) continue;
			d.lastStep = now;
			try {
				step(d);
			} catch (RuntimeException e) {
				d.status = "error: " + e.getMessage();
				Stonkcraft.LOG.warn("Copy desk {} failed", d.id, e);
			}
		}
		if (dirty && now - lastSave > 30_000) {
			lastSave = now;
			save();
		}
	}

	/** The leader's current target: a signal's value, an account read through its card, or a followed address. */
	public SignalValue leaderValue(CopyDesk d) {
		if (d.leader != null) {
			if (d.leader.account()) return account(d.leader);
			return value(d.leader.uuid());
		}
		return d.address.isBlank() ? SignalValue.NONE : address(d.address);
	}

	private void step(CopyDesk d) {
		String paused = d.paused();
		if (paused != null) {
			d.status = paused;
			return;
		}
		if (d.follower == null) {
			d.status = "insert a trade card (the follower)";
			return;
		}
		Account a = accounts.get(d.follower.uuid());
		long now = System.currentTimeMillis();
		String why = a == null ? "the follower's account is gone" : AccessPolicy.checkBlockCard(a, ref(d.follower), true, d.owner, now);
		if (why != null) {
			d.status = why;
			return;
		}
		if (a.kind != Account.Kind.PAPER) {
			d.status = "the follower must be a paper account";
			return;
		}
		BlockPos bp = BlockPos.of(d.pos);
		GuardedAccount follower = accounts.guarded(a, new GuardedAccount.Actor(d.owner, d.ownerName.isEmpty() ? "copy desk" : d.ownerName,
				"copy desk @ " + bp.getX() + "," + bp.getY() + "," + bp.getZ(), true, d.follower.cardId));
		if (follower == null) return;
		double equity = follower.equity();
		if (!(equity > 0)) {
			d.status = "the follower has no equity: fund it first";
			return;
		}
		d.peakEquity = Math.max(d.peakEquity, equity);
		if (d.drawdownPct > 0 && equity < d.peakEquity * (1 - d.drawdownPct / 100)) {
			d.running = false;
			d.status = String.format("stopped: equity fell %.1f%% from its peak", (1 - equity / d.peakEquity) * 100);
			dirty = true;
			return;
		}
		MarketData md = Stonkcraft.market();
		if (md == null) return;
		SignalValue v = leaderValue(d);
		SignalValue.Portfolio target;
		if (v instanceof SignalValue.Portfolio p) {
			target = p;
		} else if (v.none() && d.onLost == CopyDesk.OnLost.FLATTEN) {
			target = SignalValue.Portfolio.empty(1);
		} else {
			d.status = v.none() ? "leader unavailable · holding" : "the leader isn't a portfolio (it's a number)";
			return;
		}
		// perps only: spot holdings and unknown markets aren't copied
		Map<String, Double> perps = new LinkedHashMap<>();
		target.weights().forEach((c, w) -> {
			MarketData.Asset as = md.asset(c);
			if (as != null && !as.spot()) perps.put(as.name(), w);
		});
		target = new SignalValue.Portfolio(perps, target.equity());
		Map<String, double[]> current = follower.perpPositions();
		Map<String, Integer> lev = new HashMap<>();
		Set<String> coins = new LinkedHashSet<>(perps.keySet());
		coins.addAll(current.keySet());
		for (String c : coins) lev.put(c, follower.leverage(c));
		CopyDesk.Plan plan = d.plan(target, equity, current, lev, c -> {
			MarketData.Asset as = md.asset(c);
			return as == null ? 0 : as.maxLeverage();
		}, 8);
		d.tracking = plan.trackingError();
		d.markets = plan.targets().size();
		for (CopyDesk.Leverage l : plan.leverage()) follower.setLeverage(l.coin(), l.leverage(), true);
		for (CopyDesk.Trade t : plan.trades()) {
			OrderRequest r = OrderRequest.market(t.coin(), t.usd() > 0 ? Side.BUY : Side.SELL, Math.abs(t.usd()));
			r.reduceOnly = t.reduceOnly();
			follower.submit(r).thenAccept(res -> server.execute(() -> {
				if (res.ok() && !d.ordered) {
					d.ordered = true;
					Advancements.award(server, d.owner, "copycat");
				}
				d.status = (res.ok() ? "" : "⚠ ") + res.message();
			}));
		}
		if (plan.trades().isEmpty()) d.status = "on target · " + d.markets + " market" + (d.markets == 1 ? "" : "s");
		dirty = true;
	}

	/** "copying 0x0104… · 4 mkts · tracking 1.2%". */
	public String describe(CopyDesk d) {
		String who = d.leader != null ? d.leader.name : d.address.isBlank() ? "nobody" : shorten(d.address);
		String head = d.running ? "copying " + who : "copy " + who + " (stopped)";
		return head + (d.running ? String.format(" · %d mkts · off %.1f%%", d.markets, d.tracking * 100) : "");
	}

	public static String shorten(String addr) {
		return addr.length() > 12 ? addr.substring(0, 6) + "…" + addr.substring(addr.length() - 4) : addr;
	}

	/** For chat: a signal's value. */
	public String describe(SignalNode n) {
		return n.name + " (" + n.op.label + "): " + SignalEngine.describe(value(n.id));
	}

	public static String usd(double v) {
		return Fmt.usd(v);
	}
}
