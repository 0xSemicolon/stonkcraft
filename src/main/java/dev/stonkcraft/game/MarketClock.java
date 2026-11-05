package dev.stonkcraft.game;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * New York Stock Exchange regular hours: 09:30 to 16:00 America/New_York, Monday to Friday, closed on exchange
 * holidays, and closing at 13:00 on early-close days. Holidays are listed for 2026 and 2027.
 */
public final class MarketClock {
	private MarketClock() {}

	public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	public static final LocalTime OPEN = LocalTime.of(9, 30);
	public static final LocalTime CLOSE = LocalTime.of(16, 0);
	public static final LocalTime EARLY_CLOSE = LocalTime.of(13, 0);

	private static final Set<LocalDate> HOLIDAYS = Set.of(
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 19), LocalDate.of(2026, 2, 16), LocalDate.of(2026, 4, 3),
			LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 19), LocalDate.of(2026, 7, 3), LocalDate.of(2026, 9, 7),
			LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 25),
			LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 18), LocalDate.of(2027, 2, 15), LocalDate.of(2027, 3, 26),
			LocalDate.of(2027, 5, 31), LocalDate.of(2027, 6, 18), LocalDate.of(2027, 7, 5), LocalDate.of(2027, 9, 6),
			LocalDate.of(2027, 11, 25), LocalDate.of(2027, 12, 24));

	private static final Set<LocalDate> EARLY = Set.of(
			LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 24), LocalDate.of(2027, 11, 26));

	/** Shifts "now" for development, so the bells can be rung on demand (see {@link #setNow}). Zero in normal play. */
	private static volatile long offsetMs = Long.getLong("stonkcraft.clock.offset", 0L);

	/** The exchange's idea of the current time: the wall clock, plus the dev offset. */
	public static long now() {
		return System.currentTimeMillis() + offsetMs;
	}

	/** Dev and tests: makes {@link #now} read {@code epochMs} from this moment on. */
	public static void setNow(long epochMs) {
		offsetMs = epochMs - System.currentTimeMillis();
	}

	public static boolean tradingDay(LocalDate d) {
		DayOfWeek w = d.getDayOfWeek();
		return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !HOLIDAYS.contains(d);
	}

	public static LocalTime closeOn(LocalDate d) {
		return EARLY.contains(d) ? EARLY_CLOSE : CLOSE;
	}

	public static boolean open(long epochMs) {
		ZonedDateTime t = Instant.ofEpochMilli(epochMs).atZone(NEW_YORK);
		LocalDate d = t.toLocalDate();
		if (!tradingDay(d)) return false;
		LocalTime now = t.toLocalTime();
		return !now.isBefore(OPEN) && now.isBefore(closeOn(d));
	}

	/** "Open · closes 16:00 ET" or "Closed · opens Mon 09:30 ET". */
	public static String describe(long epochMs) {
		ZonedDateTime t = Instant.ofEpochMilli(epochMs).atZone(NEW_YORK);
		if (open(epochMs)) return "NYSE open · closes " + closeOn(t.toLocalDate()) + " ET";
		LocalDate d = t.toLocalDate();
		if (!tradingDay(d) || !t.toLocalTime().isBefore(OPEN)) d = d.plusDays(1);
		while (!tradingDay(d)) d = d.plusDays(1);
		String day = d.equals(t.toLocalDate()) ? "today" : d.equals(t.toLocalDate().plusDays(1)) ? "tomorrow"
				: d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ROOT);
		return "NYSE closed · opens " + day + " " + OPEN + " ET";
	}
}
