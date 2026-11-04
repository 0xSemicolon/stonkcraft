package dev.stonkcraft.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.registry.ModBlocks;
import dev.stonkcraft.registry.ModComponents;
import dev.stonkcraft.trading.Account;
import dev.stonkcraft.trading.AccountMetric;
import dev.stonkcraft.trading.AccountRegistry;
import dev.stonkcraft.trading.Fmt;
import dev.stonkcraft.trading.TradingAccount;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Shows one figure of the inserted card's account. The server works the figure out (viewers may not have access
 * to the account themselves) and syncs ready-made text to clients for the renderer.
 */
public class AccountDisplayBlockEntity extends HcBlockEntity {
	public String coin = "";
	public AccountMetric metric = AccountMetric.EQUITY;
	public boolean spark = true;

	// display cache, synced to clients
	public String dName = "";
	public String dValue = "";
	public String dSub = "";
	/** "WATCH" for watch-only accounts; empty for paper. */
	public String dTag = "";
	/** 1 good, -1 bad, 0 neutral. */
	public int dTone;
	public float[] dSpark = new float[0];

	public AccountDisplayBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.ACCOUNT_DISPLAY_BE, pos, state);
	}

	@Override
	public boolean usesCard() {
		return true;
	}

	@Override
	public String screen() {
		return "account_display";
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, AccountDisplayBlockEntity be) {
		if (level.getGameTime() % 20 != 0) return;
		be.tap();
		be.refresh();
	}

	@Override
	public boolean hasTap() {
		return true;
	}

	/** The figure on the face; with a Signal Card in, that signal passed through. */
	@Override
	protected void defineTap(dev.stonkcraft.signal.SignalNode n) {
		tapInputFromCard(n);
		ModComponents.SignalRef sig = signalRef();
		if (sig != null) {
			n.op = dev.stonkcraft.signal.SignalOp.BLEND;
			n.text = "";
			n.market = "";
			n.name = sig.name();
			return;
		}
		n.op = dev.stonkcraft.signal.SignalOp.METRIC;
		n.text = metric.name();
		n.market = metric.perCoin || metric == AccountMetric.ORDERS ? coin : "";
		ModComponents.AccountRef ref = cardRef();
		String c = coin.contains(":") ? coin.substring(coin.indexOf(':') + 1) : coin;
		n.name = (ref == null ? "" : ref.name() + " · ") + metric.label + (metric.perCoin && !c.isEmpty() ? " " + c : "");
	}

	private void refresh() {
		ModComponents.AccountRef ref = cardRef();
		TradingAccount t = account();
		String name = ref == null ? "" : ref.name();
		String value, sub = "", tag = "";
		int tone = 0;
		float[] sp = new float[0];
		AccountRegistry reg = Stonkcraft.accounts();
		Account acct = ref == null || reg == null || ref.uuid() == null ? null : reg.get(ref.uuid());
		if (acct != null && acct.kind == Account.Kind.WATCH) tag = "WATCH";
		ModComponents.SignalRef sig = signalRef();
		if (sig != null) {
			name = sig.name();
			tag = "SIGNAL";
			dev.stonkcraft.signal.SignalValue v = signalValue();
			value = v.none() ? "no value" : dev.stonkcraft.signal.SignalEngine.describe(v);
			if (v instanceof dev.stonkcraft.signal.SignalValue.Portfolio p) {
				sub = String.format("gross %.2fx · net %+.2fx", p.gross(), p.net());
				tone = p.net() > 0 ? 1 : p.net() < 0 ? -1 : 0;
			} else if (v instanceof dev.stonkcraft.signal.SignalValue.Scalar s) {
				tone = s.value() > 0 ? 1 : s.value() < 0 ? -1 : 0;
			}
		} else if (ref == null) {
			value = "insert card";
		} else if (t == null) {
			value = "unavailable";
		} else {
			dev.stonkcraft.trading.AccountStats st = new dev.stonkcraft.trading.AccountStats(t);
			Object[] d = st.display(metric, coin);
			value = (String) d[0];
			sub = (String) d[1];
			tone = (int) d[2];
			JsonArray hist = st.snap.getAsJsonArray("equityHistory");
			if (spark && hist.size() > 1 && !metric.perCoin) {
				int n = Math.min(hist.size(), 40);
				sp = new float[n];
				for (int i = 0; i < n; i++) sp[i] = (float) hist.get(hist.size() - n + i).getAsDouble();
			}
		}
		boolean changed = !name.equals(dName) || !tag.equals(dTag) || !value.equals(dValue) || !sub.equals(dSub) || tone != dTone || !Arrays.equals(sp, dSpark);
		dName = name;
		dTag = tag;
		dValue = value;
		dSub = sub;
		dTone = tone;
		dSpark = sp;
		if (changed) sync();
	}

	@Override
	@Nullable
	public ItemStack acceptSignalCard(ItemStack one) {
		ItemStack old = swapCard(one);
		refresh();
		return old;
	}

	@Override
	public JsonObject configJson() {
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		o.addProperty("metric", metric.name());
		o.addProperty("spark", spark);
		return o;
	}

	/** Adds what the face shows right now, for the screen's preview. */
	@Override
	public JsonObject screenJson() {
		JsonObject o = super.screenJson();
		o.addProperty("faceMetric", metric.name());
		o.addProperty("faceValue", dValue);
		o.addProperty("faceSub", dSub);
		o.addProperty("faceTone", dTone);
		return o;
	}

	@Override
	public void applyConfig(JsonObject json) {
		coin = str(json, "coin", coin).trim();
		String resolved = coin.isEmpty() || Stonkcraft.market() == null ? null : Stonkcraft.market().resolve(coin);
		if (resolved != null) coin = resolved;
		metric = AccountMetric.parse(str(json, "metric", metric.name()));
		if (json.has("spark")) spark = json.get("spark").getAsBoolean();
		tap();
		refresh();
		sync();
	}

	@Override
	protected void writeConfig(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putString("Coin", coin);
		tag.putString("Metric", metric.name());
		tag.putBoolean("Spark", spark);
		tag.putString("DName", dName);
		tag.putString("DTag", dTag);
		tag.putString("DValue", dValue);
		tag.putString("DSub", dSub);
		tag.putInt("DTone", dTone);
		ListTag list = new ListTag();
		for (float f : dSpark) list.add(FloatTag.valueOf(f));
		tag.put("DSpark", list);
	}

	@Override
	protected void readConfig(CompoundTag tag, HolderLookup.Provider registries) {
		if (tag.contains("Coin")) coin = tag.getString("Coin");
		if (tag.contains("Metric")) metric = AccountMetric.parse(tag.getString("Metric"));
		if (tag.contains("Spark")) spark = tag.getBoolean("Spark");
		dName = tag.getString("DName");
		dTag = tag.getString("DTag");
		dValue = tag.getString("DValue");
		dSub = tag.getString("DSub");
		dTone = tag.getInt("DTone");
		ListTag list = tag.getList("DSpark", Tag.TAG_FLOAT);
		dSpark = new float[list.size()];
		for (int i = 0; i < list.size(); i++) dSpark[i] = list.getFloat(i);
	}
}
