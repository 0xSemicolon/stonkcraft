package dev.stonkcraft.trading;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.config.HyperConfig;
import dev.stonkcraft.item.Coins;
import dev.stonkcraft.market.MarketData;
import dev.stonkcraft.registry.ModComponents;
import dev.stonkcraft.registry.ModItems;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * All trading accounts on the server: simulated paper ledgers, and watch-only mirrors of Hyperliquid mainnet
 * addresses. Money enters and leaves paper accounts only as coins (and as metal at a Commodity Exchange).
 */
public final class AccountRegistry implements GuardedAccount.Auditor {
	private static final Gson GSON = new GsonBuilder().create();
	private static final Type LEGACY = new TypeToken<Map<String, PaperAccount.State>>() {}.getType();

	/** Persistent state. */
	private static final class Saved {
		List<Account> accounts = new ArrayList<>();
		Set<UUID> granted = new HashSet<>();
	}

	private final MinecraftServer server;
	private final Supplier<MarketData> market;
	private final Map<UUID, Account> accounts = new LinkedHashMap<>();
	private final Map<UUID, PaperAccount> paperViews = new HashMap<>();
	private final Map<UUID, WatchAccount> watchViews = new HashMap<>();
	/** Mirrors of addresses followed by signals and copy desks without an account, shared by address. */
	private final Map<String, WatchAccount> addressViews = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<UUID, UUID> selected = new HashMap<>();
	/** When a block (sensor, executor, display...) last read each account; drives live-feed priority. */
	private final Map<UUID, Long> blockUse = new java.util.concurrent.ConcurrentHashMap<>();
	private long lastFeedAssign;
	private Saved saved = new Saved();

