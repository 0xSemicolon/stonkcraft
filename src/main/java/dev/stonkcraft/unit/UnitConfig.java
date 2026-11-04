package dev.stonkcraft.unit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.stonkcraft.Stonkcraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Which items a Commodity Exchange takes, at config/stonkcraft-exchange.json. Each token is a Stonkcraft-only spot asset
 * ("UGOLD") priced at a reference market's mid ("xyz:GOLD"); each item counts for some amount of the token, in the
 * token's unit (troy ounces of gold, pounds of copper). Server owners can add their own pegs.
 */
public final class UnitConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public String _readme = "Items a Commodity Exchange accepts. Each token is priced at priceMarket's mid; items count for that many units. "
			+ "Raw metal and ore count for 90% of the refined amount. withdraw lists what comes out, largest first.";
	/** Bridge fee in basis points, charged on deposits and on withdrawals. */
	public int feeBps = 25;
	/** Defaults this file was last brought up to; 0 for files written when it took only gold and copper. */
	public int version;
	static final int VERSION = 5;
	/**
	 * Whether an item id is in an item tag ("c:ingots/silver"), and the items in one. The game sets these once tags
	 * are loaded; until then (and in tests, unless a test sets them) no item is in any tag.
	 */
	public static java.util.function.BiPredicate<String, String> inTag = (item, tag) -> false;
	public static java.util.function.Function<String, List<String>> tagItems = tag -> List.of();
	public List<Token> tokens = new ArrayList<>();

	public static final class Token {
		public String token = "";
		public String priceMarket = "";
		public String unit = "";
		/** Item id, or "#" and an item tag ("#c:ingots/gold"), -> units of the token per item. */
		public Map<String, Double> items = new LinkedHashMap<>();
		/** Item ids handed out on withdrawal, largest first. Each must be in {@link #items}. */
		public List<String> withdraw = new ArrayList<>();
		/** Why it's priced this way, for the bridge screen: "Minecraft has no silver, so...". */
		public String note = "";

		/** The spot market name, "UGOLD/USDC". */
		public String market() {
			return token + "/USDC";
		}

		/** Units per item: its own entry, else the first tag it's in. */
		public double per(String itemId) {
			Double v = items.get(itemId);
			if (v != null) return v;
			for (Map.Entry<String, Double> e : items.entrySet()) if (e.getKey().startsWith("#") && inTag.test(itemId, e.getKey().substring(1))) return e.getValue();
			return 0;
		}

		/** The items a tag entry stands for that aren't listed by id, for screens that only know ids. */
		public Map<String, Double> expanded() {
			Map<String, Double> out = new LinkedHashMap<>();
			items.forEach((k, v) -> {
				if (!k.startsWith("#")) out.put(k, v);
			});
			items.forEach((k, v) -> {
				if (k.startsWith("#")) for (String id : tagItems.apply(k.substring(1))) out.putIfAbsent(id, v);
			});
			return out;
		}
	}

	private static UnitConfig instance = defaults();

	public static UnitConfig get() {
		return instance;
	}

	public double fee() {
		return Math.max(0, Math.min(1000, feeBps)) / 10_000.0;
	}

	/** The token an item belongs to, or null if no bridge takes it. An item listed by id wins over a tag, in any token. */
	@Nullable
	public Token tokenFor(String itemId) {
		for (Token t : tokens) {
			Double v = t.items.get(itemId);
			if (v != null && v > 0) return t;
		}
		for (Token t : tokens) if (t.per(itemId) > 0) return t;
		return null;
	}

	@Nullable
	public Token token(String name) {
		for (Token t : tokens) if (t.token.equalsIgnoreCase(name) || t.market().equalsIgnoreCase(name)) return t;
		return null;
	}

	/** Token name -> reference market, for {@link dev.stonkcraft.market.MarketData#setUnits}. */
	public Map<String, String> markets() {
		Map<String, String> out = new LinkedHashMap<>();
		for (Token t : tokens) if (!t.token.isBlank() && !t.priceMarket.isBlank()) out.put(t.token, t.priceMarket);
		return out;
	}

	/**
	 * Twelve pegs. trade[xyz] only lists gold and copper of what Minecraft has, so the rest are priced by the market
	 * that feels closest: iron is silver, a diamond palladium, redstone chip stocks, lava crude oil. Each token's note
	 * says why, on the bridge screen.
	 */
	public static UnitConfig defaults() {
		UnitConfig c = new UnitConfig();
		c.tokens.addAll(List.of(
				// metals
				tok("UGOLD", "xyz:GOLD", "oz", "Gold is gold: an ingot is a troy ounce.",
						items("gold_nugget", 1 / 9.0, "gold_ingot", 1, "gold_block", 9, "raw_gold", 0.9, "raw_gold_block", 8.1,
								"gold_ore", 0.9, "deepslate_gold_ore", 0.9, "nether_gold_ore", 0.4),
						"gold_block", "gold_ingot", "gold_nugget"),
				tok("UCOPPER", "xyz:COPPER", "lb", "Copper is copper: an ingot is a pound.",
						items("copper_ingot", 1, "copper_block", 9, "raw_copper", 0.9, "raw_copper_block", 8.1, "copper_ore", 0.9,
								"deepslate_copper_ore", 0.9),
						"copper_block", "copper_ingot"),
				tok("UIRON", "xyz:SILVER", "oz", "Minecraft has no silver, so its everyday metal stands in: an ingot is an ounce of silver.",
						items("iron_nugget", 1 / 9.0, "iron_ingot", 1, "iron_block", 9, "raw_iron", 0.9, "raw_iron_block", 8.1,
								"iron_ore", 0.9, "deepslate_iron_ore", 0.9),
						"iron_block", "iron_ingot", "iron_nugget"),
				// an ingot is four scrap plus four gold; it comes back out as scrap, since the gold can't be paid back as platinum
				tok("UNETHERITE", "xyz:PLATINUM", "oz", "The rarest metal, priced as platinum: a scrap is an ounce. Comes back out as scrap.",
						items("netherite_scrap", 1, "ancient_debris", 0.9, "netherite_ingot", 4, "netherite_block", 36),
						"netherite_scrap"),
				// gems
				tok("UDIAMOND", "xyz:PALLADIUM", "oz", "The gem everyone mines for, priced as the precious metal left over: a diamond is an ounce of palladium.",
						items("diamond", 1, "diamond_block", 9, "diamond_ore", 0.9, "deepslate_diamond_ore", 0.9),
						"diamond_block", "diamond"),
				tok("UEMERALD", "xyz:EUR", "EUR", "Villagers' money, so a foreign currency: an emerald is ten euros.",
						items("emerald", 10, "emerald_block", 90, "emerald_ore", 9, "deepslate_emerald_ore", 9),
						"emerald_block", "emerald"),
				// circuitry
				tok("UREDSTONE", "xyz:SMH", "sh", "Minecraft's circuitry, priced as chip stocks: a dust is a hundredth of a share of SMH.",
						items("redstone", 0.01, "redstone_block", 0.09, "redstone_ore", 0.04, "deepslate_redstone_ore", 0.04),
						"redstone_block", "redstone"),
				// a quartz block can't be split again, so quartz comes back out loose
				tok("UQUARTZ", "xyz:DRAM", "sh", "The silicon in comparators and observers, priced as memory chips: a quartz is a tenth of a share of DRAM.",
						items("quartz", 0.1, "quartz_block", 0.4, "nether_quartz_ore", 0.09),
						"quartz"),
				tok("UGLOWSTONE", "xyz:URNM", "sh", "It glows, so it's priced as uranium miners: a dust is a tenth of a share of URNM.",
						items("glowstone_dust", 0.1, "glowstone", 0.4),
						"glowstone_dust"),
				// fuel
				tok("UCOAL", "xyz:NATGAS", "MMBtu", "Fuel for fuel: a coal is a million BTU of natural gas.",
						items("coal", 1, "charcoal", 1, "coal_block", 9, "coal_ore", 0.9, "deepslate_coal_ore", 0.9),
						"coal_block", "coal"),
				tok("ULAVA", "xyz:CL", "bbl", "Black gold comes by the bucket: a lava bucket is a barrel of crude. You keep the bucket.",
						items("lava_bucket", 1),
						"lava_bucket"),
				tok("UBLAZE", "xyz:HO", "gal", "The hottest fuel there is, priced as heating oil: a blaze rod is a gallon.",
						items("blaze_rod", 1, "blaze_powder", 0.5),
						"blaze_rod")));
		for (Token t : c.tokens) addTags(t);
		c.version = VERSION;
		return c;
	}

	/**
	 * Common tags ("c" namespace) beside the vanilla ids they hold, so other mods' variants count too. In a holder
	 * class: defaults() runs from the static instance field, before later static fields are set.
	 */
	private static final class Common {
		static final Map<String, String> TAGS = Map.of(
				"minecraft:gold_ingot", "#c:ingots/gold", "minecraft:copper_ingot", "#c:ingots/copper", "minecraft:iron_ingot", "#c:ingots/iron",
				"minecraft:diamond", "#c:gems/diamond", "minecraft:emerald", "#c:gems/emerald", "minecraft:netherite_ingot", "#c:ingots/netherite",
				"minecraft:redstone", "#c:dusts/redstone", "minecraft:quartz", "#c:gems/quartz", "minecraft:glowstone_dust", "#c:dusts/glowstone",
				"minecraft:blaze_rod", "#c:rods/blaze");
	}

	/** Adds each common tag a token's vanilla item belongs to, at that item's amount, unless the token has it. */
	private static void addTags(Token t) {
		Common.TAGS.forEach((id, tag) -> {
			Double per = t.items.get(id);
			if (per != null && !t.items.containsKey(tag)) t.items.put(tag, per);
		});
	}

	/**
	 * Metals other mods add that Minecraft doesn't have, each priced by a market a default token already uses. They
	 * aren't written to the config: {@link #addModMetals} adds one only when some mod puts items in its tag.
	 */
	static List<Token> modMetals() {
		Token silver = tok("USILVER", "xyz:SILVER", "oz", "Silver from a mod is silver: an ingot is an ounce.", Map.of("#c:ingots/silver", 1.0));
		Token platinum = tok("UPLATINUM", "xyz:PLATINUM", "oz", "Platinum from a mod is platinum: an ingot is an ounce.", Map.of("#c:ingots/platinum", 1.0));
		Token uranium = tok("UURANIUM", "xyz:URNM", "sh", "Uranium from a mod, priced as uranium miners: an ingot is a share of URNM.", Map.of("#c:ingots/uranium", 1.0));
		return List.of(silver, platinum, uranium);
	}

	/** Adds the mod metals whose tag has items, withdrawn as the tag's first item. Returns the ones added. */
	public List<String> addModMetals() {
		List<String> added = new ArrayList<>();
		for (Token t : modMetals()) {
			if (token(t.token) != null) continue;
			String tag = t.items.keySet().iterator().next().substring(1);
			List<String> members = tagItems.apply(tag);
			if (members.isEmpty()) continue;
			t.withdraw.add(members.get(0));
			tokens.add(t);
			added.add(t.token + " (" + tag + ")");
		}
		return added;
	}

	private static Token tok(String name, String market, String unit, String note, Map<String, Double> items, String... withdraw) {
		Token t = new Token();
		t.token = name;
		t.priceMarket = market;
		t.unit = unit;
		t.note = note;
		t.items.putAll(items);
		for (String w : withdraw) t.withdraw.add("minecraft:" + w);
		return t;
	}

	/** Pairs of vanilla item path and units per item. */
	private static Map<String, Double> items(Object... pairs) {
		Map<String, Double> out = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) out.put("minecraft:" + pairs[i], ((Number) pairs[i + 1]).doubleValue());
		return out;
	}

	/** Brings an older config up to date: tokens added to the defaults since it was written are appended. */
	void upgrade() {
		if (version >= VERSION) return;
		List<Token> defaults = defaults().tokens;
		// only tokens newer than the file: one an owner removed after its version stays removed
		for (Token d : defaults) if (introduced(d.token) > version && token(d.token) == null) tokens.add(d);
		// notes came later than some tokens; an owner's own note stays
		for (Token d : defaults) {
			Token t = token(d.token);
			if (t != null && (t.note == null || t.note.isBlank()) && t.priceMarket.equals(d.priceMarket)) t.note = d.note;
		}
		// version 5 added common tags beside vanilla ids, at whatever amount the owner gave the id
		if (version < 5) for (Token t : tokens) addTags(t);
		version = VERSION;
	}

	/** The config version a default token first shipped in. */
	private static int introduced(String token) {
		return switch (token) {
			case "UIRON", "UNETHERITE", "UCOAL" -> 2;
			case "UDIAMOND" -> 3;
			case "UEMERALD", "UREDSTONE", "UQUARTZ", "UGLOWSTONE", "ULAVA", "UBLAZE" -> 4;
			default -> 1;
		};
	}
	/** A token's everyday name: "UGLOWSTONE" -> "glowstone". */
	public static String name(Token t) {
		return (t.token.startsWith("U") ? t.token.substring(1) : t.token).toLowerCase(java.util.Locale.ROOT);
	}

	/** Names of what a bridge takes, for messages: "gold, copper, iron, diamond, netherite or coal". */
	public String accepted() {
		List<String> names = new ArrayList<>();
		for (Token t : tokens) names.add((t.token.startsWith("U") ? t.token.substring(1) : t.token).toLowerCase(java.util.Locale.ROOT));
		if (names.size() < 2) return String.join("", names);
		return String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1);
	}

	public static Path path() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("stonkcraft-exchange.json");
	}

	public static void load() {
		Path path = path();
		try {
			if (Files.exists(path)) {
				UnitConfig loaded = GSON.fromJson(Files.readString(path), UnitConfig.class);
				if (loaded != null && loaded.tokens != null) {
					loaded.upgrade();
					instance = loaded;
				}
			}
			Files.writeString(path, GSON.toJson(instance));
		} catch (Exception e) {
			Stonkcraft.LOG.error("Failed to load {}, using defaults", path, e);
			instance = defaults();
		}
	}
}
