package dev.hypercraft.unit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.hypercraft.market.MarketData;
import dev.hypercraft.trading.Fmt;
import dev.hypercraft.trading.PaperAccount;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * UNIT.XYZ: turns metal into account equity and back. Deposits credit a Unit token's spot balance, or a fully
 * collateralised long on the token's reference perp; withdrawals take metal back out of either, a long prorated by
 * its leverage (see {@link PaperAccount#withdrawLong}). A fee ({@link UnitConfig#feeBps}) applies each way.
 */
public final class UnitBridge {
	private UnitBridge() {}

	/** Where bridged metal lands. */
	public enum Mode {
		SPOT("Spot balance"), LONG("1x long");

		public final String label;

		Mode(String label) {
			this.label = label;
		}

		public static Mode parse(@Nullable String s) {
			try {
				return s == null ? SPOT : valueOf(s);
			} catch (IllegalArgumentException e) {
				return SPOT;
			}
		}
	}

	/** @param units of the token moved; usd its value at the time */
	public record Result(boolean ok, String message, double units, double usd, List<ItemStack> items) {
		static Result fail(String why) {
			return new Result(false, why, 0, 0, List.of());
		}
	}

	public static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	/** Lets the config look items up in the game's item tags, once they're loaded. */
	public static void bindTags() {
		UnitConfig.inTag = (item, tag) -> {
			ResourceLocation id = ResourceLocation.tryParse(item), t = ResourceLocation.tryParse(tag);
			return id != null && t != null && BuiltInRegistries.ITEM.get(id).builtInRegistryHolder()
					.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, t));
		};
		UnitConfig.tagItems = tag -> {
			ResourceLocation t = ResourceLocation.tryParse(tag);
			if (t == null) return List.of();
			List<String> out = new ArrayList<>();
			BuiltInRegistries.ITEM.getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, t))
					.ifPresent(set -> set.forEach(h -> out.add(BuiltInRegistries.ITEM.getKey(h.value()).toString())));
			return out;
		};
	}

	public static Item item(String itemId) {
		return BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
	}

	/** Units of its token one of these items is worth, or 0 if no bridge takes it. */
	public static double unitsPer(ItemStack stack) {
		UnitConfig.Token t = UnitConfig.get().tokenFor(itemId(stack));
		return t == null ? 0 : t.per(itemId(stack));
	}

	public static boolean bridgeable(ItemStack stack) {
		return !stack.isEmpty() && unitsPer(stack) > 0;
	}

	/** Live price of one unit of the token, NaN while unknown. */
	public static double price(@Nullable MarketData md, UnitConfig.Token t) {
		if (md == null) return Double.NaN;
		double px = md.mid(t.priceMarket);
		return px > 0 ? px : Double.NaN;
	}

	/** Deposits {@code count} of one item. The caller has already taken the items and gives them back on failure. */
	public static Result deposit(PaperAccount acct, @Nullable MarketData md, String itemId, long count, Mode mode) {
		UnitConfig cfg = UnitConfig.get();
		UnitConfig.Token t = cfg.tokenFor(itemId);
		if (t == null || count <= 0) return Result.fail("A UNIT.XYZ doesn't take that");
		double px = price(md, t);
		if (Double.isNaN(px)) return Result.fail("No " + t.token + " price yet (" + t.priceMarket + ")");
		double units = t.per(itemId) * count * (1 - cfg.fee());
		String where;
		if (mode == Mode.LONG) {
			MarketData.Asset perp = md.asset(t.priceMarket);
			if (perp == null || perp.spot()) return Result.fail(t.priceMarket + " isn't a perp market");
			acct.depositLong(perp.name(), units, px);
			where = "a long on " + perp.name();
		} else {
			acct.creditSpot(t.market(), units, px);
			where = t.token;
		}
		return new Result(true, String.format("Bridged %s %s of %s in as %s (%s)", Fmt.size(units), t.unit, t.token.substring(1).toLowerCase(),
				where, Fmt.usd(units * px)), units, units * px, List.of());
	}

	/** How many units can come out of the account as metal right now, before the fee. */
	public static double available(PaperAccount acct, @Nullable MarketData md, UnitConfig.Token t, Mode mode) {
		double px = price(md, t);
		if (Double.isNaN(px)) return 0;
		if (mode == Mode.LONG) {
			MarketData.Asset perp = md.asset(t.priceMarket);
			return perp == null ? 0 : acct.longWithdrawable(perp.name(), px);
		}
		return acct.spotBalance(t.market());
	}

	/**
	 * Withdraws up to {@code units} as items, made up greedily from the token's withdraw list (blocks, then ingots,
	 * then nuggets). The account pays the delivered amount plus the fee; anything smaller than the smallest item stays.
	 */
	public static Result withdraw(PaperAccount acct, @Nullable MarketData md, UnitConfig.Token t, Mode mode, double units) {
		UnitConfig cfg = UnitConfig.get();
		double px = price(md, t);
		if (Double.isNaN(px)) return Result.fail("No " + t.token + " price yet");
		double fee = 1 + cfg.fee();
		double avail = available(acct, md, t, mode);
		double want = Math.min(units, avail / fee);
		List<long[]> plan = new ArrayList<>();
		double remaining = want;
		double delivered = 0;
		for (int i = 0; i < t.withdraw.size(); i++) {
			double per = t.per(t.withdraw.get(i));
			if (per <= 0) continue;
			long n = (long) Math.floor(remaining / per + 1e-9);
			if (n <= 0) continue;
			plan.add(new long[]{i, n});
			remaining -= n * per;
			delivered += n * per;
		}
		if (delivered <= 0) {
			return Result.fail(avail <= 0 ? "Nothing to withdraw: no " + (mode == Mode.LONG ? "long on " + t.priceMarket : t.token + " balance")
					: "Less than one " + shortName(t.withdraw.get(t.withdraw.size() - 1)) + " available (" + Fmt.size(avail) + " " + t.unit + ")");
		}
		double debit = Math.min(avail, delivered * fee);
		String err;
		if (mode == Mode.LONG) {
			err = acct.withdrawLong(md.asset(t.priceMarket).name(), debit, px);
		} else {
			err = acct.debitSpot(t.market(), debit, px);
		}
		if (err != null) return Result.fail(err);
		List<ItemStack> items = new ArrayList<>();
		StringBuilder what = new StringBuilder();
		for (long[] p : plan) {
			Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(t.withdraw.get((int) p[0])));
			long n = p[1];
			if (what.length() > 0) what.append(", ");
			what.append(n).append(' ').append(shortName(t.withdraw.get((int) p[0])));
			while (n > 0) {
				int take = (int) Math.min(item.getDefaultMaxStackSize(), n);
				items.add(new ItemStack(item, take));
				n -= take;
			}
		}
		return new Result(true, "Withdrew " + what + " (" + Fmt.size(delivered) + " " + t.unit + ", " + Fmt.usd(delivered * px) + ")", delivered,
				delivered * px, items);
	}

	/** "minecraft:gold_ingot" -> "gold ingot". */
	public static String shortName(String itemId) {
		String n = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
		return n.replace('_', ' ');
	}

	/** The bridge's screen: one row per token with its price, balance, long and what can come out. */
	public static JsonArray rows(@Nullable PaperAccount acct, @Nullable MarketData md) {
		JsonArray out = new JsonArray();
		for (UnitConfig.Token t : UnitConfig.get().tokens) {
			JsonObject o = new JsonObject();
			o.addProperty("token", t.token);
			o.addProperty("market", t.priceMarket);
			o.addProperty("unit", t.unit);
			o.addProperty("note", t.note == null ? "" : t.note);
			double px = price(md, t);
			o.addProperty("price", Double.isNaN(px) ? 0 : px);
			if (acct != null) {
				o.addProperty("spot", acct.spotBalance(t.market()));
				MarketData.Asset perp = md == null ? null : md.asset(t.priceMarket);
				o.addProperty("long", perp == null ? 0 : Math.max(0, acct.positionSize(perp.name())));
				o.addProperty("lev", perp == null ? 0 : acct.leverage(perp.name()));
				o.addProperty("spotOut", available(acct, md, t, Mode.SPOT));
				o.addProperty("longOut", available(acct, md, t, Mode.LONG));
			}
			JsonArray items = new JsonArray();
			// tags as the items in them: the screen counts what you carry by id
			t.expanded().forEach((id, per) -> {
				JsonArray row = new JsonArray();
				row.add(id);
				row.add(per);
				items.add(row);
			});
			o.add("items", items);
			JsonArray wd = new JsonArray();
			for (String id : t.withdraw) wd.add(id);
			o.add("withdraw", wd);
			out.add(o);
		}
		return out;
	}
}