	private final ScheduledExecutorService watchExec = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "Stonkcraft-Watch");
		t.setDaemon(true);
		return t;
	});

	public AccountRegistry(MinecraftServer server, Supplier<MarketData> market) {
		this.server = server;
		this.market = market;
		load();
	}

	/** The account's tag for menus and chat. */
	public String tag(Account a) {
		return a.tag();
	}

	/** {@link #tag} short enough for a block face. */
	public String shortTag(Account a) {
		return a.tag();
	}

	// ------------------------------------------------------------------ persistence

	private Path file() {
		return server.getWorldPath(LevelResource.ROOT).resolve("stonkcraft_accounts.json");
	}

	private void load() {
		try {
			Path f = file();
			if (Files.exists(f)) {
				Saved s = GSON.fromJson(Files.readString(f), Saved.class);
				if (s != null) saved = s;
			}
			if (saved.granted == null) saved.granted = new HashSet<>();
			for (Account a : saved.accounts) {
				a.upgrade();
				migrateLive(a);
				register(a);
			}
			migrateLegacy();
		} catch (Exception e) {
			Stonkcraft.LOG.error("Failed to load accounts", e);
		}
	}

	/** Live trading was removed: a linked live account becomes a read-only mirror of the same address. */
	private void migrateLive(Account a) {
		if (a.kind != Account.Kind.LIVE) return;
		a.kind = Account.Kind.WATCH;
		a.log("Stonkcraft", "server", "Live trading was removed; this account now follows " + a.address + " read-only", true);
		dirty = true;
	}

	/** Old saves had one paper ledger per player; turn each into a named account. */
	private void migrateLegacy() throws Exception {
		Path legacy = server.getWorldPath(LevelResource.ROOT).resolve("stonkcraft_paper.json");
		if (!Files.exists(legacy)) return;
		Map<String, PaperAccount.State> states = GSON.fromJson(Files.readString(legacy), LEGACY);
		if (states != null) {
			states.forEach((id, st) -> {
				Account a = new Account();
				a.id = UUID.randomUUID();
				a.name = "Paper Account";
				a.owner = UUID.fromString(id);
				a.kind = Account.Kind.PAPER;
				if (st.netDeposits == 0) st.netDeposits = HyperConfig.get().paperStartingBalance;
				a.paper = st;
				register(a);
				saved.granted.add(a.owner);
			});
		}
		Files.move(legacy, legacy.resolveSibling("stonkcraft_paper.json.migrated"));
		Stonkcraft.LOG.info("Migrated {} legacy paper accounts", states == null ? 0 : states.size());
		save();
	}

	public void save() {
		try {
			saved.accounts = new ArrayList<>(accounts.values());
			Files.writeString(file(), GSON.toJson(saved));
		} catch (Exception e) {
			Stonkcraft.LOG.error("Failed to save accounts", e);
		}
	}

	public void stop() {
		save();
		watchExec.shutdownNow();
	}

	private void register(Account a) {
		accounts.put(a.id, a);
		if (a.kind == Account.Kind.PAPER) {
			if (a.paper == null) a.paper = PaperAccount.fresh();
			paperViews.put(a.id, new PaperAccount(a.paper, market));
		} else if (a.kind == Account.Kind.WATCH && a.address != null) {
			WatchAccount view = new WatchAccount(market.get(), a.address, watchExec);
			watchViews.put(a.id, view);
			view.start();
		}
	}

	/**
	 * A read-only mirror of any address, for signals and copy desks that follow an address without an account.
	 * Created on first use and shared.
	 */
	@Nullable
	public WatchAccount mirror(String address) {
		String addr = address.trim().toLowerCase();
		if (!addr.matches("0x[0-9a-f]{40}") || !HyperConfig.get().allowWatchAccounts || market.get() == null) return null;
		for (WatchAccount w : watchViews.values()) if (w.user().equals(addr)) return w;
		return addressViews.computeIfAbsent(addr, k -> {
			WatchAccount view = new WatchAccount(market.get(), k, watchExec);
			view.start();
			return view;
		});
	}

	// ------------------------------------------------------------------ lookup and access

	@Nullable
	public Account get(@Nullable UUID id) {
		return id == null ? null : accounts.get(id);
	}

	/** The tradable view of an account, or null if it doesn't exist. */
	@Nullable
	public TradingAccount trading(@Nullable UUID id) {
		Account a = get(id);
		if (a == null) return null;
		return a.kind == Account.Kind.PAPER ? paperViews.get(a.id) : watchViews.get(a.id);
	}

	/** Is the player carrying a card for this account that hasn't been revoked? */
	public static boolean holdsCard(ServerPlayer p, Account a) {
		Inventory inv = p.getInventory();
		String id = a.id.toString();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (dev.stonkcraft.item.AccountCardItem.isCard(s)) {
				ModComponents.AccountRef ref = s.get(ModComponents.ACCOUNT);
				// only cards the account issued count; id-less legacy cards don't grant visibility
				if (ref != null && ref.id().equals(id) && !ref.cardId().isEmpty() && AccessPolicy.checkCard(a, ref, false) == null) return true;
			}
		}
		return false;
	}

	/**
	 * May see the account (terminal, vault screen): the owner, anyone granted access, anyone carrying a valid card,
	 * and ops for paper accounts. Seeing is never enough to trade or move money.
	 */
	public boolean canView(ServerPlayer p, Account a) {
		if (AccessPolicy.level(a, p.getUUID(), System.currentTimeMillis()) != AccessPolicy.Level.NONE) return true;
		if (a.kind == Account.Kind.PAPER && p.hasPermissions(2)) return true;
		return holdsCard(p, a);
	}

	/** @deprecated use {@link #canView}; kept for call sites that only display. */
	@Deprecated
	public boolean canAccess(ServerPlayer p, Account a) {
		return canView(p, a);
	}

	/** The owner, or an op administering a paper account. Gate for money and account management. */
	public boolean canManage(ServerPlayer p, Account a) {
		return a.owner.equals(p.getUUID()) || (a.kind == Account.Kind.PAPER && p.hasPermissions(2));
	}

	public List<Account> accessible(ServerPlayer p) {
		List<Account> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Account a : accounts.values()) {
			if (AccessPolicy.level(a, p.getUUID(), now) != AccessPolicy.Level.NONE || holdsCard(p, a)) out.add(a);
		}
		return out;
	}

	/** The account as this actor may use it: every write checked and logged. Null if it can't run here. */
	@Nullable
	public GuardedAccount guarded(@Nullable Account a, GuardedAccount.Actor actor) {
		TradingAccount t = a == null ? null : trading(a.id);
		return t == null ? null : new GuardedAccount(t, a, actor, this);
	}

	public static GuardedAccount.Actor actor(ServerPlayer p, String via) {
		return new GuardedAccount.Actor(p.getUUID(), p.getGameProfile().getName(), via, false);
	}

	private volatile boolean dirty;
	private long lastSave;

	@Override
	public void record(Account a, GuardedAccount.Actor actor, String text, boolean ok) {
		server.execute(() -> {
			a.log(actor.name(), actor.via(), text, ok);
			dirty = true;
			// tell the owner when someone else (or a block they don't own) acts on their account
			if (actor.id() == null || !actor.id().equals(a.owner)) {
				ServerPlayer owner = server.getPlayerList().getPlayer(a.owner);
				if (owner != null) {
					owner.sendSystemMessage(Component.literal("§b[" + a.name + "] §e" + actor.name() + " §8(" + actor.via() + ")§7: " + (ok ? "§a" : "§c") + text));
				}
			}
		});
	}

	/** Marks accounts changed so the next periodic save writes them. */
	public void markDirty() {
		dirty = true;
	}

	/** Logs an account-management event (grants, cards, money) and saves. */
	public void logEvent(Account a, ServerPlayer who, String text) {
		a.log(who.getGameProfile().getName(), "vault", text, true);
		save();
	}

	// ------------------------------------------------------------------ permissions

	public Account.CardRecord issueCard(Account a, String level, String label) {
		Account.CardRecord c = new Account.CardRecord();
		c.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		c.level = "READ".equals(level) ? "READ" : "TRADE";
		c.label = label == null ? "" : label.replaceAll("[§\\p{Cntrl}]", "").trim();
		if (c.label.length() > 24) c.label = c.label.substring(0, 24);
		c.issued = System.currentTimeMillis();
		a.cards.add(c);
		while (a.cards.size() > 64) a.cards.remove(0);
		save();
		return c;
	}

	public boolean revokeCard(Account a, String cardId) {
		Account.CardRecord c = a.card(cardId);
		if (c == null || c.revoked) return false;
		c.revoked = true;
		save();
		return true;
	}

	/** Adds or replaces a grant; returns an error or null. */
	@Nullable
	public String setGrant(Account a, Account.Grant g) {
		if (g.player.equals(a.owner)) return "That's the owner";
		if (a.kind == Account.Kind.WATCH && g.role == Account.Role.TRADE) return "Watch-only accounts can't be traded";
		a.grants.removeIf(x -> x.player.equals(g.player));
		if (a.grants.size() >= 32) return "Too many people have access already";
		g.created = System.currentTimeMillis();
		a.grants.add(g);
		save();
		return null;
	}

	public boolean removeGrant(Account a, UUID player) {
		boolean removed = a.grants.removeIf(g -> g.player.equals(player));
		if (removed) save();
		return removed;
	}

	public void setFrozen(Account a, boolean frozen) {
		a.frozen = frozen;
		save();
	}

	public List<Account> owned(UUID player) {
		List<Account> out = new ArrayList<>();
		for (Account a : accounts.values()) if (a.owner.equals(player)) out.add(a);
		return out;
	}

	/** The account a player trades from the terminal and commands. */
	@Nullable
	public Account selected(ServerPlayer p) {
		Account a = get(selected.get(p.getUUID()));
		if (a != null && canView(p, a)) return a;
		List<Account> mine = accessible(p);
		if (mine.isEmpty()) return null;
		selected.put(p.getUUID(), mine.get(0).id);
		return mine.get(0);
	}

	public void select(ServerPlayer p, UUID id) {
		Account a = get(id);
		if (a != null && canView(p, a)) selected.put(p.getUUID(), id);
	}

	// ------------------------------------------------------------------ lifecycle

	private Account base(ServerPlayer owner, String name, String fallback, Account.Kind kind) {
		Account a = new Account();
		a.id = UUID.randomUUID();
		a.name = cleanName(name, fallback);
		a.owner = owner.getUUID();
		a.ownerName = owner.getGameProfile().getName();
		a.kind = kind;
		return a;
	}

	public Account createPaper(ServerPlayer owner, String name) {
		Account a = base(owner, name, "Paper Account", Account.Kind.PAPER);
		a.paper = PaperAccount.fresh();
		register(a);
		save();
		dev.stonkcraft.game.Advancements.award(owner, "open_for_business");
		return a;
	}

	/** The player's own watch account for an address, opened if they have none yet. */
	public Account watchFor(ServerPlayer owner, String address) {
		String addr = address.trim().toLowerCase();
		for (Account a : accounts.values()) {
			if (a.kind == Account.Kind.WATCH && addr.equals(a.address) && owner.getUUID().equals(a.owner)) return a;
		}
		return createWatch(owner, "", addr);
	}

	/** A read-only view of any Hyperliquid mainnet address. Needs no keys. */
	public Account createWatch(ServerPlayer owner, String name, String address) {
		if (!HyperConfig.get().allowWatchAccounts) throw new IllegalStateException("Watch accounts are off on this server");
		String addr = address.trim().toLowerCase();
		if (!addr.matches("0x[0-9a-f]{40}")) throw new IllegalStateException("That isn't an account address (0x followed by 40 hex characters)");
		Account a = base(owner, name, "Watch " + addr.substring(0, 6) + "…" + addr.substring(38), Account.Kind.WATCH);
		a.address = addr;
		register(a);
		save();
		return a;
	}

	public void rename(Account a, String name) {
		a.name = cleanName(name, a.name);
		save();
	}

	/** Deletes an empty account. Returns an error, or null on success. */
	@Nullable
	public String close(Account a) {
		TradingAccount t = trading(a.id);
		if (t != null) {
			if (a.kind == Account.Kind.PAPER && t.equity() >= 1) return "Withdraw everything first (" + Fmt.usd(t.equity()) + " left)";
			if (a.kind != Account.Kind.WATCH && t.snapshot().getAsJsonArray("positions").size() > 0) return "Close all positions first";
		}
		accounts.remove(a.id);
		paperViews.remove(a.id);
		watchViews.remove(a.id);
		save();
		return null;
	}

	private static String cleanName(String name, String fallback) {
		String n = name == null ? "" : name.replaceAll("[§\\p{Cntrl}]", "").trim();
		if (n.length() > 24) n = n.substring(0, 24);
		return n.isEmpty() ? fallback : n;
	}

	// ------------------------------------------------------------------ money in and out

	/** Credits coins already taken from the player. Returns null on success or an error message. */
	@Nullable
	public String deposit(Account a, long usd) {
		if (usd <= 0) return "Nothing to deposit";
		if (a.kind != Account.Kind.PAPER) return "Watch-only accounts don't take coins";
		paperViews.get(a.id).deposit(usd);
		save();
		return null;
	}

	/** Debits the account; on success the caller hands out coins. Returns null on success or an error. */
	@Nullable
	public String withdraw(Account a, long usd) {
		if (usd <= 0) return "Enter an amount";
		if (a.kind != Account.Kind.PAPER) return "Watch-only accounts hold no coins to withdraw";
		PaperAccount p = paperViews.get(a.id);
		if (!p.withdraw(usd)) return "Only " + Fmt.usd(p.withdrawable()) + " is free to withdraw";
		save();
		return null;
	}

	/** The paper ledger behind an account, or null for watch accounts. */
	@Nullable
	public PaperAccount paper(@Nullable UUID id) {
		return id == null ? null : paperViews.get(id);
	}

	/** First join: a one-time pouch of paper money. */
	public void onJoin(ServerPlayer p) {
		for (Account a : accounts.values()) {
			if (a.owner.equals(p.getUUID()) && a.ownerName.isEmpty()) a.ownerName = p.getGameProfile().getName();
		}
		if (saved.granted.add(p.getUUID())) {
			long grant = (long) HyperConfig.get().paperStarterGrant;
			for (ItemStack s : Coins.make(grant, "paper")) {
				if (!p.getInventory().add(s)) p.drop(s, false);
			}
			if (grant > 0) {
				p.sendSystemMessage(Component.literal("§b[Stonkcraft] §7Here's §f" + Fmt.usd(grant) + "§7 in paper money. Craft a §fTrading Terminal§7, open an account in its §fBuild§7 tab, then hit §f+ Fund§7 to deposit it."));
			} else {
				p.sendSystemMessage(Component.literal("§b[Stonkcraft] §7Money is earned here: craft an §fAccount Vault§7 and a §fCommodity Exchange§7, then deposit gold, iron, diamonds and more into your account at live prices. Trade at a §fTrading Terminal§7. Recipes are in your recipe book."));
			}
			save();
		}
	}

	/** Advancements that follow from an account's state: a first fill, a million in equity. */
	private void milestones(ServerPlayer p, PaperAccount acct) {
		if (!acct.state().fills.isEmpty()) dev.stonkcraft.game.Advancements.awardOnce(p, "first_fill");
		if (acct.state().fills.stream().anyMatch(f -> f.pnl() > 0)) dev.stonkcraft.game.Advancements.awardOnce(p, "in_the_green");
		if (acct.equity() >= 1_000_000) dev.stonkcraft.game.Advancements.awardOnce(p, "millionaire");
	}

	/** Called by blocks reading an account, so accounts that drive circuits get the live feed first. */
	public void noteUse(UUID accountId) {
		blockUse.put(accountId, System.currentTimeMillis());
	}

	/**
	 * Hyperliquid caps distinct users across user feeds per IP, so live feeds go to accounts in order: used by a
	 * loaded block in the last 30s, selected by an online player, then the rest. Others keep polling.
	 */
	private void assignFeeds(long now) {
		MarketData md = market.get();
		if (md == null || md.socket() == null || (watchViews.isEmpty() && addressViews.isEmpty())) return;
		java.util.Set<UUID> selectedOnline = new java.util.HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			UUID s = selected.get(p.getUUID());
			if (s != null) selectedOnline.add(s);
		}
		List<UUID> order = new ArrayList<>(watchViews.keySet());
		order.sort(java.util.Comparator.comparingInt(id -> now - blockUse.getOrDefault(id, 0L) < 30_000 ? 0 : selectedOnline.contains(id) ? 1 : 2));
		int slots = Math.max(0, HyperConfig.get().websocketMaxUsers);
		java.util.Set<String> fedUsers = new java.util.HashSet<>();
		List<WatchAccount> views = new ArrayList<>();
		for (UUID id : order) views.add(watchViews.get(id));
		// addresses followed by copy desks and signals drive trades, so they go first
		views.addAll(0, addressViews.values());
		for (WatchAccount la : views) {
			// several accounts can watch the same address; they share one feed slot
			boolean give = fedUsers.contains(la.user()) || fedUsers.size() < slots;
			if (give) fedUsers.add(la.user());
			la.setLiveFeed(give);
		}
	}

	/** {fed, total} watched addresses, for /stonk status. */
	public int[] feedCounts() {
		int fed = 0;
		for (WatchAccount la : watchViews.values()) if (la.hasLiveFeed()) fed++;
		for (WatchAccount la : addressViews.values()) if (la.hasLiveFeed()) fed++;
		return new int[]{fed, watchViews.size() + addressViews.size()};
	}

	public void tick() {
		long nowMs = System.currentTimeMillis();
		// paper ledgers and activity logs change constantly; save at most every 30s
		if (nowMs - lastSave >= 30_000) {
			lastSave = nowMs;
			if (dirty || !paperViews.isEmpty()) {
				dirty = false;
				save();
			}
		}
		if (nowMs - lastFeedAssign >= 10_000) {
			lastFeedAssign = nowMs;
			assignFeeds(nowMs);
		}
		long now = System.currentTimeMillis();
		paperViews.forEach((id, acct) -> {
			List<String> events = acct.tick(now);
			Account a = accounts.get(id);
			ServerPlayer p = a == null ? null : server.getPlayerList().getPlayer(a.owner);
			if (p != null) milestones(p, acct);
			if (events.isEmpty()) return;
			if (p != null) for (String e : events) {
				p.sendSystemMessage(Component.literal("§b[" + a.name + "] " + e));
				if (e.contains("Liquidated")) dev.stonkcraft.game.GameHooks.marginCall(p, a.name);
			}
		});
	}
}
