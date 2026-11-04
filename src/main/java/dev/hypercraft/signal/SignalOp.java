package dev.hypercraft.signal;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * What a Signal Mixer does. Sources take no inputs (or one account, for METRIC); the rest transform their inputs.
 * {@code text}, {@code a} and {@code b} are the op's parameters; their labels are null when unused.
 */
public enum SignalOp {
	// sources
	ADDRESS("Follow address", 0, 0, "Address", null, null, "Portfolio of any Hyperliquid address, read-only"),
	BASKET("Basket", 0, 0, "Weights", null, null, "Fixed weights, e.g. BTC:0.5, ETH:0.3, GOLD:-0.2"),
	PRICE("Price", 0, 0, "Market", null, null, "A market's mid price"),
	CHANGE("Change %", 0, 0, "Market", "Window s", null, "A market's % change over the window (max 900 s)"),
	FUNDING("Funding APR", 0, 0, "Market", null, null, "A perp's annualized funding, in %"),
	METRIC("Account figure", 1, 1, "Figure", null, null, "Any Account Display figure of the input account (EQUITY, PNL_DAY, MARGIN_USAGE...)"),
	REDSTONE("Redstone", 0, 0, null, null, null, "The strongest redstone signal into the mixer, 0-15"),
	CONSTANT("Constant", 0, 0, null, "Value", null, "A fixed number"),
	MARKET_HOURS("NYSE open", 0, 0, null, null, null, "1 while the New York Stock Exchange is open, else 0"),
	POSITION("Position $", 1, 1, "Market", null, null, "The input account's position in a market, in USD: + long, - short"),
	SIDE("Side", 1, 1, "Market", null, null, "+1 while the input account is long the market, -1 while short, 0 flat"),
	POSITION_PNL("Position PnL", 1, 1, "Market", null, null, "The input account's unrealized PnL on a market, in USD; nothing while flat"),
	OPEN_ORDERS("Open orders", 1, 1, "Market", null, null, "How many orders the input account has open on a market (blank = every market)"),
	SPREAD("Spread", 0, 0, "Market", null, null, "The order book's bid-ask spread, in basis points"),
	BOOK_TILT("Book tilt", 0, 0, "Market", null, null, "The bid side's share of the top 10 book levels, 0..1 (0.5 = balanced)"),
	FILL("Fill", 1, 1, "Market", null, null, "An event each time one of the input account's orders fills on the market"),
	BELL("NYSE bell", 0, 0, null, null, null, "An event at the New York Stock Exchange's opening and closing bell"),
	DESK("Copy desk", 0, 0, null, null, null, "A Copy Desk's tracking error, 0..1 of the follower's equity", true),
	ORDER("Executor", 0, 0, null, null, null, "An event each time a Trade Executor's order goes through", true),
	// transforms
	BLEND("Blend", 1, 4, null, null, null, "Weighted sum of the inputs (set each input's weight)"),
	SCALE("Scale", 1, 2, null, "Factor", null, "First input times the second (a number), or times the factor"),
	INVERT("Invert", 1, 1, null, null, null, "Times -1: fade a trader"),
	FILTER("Filter", 1, 1, "Markets", "Drop? 0/1", "Side 0/1/2", "Keep (or drop) the listed markets; side 1 = longs only, 2 = shorts only"),
	TOP("Top N", 1, 1, null, "N", null, "Keep the N largest positions"),
	CAP("Cap", 1, 1, null, "Max gross x", "Max per coin", "Scale down to a gross leverage, clip each weight"),
	DELAY("Delay", 1, 1, null, "Seconds", null, "The input as it was N seconds ago (max 3600)"),
	SMOOTH("Smooth", 1, 1, null, "Half-life s", null, "Exponential moving average"),
	GATE("Gate", 2, 2, null, "Threshold", null, "The first input while the second is above the threshold, else zero"),
	COMPARE("Compare", 1, 1, null, "Threshold", "≤? 0/1", "1 if the input is ≥ the threshold (or ≤ with 1), else 0"),
	MAP("Map", 1, 1, null, "Low", "High", "Scales low..high onto 0..1, clamped"),
	PICK("Pick", 1, 1, "Market / GROSS / NET", null, null, "A portfolio's weight in one market, or its gross or net leverage"),
	DELTA("Change by", 1, 1, null, "Seconds", null, "The input now minus the input N seconds ago (max 3600)");

	public final String label;
	public final int minInputs, maxInputs;
	@Nullable
	public final String textLabel, aLabel, bLabel;
	public final String help;
	/** Only a block's own reading uses it (a Copy Desk's tracking, an executor's orders); mixers don't offer it. */
	public final boolean tapOnly;

	SignalOp(String label, int minInputs, int maxInputs, @Nullable String textLabel, @Nullable String aLabel, @Nullable String bLabel, String help) {
		this(label, minInputs, maxInputs, textLabel, aLabel, bLabel, help, false);
	}

	SignalOp(String label, int minInputs, int maxInputs, @Nullable String textLabel, @Nullable String aLabel, @Nullable String bLabel, String help, boolean tapOnly) {
		this.tapOnly = tapOnly;
		this.label = label;
		this.minInputs = minInputs;
		this.maxInputs = maxInputs;
		this.textLabel = textLabel;
		this.aLabel = aLabel;
		this.bLabel = bLabel;
		this.help = help;
	}

	public boolean source() {
		return maxInputs == 0;
	}

	/** Reads one figure of its single input, which must be an account card. */
	public boolean readsAccount() {
		return this == METRIC || this == POSITION || this == SIDE || this == POSITION_PNL || this == OPEN_ORDERS || this == FILL;
	}

	public static SignalOp parse(@Nullable String s) {
		try {
			return s == null ? BLEND : valueOf(s.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return BLEND;
		}
	}
}
