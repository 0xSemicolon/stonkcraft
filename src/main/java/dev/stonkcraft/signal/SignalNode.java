package dev.stonkcraft.signal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** One signal: an op, its parameters and up to four inputs. Lives in the server's signal registry; a Signal Mixer block edits it. */
public final class SignalNode {
	/** An input: another signal, or an account read through one of its cards. */
	public static final class Input {
		public static final String SIGNAL = "SIGNAL", ACCOUNT = "ACCOUNT";

		public String kind = SIGNAL;
		/** Signal id, or account id. */
		public String id = "";
		/** For account inputs: the card's id and level, re-checked on every evaluation. */
		public String cardId = "";
		public String level = "";
		public String name = "";
		public double weight = 1;

		public boolean account() {
			return ACCOUNT.equals(kind);
		}

		@Nullable
		public UUID uuid() {
			try {
				return UUID.fromString(id);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	public UUID id = UUID.randomUUID();
	@Nullable
	public UUID owner;
	public String ownerName = "";
	public String name = "Signal";
	public SignalOp op = SignalOp.BLEND;
	public String text = "";
	/** The market for per-coin account figures (METRIC of POSITION). Other ops name their market in {@link #text}. */
	public String market = "";
	public double a, b;
	/** Made by a block for its own reading (a sensor, display, desk or executor), not by a mixer; that block edits it. */
	public boolean tap;
	public List<Input> inputs = new ArrayList<>();
	/** Strongest redstone into the mixer block, kept while it's unloaded. */
	public int redstone;
	/** Where the mixer block stands. */
	public long pos;
	public String dim = "minecraft:overworld";

	/** (time ms, value) samples of the first input, for DELAY. */
	transient ArrayDeque<Object[]> history;
	transient SignalValue ema;
	transient long emaAt;
	/** For BELL: whether the exchange was open last tick, and the latest bell. */
	transient Boolean wasOpen;
	transient SignalValue.Event bell;
	/** For ORDER: the latest event its executor pushed. */
	public transient SignalValue pushed = SignalValue.NONE;
	/** Value at the last evaluation. */
	public transient SignalValue last = SignalValue.NONE;

	/** Everything that defines what the node computes, to tell whether a block's rewrite of its tap changed it. */
	public String shape() {
		StringBuilder sb = new StringBuilder().append(op).append('|').append(text).append('|').append(market).append('|').append(a).append('|').append(b).append('|').append(name);
		for (Input in : inputs) sb.append('|').append(in.kind).append(in.id).append(in.cardId).append(in.level).append(in.weight);
		return sb.toString();
	}

	public void upgrade() {
		if (inputs == null) inputs = new ArrayList<>();
		if (op == null) op = SignalOp.BLEND;
		if (text == null) text = "";
		if (name == null) name = "Signal";
		if (market == null) market = "";
		if (pushed == null) pushed = SignalValue.NONE;
		if (last == null) last = SignalValue.NONE;
	}
}
