package dev.hypercraft.signal;

/** Remembers the last event a block saw on a signal, so it reacts once per event, and not to an old one on load. */
public final class EventWatch {
	private long seen = Long.MIN_VALUE;

	/** Whether {@code v} is an event that hasn't been seen yet. The first value seen only sets the baseline. */
	public boolean next(SignalValue v) {
		if (!(v instanceof SignalValue.Event e)) return false;
		boolean fresh = seen != Long.MIN_VALUE && e.seq() != seen && e.at() > 0;
		seen = e.seq();
		return fresh;
	}
}
