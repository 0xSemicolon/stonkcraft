package dev.hypercraft.trading;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client order ids: "0x" + 16 hex (who placed it) + 16 hex (which order). The owner half is derived from a
 * block position or player, so a block can recognise its own orders after a restart without a lookup table.
 */
public final class Cloids {
	private Cloids() {}

	private static final AtomicLong SEQ = new AtomicLong();

	/** Owner half for a block: hash of dimension and position. */
	public static String blockPrefix(String dimension, long blockPos) {
		return "0x" + hex16(fnv(dimension + "@" + blockPos));
	}

	/** Owner half for orders a player places from the terminal or commands. */
	public static String playerPrefix(UUID player) {
		return "0x" + hex16(fnv("player:" + player));
	}

	/** A fresh id under {@code prefix}: time in ms (high 48 bits) and a counter (low 16). */
	public static String next(String prefix) {
		long low = (System.currentTimeMillis() << 16) | (SEQ.incrementAndGet() & 0xFFFF);
		return prefix + hex16(low);
	}

	public static boolean ownedBy(String cloid, String prefix) {
		return cloid != null && cloid.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
	}

	public static boolean valid(String cloid) {
		return cloid != null && cloid.matches("0x[0-9a-fA-F]{32}");
	}

	private static String hex16(long v) {
		return String.format(Locale.ROOT, "%016x", v);
	}

	/** 64-bit FNV-1a. */
	private static long fnv(String s) {
		long h = 0xcbf29ce484222325L;
		for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
			h ^= b & 0xff;
			h *= 0x100000001b3L;
		}
		return h;
	}
}
