package dev.hypercraft.block;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/** A Trade Executor setting that can come from a redstone signal instead of a fixed value. */
public enum ExecutorInput {
	SIZE("Size", "SIZE", "$", 0, 1000),
	LEVERAGE("Leverage", "LEV", "x", 1, 20),
	PRICE("Price offset", "PX", "%", -2, 2),
	TP("Take-profit", "TP", "%", 1, 10),
	SL("Stop-loss", "SL", "%", 1, 5),
	MINUTES("TWAP minutes", "TWAP", "m", 5, 120);

	public final String label;
	/** Short tag painted on the block face. */
	public final String tag;
	public final String unit;
	public final double defLo, defHi;

	ExecutorInput(String label, String tag, String unit, double defLo, double defHi) {
		this.label = label;
		this.tag = tag;
		this.unit = unit;
		this.defLo = defLo;
		this.defHi = defHi;
	}

	/** Where a setting's value comes from. */
	public enum Source {
		FIXED("Fixed"), TRIGGER("Trigger strength"), NORTH("North face"), EAST("East face"), SOUTH("South face"), WEST("West face"),
		UP("Top face"), DOWN("Bottom face"), SIGNAL("Signal card (0..1)");

		public final String label;

		Source(String label) {
			this.label = label;
		}

		/** The block face this source reads, or null for FIXED/TRIGGER. */
		@Nullable
		public Direction face() {
			return switch (this) {
				case NORTH -> Direction.NORTH;
				case EAST -> Direction.EAST;
				case SOUTH -> Direction.SOUTH;
				case WEST -> Direction.WEST;
				case UP -> Direction.UP;
				case DOWN -> Direction.DOWN;
				default -> null;
			};
		}

		public static Source parse(String s) {
			try {
				return valueOf(s);
			} catch (IllegalArgumentException | NullPointerException e) {
				return FIXED;
			}
		}
	}

	/** Signal 0 gives lo, 15 gives hi, linear in between. */
	public static double map(int signal, double lo, double hi) {
		return lo + (hi - lo) * Math.max(0, Math.min(15, signal)) / 15.0;
	}
}
