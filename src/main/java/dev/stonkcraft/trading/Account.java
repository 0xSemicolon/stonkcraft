package dev.stonkcraft.trading;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** A named trading account, owned by a player and held in the world by Account Vault blocks. Persisted as JSON. */
public final class Account {
	/**
	 * PAPER: a simulated ledger. WATCH: any Hyperliquid mainnet address, read-only; its card drives sensors, displays
	 * and copy trading but can't trade or hold coins.
	 */
	public enum Kind { PAPER, WATCH }

	public UUID id;
	public String name;
	public UUID owner;
	public String ownerName = "";
	public Kind kind;
	/** Simulated ledger, PAPER only. */
	@Nullable
	public PaperAccount.State paper;
	/** The Hyperliquid address a WATCH account follows. */
	@Nullable
	public String address;

	/** What another player may do with this account. */
	public enum Role { VIEW, TRADE }

	/** Access the owner granted to another player, with limits on what they may trade. */
	public static final class Grant {
		public UUID player;
		public String name = "";
		public Role role = Role.VIEW;
		/** Largest opening order in USD; 0 = no limit beyond the account's own caps. */
		public double maxOrderUsd;
		/** Highest leverage they may set; 0 = any. */
		public int maxLeverage;
		/** Markets they may trade (exact names); empty = all. */
		public java.util.List<String> markets = new java.util.ArrayList<>();
		/** Only orders that shrink positions. */
		public boolean reduceOnly;
		/** Epoch ms after which the grant is void; 0 = never. */
		public long expiresAt;
		public long created;

		public boolean expired(long now) {
			return expiresAt > 0 && now >= expiresAt;
		}
	}

	/** A printed card: READ cards feed sensors and displays, TRADE cards also drive executors. */
	public static final class CardRecord {
		public String id = "";
		public String level = "TRADE";
		public String label = "";
		public long issued;
		public boolean revoked;
		/** Limits the card itself carries, whoever's block it's in; null = none. */
		@Nullable
		public Scope scope;
	}

	/**
	 * A card's own limits, the same ones a grant has. An order through a block must pass both these and what the
	 * block's owner may do. Kept on the account's record: the item's copy is only for its tooltip.
	 */
	public static final class Scope {
		/** Markets it may trade (exact names); empty = all. */
		public java.util.List<String> markets = new java.util.ArrayList<>();
		/** Largest opening order in USD; 0 = no limit. */
		public double maxOrderUsd;
		/** Highest leverage it may set; 0 = any. */
		public int maxLeverage;
		/** Only orders that shrink positions. */
		public boolean reduceOnly;
		/** Epoch ms after which the card stops working; 0 = never. */
		public long expiresAt;

		public boolean expired(long now) {
			return expiresAt > 0 && now >= expiresAt;
		}

		public boolean empty() {
			return markets.isEmpty() && maxOrderUsd <= 0 && maxLeverage <= 0 && !reduceOnly && expiresAt <= 0;
		}

		/** Whether this is at least as tight as {@code old} in every way, so changing a card to it only narrows it. */
		public boolean within(Scope old) {
			if (!old.markets.isEmpty() && (markets.isEmpty() || !markets.stream().allMatch(m -> old.markets.stream().anyMatch(m::equalsIgnoreCase)))) return false;
			if (old.maxOrderUsd > 0 && (maxOrderUsd <= 0 || maxOrderUsd > old.maxOrderUsd)) return false;
			if (old.maxLeverage > 0 && (maxLeverage <= 0 || maxLeverage > old.maxLeverage)) return false;
			if (old.reduceOnly && !reduceOnly) return false;
			return old.expiresAt <= 0 || expiresAt > 0 && expiresAt <= old.expiresAt;
		}

		/** "BTC, ETH · $500/order · 5x max · reduce-only · expires Fri 6 Nov 18:00". */
		public String summary() {
			java.util.List<String> parts = new java.util.ArrayList<>();
			if (!markets.isEmpty()) parts.add(String.join(", ", markets.stream().map(m -> m.contains(":") ? m.substring(m.indexOf(':') + 1) : m).toList()));
			if (maxOrderUsd > 0) parts.add(String.format(java.util.Locale.ROOT, "$%,.0f/order", maxOrderUsd));
			if (maxLeverage > 0) parts.add(maxLeverage + "x max");
			if (reduceOnly) parts.add("reduce-only");
			if (expiresAt > 0) parts.add("expires " + java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.ROOT)
					.format(java.time.Instant.ofEpochMilli(expiresAt).atZone(java.time.ZoneId.systemDefault())));
			return String.join(" · ", parts);
		}
	}

	/** One line of the account's activity log. */
	public record Activity(long time, String actor, String via, String text, boolean ok) {}

	public java.util.List<Grant> grants = new java.util.ArrayList<>();
	public java.util.List<CardRecord> cards = new java.util.ArrayList<>();
	/** Owner's panic switch: nobody but the owner trades, and no block-driven orders. Cancels still work. */
	public boolean frozen;
	public java.util.ArrayDeque<Activity> activity = new java.util.ArrayDeque<>();

	/** Fills in collections missing from saves written before these fields existed. */
	public void upgrade() {
		if (grants == null) grants = new java.util.ArrayList<>();
		if (cards == null) cards = new java.util.ArrayList<>();
		if (activity == null) activity = new java.util.ArrayDeque<>();
		for (Grant g : grants) if (g.markets == null) g.markets = new java.util.ArrayList<>();
		for (CardRecord c : cards) if (c.scope != null && c.scope.markets == null) c.scope.markets = new java.util.ArrayList<>();
	}

	@Nullable
	public Grant grant(UUID player) {
		for (Grant g : grants) if (g.player.equals(player)) return g;
		return null;
	}

	@Nullable
	public CardRecord card(String id) {
		for (CardRecord c : cards) if (c.id.equals(id)) return c;
		return null;
	}

	public void log(String actor, String via, String text, boolean ok) {
		activity.addFirst(new Activity(System.currentTimeMillis(), actor, via, text, ok));
		while (activity.size() > 100) activity.removeLast();
	}

	/** What players see next to the name: PAPER or WATCH. */
	public String tag() {
		return kind.name();
	}

	public String label() {
		return kind.name();
	}
}
