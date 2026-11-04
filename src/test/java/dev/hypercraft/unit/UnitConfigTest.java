package dev.hypercraft.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UnitConfigTest {
	@Test
	void defaultsBridgeTwelveGoods() {
		UnitConfig c = UnitConfig.defaults();
		assertEquals(List.of("UGOLD", "UCOPPER", "UIRON", "UNETHERITE", "UDIAMOND", "UEMERALD", "UREDSTONE", "UQUARTZ", "UGLOWSTONE",
				"UCOAL", "ULAVA", "UBLAZE"), c.tokens.stream().map(t -> t.token).toList());
		assertEquals("UIRON", c.tokenFor("minecraft:iron_ingot").token);
		assertEquals("xyz:SILVER", c.token("UIRON").priceMarket);
		assertEquals(9.0, c.token("UCOAL").per("minecraft:coal_block"), 1e-9);
		assertEquals(4.0, c.token("UNETHERITE").per("minecraft:netherite_ingot"), 1e-9);
		assertEquals(10.0, c.token("UEMERALD").per("minecraft:emerald"), 1e-9);
		assertEquals("ULAVA", c.tokenFor("minecraft:lava_bucket").token);
		assertEquals("glowstone", UnitConfig.name(c.token("UGLOWSTONE")));
		Set<String> seen = new HashSet<>();
		for (UnitConfig.Token t : c.tokens) {
			assertFalse(t.note.isBlank(), t.token + " says why it's priced the way it is");
			// every withdrawal item is one the bridge also takes in, and no item belongs to two tokens
			for (String w : t.withdraw) assertTrue(t.per(w) > 0, t.token + " withdraws " + w);
			for (String id : t.items.keySet()) assertTrue(seen.add(id), id + " is in two tokens");
		}
	}

	@Test
	void oldConfigGainsNewTokensButKeepsItsOwn() {
		UnitConfig old = UnitConfig.defaults();
		old.version = 0;
		old.tokens.removeIf(t -> !t.token.equals("UGOLD") && !t.token.equals("UCOPPER"));
		old.token("UGOLD").note = "";
		old.feeBps = 40;
		old.upgrade();
		assertEquals(12, old.tokens.size());
		assertNotNull(old.token("UCOAL"));
		assertFalse(old.token("UGOLD").note.isBlank(), "a note-less old token gains the default note");
		assertEquals(40, old.feeBps);
		assertEquals(UnitConfig.VERSION, old.version);
	}

	@Test
	void versionTwoConfigGainsOnlyLaterTokens() {
		UnitConfig v2 = UnitConfig.defaults();
		v2.version = 2;
		v2.tokens.removeIf(t -> t.token.equals("UDIAMOND") || t.token.equals("UCOAL") || t.token.equals("ULAVA"));
		v2.upgrade();
		assertNotNull(v2.token("UDIAMOND"));
		assertNotNull(v2.token("ULAVA"));
		assertNull(v2.token("UCOAL"));
	}

	@Test
	void upToDateConfigKeepsRemovals() {
		UnitConfig c = UnitConfig.defaults();
		c.tokens.removeIf(t -> t.token.equals("UCOAL"));
		c.upgrade();
		assertEquals(11, c.tokens.size());
	}

	/** A mod's silver ingot and a mod's gold ingot in the common tags, and a vanilla gold ingot in the gold tag too. */
	private static void modTags() {
		UnitConfig.inTag = (item, tag) -> switch (tag) {
			case "c:ingots/gold" -> item.equals("minecraft:gold_ingot") || item.equals("othermod:gold_ingot");
			case "c:ingots/silver" -> item.equals("othermod:silver_ingot");
			default -> false;
		};
		UnitConfig.tagItems = tag -> switch (tag) {
			case "c:ingots/gold" -> List.of("minecraft:gold_ingot", "othermod:gold_ingot");
			case "c:ingots/silver" -> List.of("othermod:silver_ingot");
			default -> List.of();
		};
	}

	@AfterEach
	void noTags() {
		UnitConfig.inTag = (item, tag) -> false;
		UnitConfig.tagItems = tag -> List.of();
	}

	@Test
	void anItemInACommonTagCountsAsTheVanillaOne() {
		modTags();
		UnitConfig c = UnitConfig.defaults();
		assertEquals("UGOLD", c.tokenFor("othermod:gold_ingot").token);
		assertEquals(1.0, c.token("UGOLD").per("othermod:gold_ingot"), 1e-9);
		assertNull(c.tokenFor("othermod:tin_ingot"));
		// screens only know ids: the tag shows up as the items in it
		assertTrue(c.token("UGOLD").expanded().containsKey("othermod:gold_ingot"));
		assertFalse(c.token("UGOLD").expanded().keySet().stream().anyMatch(k -> k.startsWith("#")));
	}

	@Test
	void anItemListedByIdWinsOverATag() {
		modTags();
		UnitConfig c = UnitConfig.defaults();
		// an owner lists the mod's gold ingot under copper by id: that wins over gold's tag
		c.token("UCOPPER").items.put("othermod:gold_ingot", 3.0);
		assertEquals("UCOPPER", c.tokenFor("othermod:gold_ingot").token);
		assertEquals("UGOLD", c.tokenFor("minecraft:gold_ingot").token);
	}

	@Test
	void versionFourGainsTagsAtTheOwnersAmounts() {
		UnitConfig v4 = UnitConfig.defaults();
		v4.version = 4;
		for (UnitConfig.Token t : v4.tokens) t.items.keySet().removeIf(k -> k.startsWith("#"));
		v4.token("UEMERALD").items.put("minecraft:emerald", 12.0);
		v4.upgrade();
		assertEquals(12.0, v4.token("UEMERALD").items.get("#c:gems/emerald"), 1e-9);
		assertEquals(12.0, v4.token("UEMERALD").per("minecraft:emerald"), 1e-9);
		assertEquals(1.0, v4.token("UGOLD").items.get("#c:ingots/gold"), 1e-9);
		assertEquals(UnitConfig.VERSION, v4.version);
	}

	@Test
	void modMetalsJoinOnlyWhenAModAddsThem() {
		UnitConfig plain = UnitConfig.defaults();
		assertEquals(List.of(), plain.addModMetals());
		modTags();
		UnitConfig c = UnitConfig.defaults();
		assertEquals(List.of("USILVER (c:ingots/silver)"), c.addModMetals());
		assertEquals("USILVER", c.tokenFor("othermod:silver_ingot").token);
		assertEquals(List.of("othermod:silver_ingot"), c.token("USILVER").withdraw);
		// already there: not added twice
		assertEquals(List.of(), c.addModMetals());
	}
}
