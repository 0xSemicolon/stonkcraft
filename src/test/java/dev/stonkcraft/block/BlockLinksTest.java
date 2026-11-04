package dev.stonkcraft.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlockLinksTest {
	/** Positions on a line: the blocks at the given places, V a vault, anything else a Stonkcraft block. */
	private static Integer search(String row, int from) {
		return BlockLinks.nearest(from, p -> List.of(p - 1, p + 1),
				p -> p >= 0 && p < row.length() && row.charAt(p) == 'V',
				p -> p >= 0 && p < row.length() && row.charAt(p) != ' ');
	}

	@Test
	void aRowLinksThroughTheBlockBetween() {
		// [ account block ] [ UNIT[XYZ] ] [ trading terminal ]
		assertEquals(0, search("VUT", 1));
		assertEquals(0, search("VUT", 2));
	}

	@Test
	void aGapBreaksTheLink() {
		assertNull(search("VU T", 3));
		assertEquals(0, search("VU T", 1));
	}

	@Test
	void theNearestVaultWinsAndAVaultEndsTheChain() {
		assertEquals(4, search("VUTUV", 3));
		assertEquals(0, search("VUTUV", 1));
		// the second vault's block never reaches past it to the first
		assertEquals(2, search("VUVT", 3));
	}

	@Test
	void noVaultNoLink() {
		assertNull(search("UTU", 1));
	}

	@Test
	void anyShapeLinks() {
		Map<String, List<String>> touching = Map.of("terminal", List.of("unit"), "unit", List.of("terminal", "vault"), "vault", List.of("unit"));
		assertEquals("vault", BlockLinks.nearest("terminal", touching::get, "vault"::equals, Set.of("unit", "terminal")::contains));
	}

	@Test
	void aHugeBuildStopsLooking() {
		String longRow = "V" + "U".repeat(BlockLinks.MAX_BLOCKS + 10);
		assertNull(search(longRow, longRow.length() - 1));
		assertEquals(0, search("V" + "U".repeat(10), 10));
	}
}
