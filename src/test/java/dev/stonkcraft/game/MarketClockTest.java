package dev.stonkcraft.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class MarketClockTest {
	private static long ny(int y, int mo, int d, int h, int mi) {
		return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, MarketClock.NEW_YORK).toInstant().toEpochMilli();
	}

	@Test
	void regularHours() {
		assertFalse(MarketClock.open(ny(2026, 10, 21, 9, 29)));
		assertTrue(MarketClock.open(ny(2026, 10, 21, 9, 30)));
		assertTrue(MarketClock.open(ny(2026, 10, 21, 15, 59)));
		assertFalse(MarketClock.open(ny(2026, 10, 21, 16, 0)));
		assertFalse(MarketClock.open(ny(2026, 10, 24, 12, 0)), "Saturday");
	}

	@Test
	void holidaysAndEarlyCloses() {
		assertFalse(MarketClock.open(ny(2026, 11, 26, 12, 0)), "Thanksgiving");
		assertTrue(MarketClock.open(ny(2026, 11, 27, 12, 59)));
		assertFalse(MarketClock.open(ny(2026, 11, 27, 13, 0)), "day after Thanksgiving closes at 1pm");
		assertFalse(MarketClock.open(ny(2027, 3, 26, 11, 0)), "Good Friday 2027");
	}

	@Test
	void daylightSavingIsHandledByTheZone() {
		// 09:30 New York is 13:30 UTC in summer and 14:30 UTC in winter
		assertTrue(MarketClock.open(java.time.Instant.parse("2026-07-15T13:30:00Z").toEpochMilli()));
		assertFalse(MarketClock.open(java.time.Instant.parse("2026-12-15T13:30:00Z").toEpochMilli()));
		assertTrue(MarketClock.open(java.time.Instant.parse("2026-12-15T14:30:00Z").toEpochMilli()));
	}
}
