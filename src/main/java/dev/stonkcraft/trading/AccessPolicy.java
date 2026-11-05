package dev.stonkcraft.trading;

import dev.stonkcraft.registry.ModComponents;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Who may do what with an account. Pure rules over {@link Account} so they can be tested without a server; every
 * trading path (terminal, commands, executors, kill switches, leverage dials) goes through these via
 * {@link GuardedAccount}. Each check returns null when allowed, otherwise the reason it isn't.
 */
public final class AccessPolicy {
	private AccessPolicy() {}

	public enum Level { NONE, VIEW, TRADE, OWNER }

	public static Level level(Account a, @Nullable UUID who, long now) {
		if (who == null) return Level.NONE;
		if (a.owner.equals(who)) return Level.OWNER;
		Account.Grant g = a.grant(who);
		if (g == null || g.expired(now)) return Level.NONE;
		return g.role == Account.Role.TRADE ? Level.TRADE : Level.VIEW;
	}

	/** Cancels only reduce risk, so they're allowed for traders even while the account is frozen. */
	@Nullable
	public static String checkCancel(Account a, @Nullable UUID who, long now) {
		Level l = level(a, who, now);
		return l == Level.OWNER || l == Level.TRADE ? null : noTrade(a, who, now);
	}

	@Nullable
	public static String checkOrder(Account a, @Nullable UUID who, OrderRequest r, long now) {
		String base = checkTrading(a, who, now);
		if (base != null) return base;
		Account.Grant g = a.owner.equals(who) ? null : a.grant(who);
		if (g == null) return null;
		boolean reducing = r.side == Side.CLOSE || r.reduceOnly;
		if (g.reduceOnly && !reducing) return "Your access to " + a.name + " is reduce-only";
		if (!g.markets.isEmpty() && g.markets.stream().noneMatch(m -> m.equalsIgnoreCase(r.coin))) {
			return "Your access to " + a.name + " doesn't include " + r.coin;
		}
		if (!reducing && g.maxOrderUsd > 0 && r.usd > g.maxOrderUsd + 1e-9) {
			return String.format(Locale.ROOT, "Your access to %s is capped at $%,.0f per order", a.name, g.maxOrderUsd);
		}
		return null;
	}

	@Nullable
	public static String checkLeverage(Account a, @Nullable UUID who, String coin, int leverage, long now) {
		String base = checkTrading(a, who, now);
		if (base != null) return base;
		Account.Grant g = a.owner.equals(who) ? null : a.grant(who);
		if (g == null) return null;
		if (!g.markets.isEmpty() && g.markets.stream().noneMatch(m -> m.equalsIgnoreCase(coin))) {
			return "Your access to " + a.name + " doesn't include " + coin;
		}
		if (g.maxLeverage > 0 && leverage > g.maxLeverage) return "Your access to " + a.name + " is capped at " + g.maxLeverage + "x";
		return null;
	}

	/** A scoped card's own limits on an order, on top of what the block's owner may do; null when within them. */
	@Nullable
	public static String checkScope(Account a, @Nullable String cardId, OrderRequest r, long now) {
		Account.Scope s = scope(a, cardId);
		if (s == null) return null;
		if (s.expired(now)) return "This card has expired";
		boolean reducing = r.side == Side.CLOSE || r.reduceOnly;
		if (s.reduceOnly && !reducing) return "This card is reduce-only";
		if (!s.markets.isEmpty() && s.markets.stream().noneMatch(m -> m.equalsIgnoreCase(r.coin))) return "This card doesn't include " + r.coin;
		if (!reducing && s.maxOrderUsd > 0 && r.usd > s.maxOrderUsd + 1e-9) return String.format(Locale.ROOT, "This card is capped at $%,.0f per order", s.maxOrderUsd);
		return null;
	}

	@Nullable
	public static String checkScopeLeverage(Account a, @Nullable String cardId, String coin, int leverage, long now) {
		Account.Scope s = scope(a, cardId);
		if (s == null) return null;
		if (s.expired(now)) return "This card has expired";
		if (!s.markets.isEmpty() && s.markets.stream().noneMatch(m -> m.equalsIgnoreCase(coin))) return "This card doesn't include " + coin;
		if (s.maxLeverage > 0 && leverage > s.maxLeverage) return "This card is capped at " + s.maxLeverage + "x";
		return null;
	}

	@Nullable
	private static Account.Scope scope(Account a, @Nullable String cardId) {
		if (cardId == null || cardId.isEmpty()) return null;
		Account.CardRecord c = a.card(cardId);
		return c == null ? null : c.scope;
	}

	/** Anything else that changes positions or margin (e.g. isolated margin moves). */
	@Nullable
	public static String checkTrading(Account a, @Nullable UUID who, long now) {
		Level l = level(a, who, now);
		if (l != Level.OWNER && l != Level.TRADE) return noTrade(a, who, now);
		if (a.frozen && l != Level.OWNER) return a.name + " is frozen by its owner";
		return null;
	}

	/**
	 * Card checks for blocks: the card must still be valid, and trading needs a TRADE card (or a legacy card from
	 * before card ids). Who may trade is decided separately, by the block owner's level.
	 */
	@Nullable
	public static String checkCard(Account a, ModComponents.AccountRef ref, boolean trade) {
		if (ref.cardId().isEmpty()) {
			// cards from before card ids: still honoured, the block-owner rule protects them
			return trade && ref.readOnly() ? "Read-only card: it can't drive trades" : null;
		}
		Account.CardRecord c = a.card(ref.cardId());
		if (c == null || c.revoked) return "This card was revoked";
		if (c.scope != null && c.scope.expired(System.currentTimeMillis())) return "This card has expired";
		// the account's record decides the level, never the item (item data can be edited in creative)
		if (trade && !"TRADE".equals(c.level)) return "Read-only card: it can't drive trades";
		return null;
	}

	/**
	 * Card check for a block. Cards printed before card ids carry no id the account can vouch for (and could be
	 * forged), so they only work in blocks whose owner has access to the account anyway.
	 */
	@Nullable
	public static String checkBlockCard(Account a, ModComponents.AccountRef ref, boolean trade, @Nullable UUID blockOwner, long now) {
		if (ref.cardId().isEmpty() && level(a, blockOwner, now) == Level.NONE) return "Old card: reprint it from the vault";
		return checkCard(a, ref, trade);
	}

	/** Block-driven trading also stops while frozen, even for the owner's own blocks. */
	@Nullable
	public static String checkBlockTrading(Account a, @Nullable UUID blockOwner, long now) {
		if (blockOwner == null) return "This block has no owner yet; open it once to claim it";
		if (a.frozen) return a.name + " is frozen by its owner";
		return checkTrading(a, blockOwner, now);
	}

	private static String noTrade(Account a, @Nullable UUID who, long now) {
		Account.Grant g = who == null ? null : a.grant(who);
		if (g != null && g.expired(now)) return "Your access to " + a.name + " has expired";
		if (g != null && g.role == Account.Role.VIEW) return "You can view " + a.name + " but not trade it";
		return "You don't have trading access to " + a.name;
	}
}
