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
}
