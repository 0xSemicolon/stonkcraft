package dev.stonkcraft.trading;

/** What an Account Display shows. */
public enum AccountMetric {
	EQUITY("Equity", false),
	PNL("PnL", false),
	PNL_DAY("PnL 24h", false),
	PNL_WEEK("PnL 7d", false),
	PNL_MONTH("PnL 30d", false),
	RETURN("Return", false),
	UPNL("uPnL", false),
	REALIZED("Realized PnL", false),
	FREE("Free collateral", false),
	MARGIN("Margin used", false),
	MARGIN_USAGE("Margin usage", false),
	LEVERAGE("Account leverage", false),
	EXPOSURE("Gross exposure", false),
	NET_EXPOSURE("Net exposure", false),
	POSITIONS("Open positions", false),
	ORDERS("Open orders", false),
	FUNDING("Funding (24h)", false),
	POSITION("Position", true);

	public final String label;
	/** Shows one market (the configured coin) rather than the whole account. */
	public final boolean perCoin;

	AccountMetric(String label, boolean perCoin) {
		this.label = label;
		this.perCoin = perCoin;
	}

	public static AccountMetric parse(String s) {
		try {
			return valueOf(s);
		} catch (IllegalArgumentException | NullPointerException e) {
			return EQUITY;
		}
	}
}
