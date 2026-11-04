package dev.stonkcraft.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class CoinsTest {
	@Test
	void splitsIntoFewestCoins() {
		assertArrayEquals(new long[]{0, 0, 0}, Coins.split(0));
		assertArrayEquals(new long[]{0, 0, 99}, Coins.split(99));
		assertArrayEquals(new long[]{0, 1, 0}, Coins.split(100));
		assertArrayEquals(new long[]{1, 2, 34}, Coins.split(10_234));
		assertArrayEquals(new long[]{10, 0, 0}, Coins.split(100_000));
	}
}
