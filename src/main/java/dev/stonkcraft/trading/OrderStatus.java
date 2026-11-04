package dev.stonkcraft.trading;

/** Where an order placed with a client order id stands. Executors expose it on their comparator. */
public enum OrderStatus {
	NONE(0), RESTING(4), PARTIAL(8), FILLED(15), CANCELLED(2), REJECTED(1);

	/** Comparator strength for the executor's "My order" mode. */
	public final int signal;

	OrderStatus(int signal) {
		this.signal = signal;
	}

	public boolean isOpen() {
		return this == RESTING || this == PARTIAL;
	}

	/** Maps Hyperliquid's orderStatus strings ("open", "filled", "canceled", "marginCanceled", "rejected"...). */
	public static OrderStatus fromExchange(String s) {
		if (s == null) return NONE;
		if (s.equals("open") || s.equals("triggered")) return RESTING;
		if (s.equals("filled")) return FILLED;
		if (s.endsWith("Rejected") || s.equals("rejected")) return REJECTED;
		if (s.toLowerCase(java.util.Locale.ROOT).contains("cancel")) return CANCELLED;
		return NONE;
	}
}
