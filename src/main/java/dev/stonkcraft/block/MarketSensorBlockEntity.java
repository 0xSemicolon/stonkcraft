package dev.stonkcraft.block;

import com.google.gson.JsonObject;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.market.MarketData;
import dev.stonkcraft.registry.ModBlocks;
import dev.stonkcraft.signal.SignalNode;
import dev.stonkcraft.signal.SignalValue;
import dev.stonkcraft.trading.AccountMetric;
import dev.stonkcraft.trading.AccountStats;
import dev.stonkcraft.trading.Fmt;
import dev.stonkcraft.trading.TradingAccount;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/**
 * A Market Sensor: a reading (its tap, a signal in the registry, so a Signal Card can read it too) and a test that
 * turns the reading into redstone. The card slot holds the account card account readings use, or the Signal Card
 * the SIGNAL reading uses.
 */
public class MarketSensorBlockEntity extends HcBlockEntity {
	public String coin = "xyz:GOLD";
	public SensorReading reading = SensorReading.PRICE;
	public SensorTest test = SensorTest.AT_LEAST;
	public double a = 100_000;
	public double b = 0;
	/** Seconds: the Change % reading's window, and the Rises/Falls tests'. */
	public double window = 60;
	/** Test the reading's size, ignoring its sign. */
	public boolean abs;
	/** The account figure the ACCOUNT reading reads. */
	public AccountMetric metric = AccountMetric.PNL_DAY;
	/** (time, value) samples of the reading for Rises/Falls; server memory only. */
	private final java.util.ArrayDeque<double[]> history = new java.util.ArrayDeque<>();
	/** Human-readable live reading, synced to clients for the floating label. */
	public String readingText = "";

	private long lastReadingSync;
	/** So a pulse fires once per event, and not on load. */
	private final dev.stonkcraft.signal.EventWatch events = new dev.stonkcraft.signal.EventWatch();
	private long pulseUntil;

	public MarketSensorBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.SENSOR_BE, pos, state);
	}

	@Override
	public boolean usesCard() {
		return true;
	}

	@Override
	public String screen() {
		return "sensor";
	}

	@Override
	public boolean hasTap() {
		return true;
	}

	@Override
	protected void defineTap(SignalNode n) {
		n.op = reading.op;
		n.text = reading == SensorReading.ACCOUNT ? metric.name() : reading.coin ? coin : "";
		n.market = reading == SensorReading.ACCOUNT && (metric.perCoin || metric == AccountMetric.ORDERS) ? coin : "";
		n.a = reading == SensorReading.CHANGE ? Mth.clamp(window, 1, MarketData.HISTORY) : 0;
		n.b = 0;
		if (reading.card || reading == SensorReading.SIGNAL) tapInputFromCard(n);
		else n.inputs.clear();
		// a Signal Card reading passes through BLEND only from a Signal Card; an account card there means nothing
		if (reading == SensorReading.SIGNAL && signalRef() == null || reading.card && cardRef() == null) n.inputs.clear();
		n.name = tapName();
	}

	/** What a Signal Card printed from this sensor is called: "BTC price", "Treasury · PnL 24h". */
	private String tapName() {
		String c = coin.contains(":") ? coin.substring(coin.indexOf(':') + 1) : coin;
		String who = cardRef() == null ? "" : cardRef().name() + " · ";
		return switch (reading) {
			case ACCOUNT -> who + metric.label + (metric.perCoin ? " " + c : "");
			case SIGNAL -> signalRef() == null ? "Signal" : signalRef().name();
			case NYSE_OPEN, NYSE_BELL -> reading.label;
			default -> who + c + " " + reading.label.toLowerCase(java.util.Locale.ROOT);
		};
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, MarketSensorBlockEntity be) {
		if (level.getGameTime() % 10 != 0) return;
		if (level.getGameTime() % 20 == 0 || be.tapId == null) be.tap();
		int power = be.evaluate();
		if (power != state.getValue(BlockStateProperties.POWER)) {
			level.setBlock(pos, state.setValue(BlockStateProperties.POWER, power), Block.UPDATE_ALL);
			if (be.test != SensorTest.ANALOG && (power > 0) != (state.getValue(BlockStateProperties.POWER) > 0)) {
				level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.6f, power > 0 ? 1.6f : 0.8f);
			}
		}
		long now = System.currentTimeMillis();
		if (now - be.lastReadingSync > 2000) {
			be.lastReadingSync = now;
			String old = be.readingText;
			be.readingText = be.describe();
			if (!old.equals(be.readingText)) be.sync();
		}
	}

	@Override
	@Nullable
	public ItemStack acceptSignalCard(ItemStack one) {
		ItemStack old = swapCard(one);
		tap();
		return old;
	}

	@Override
	public ItemStack swapCard(ItemStack next) {
		ItemStack old = super.swapCard(next);
		history.clear();
		return old;
	}

	/** The reading as a number: a Signal Card's portfolio reads as gross leverage, or the coin's weight. */
	private double number(SignalValue v) {
		if (v.none()) return Double.NaN;
		double x = v.asNumber(reading == SensorReading.SIGNAL ? coin : "");
		return abs ? Math.abs(x) : x;
	}

	/** The reading's change over the window, NaN with no history yet (until then, against the oldest sample). */
	private double change(double now) {
		long t = System.currentTimeMillis();
		history.addLast(new double[]{t, now});
		while (history.size() > 2 && history.peekFirst()[0] < t - 3_600_000L) history.pollFirst();
		long since = t - (long) (Mth.clamp(window, 5, 3600) * 1000);
		double[] ref = null;
		for (double[] s : history) {
			if (s[0] <= since) ref = s;
			else break;
		}
		if (ref == null) ref = history.peekFirst();
		return ref == null ? Double.NaN : now - ref[1];
	}

	private int evaluate() {
		SignalValue v = tapValue();
		if (test == SensorTest.PULSE) {
			long now = System.currentTimeMillis();
			if (events.next(v)) pulseUntil = now + 1000;
			return now < pulseUntil ? 15 : 0;
		}
		double x = number(v);
		double ch = test.windowed() && !Double.isNaN(x) ? change(x) : Double.NaN;
		return test.power(x, a, b, ch);
	}

	private String describe() {
		if (reading == SensorReading.NYSE_OPEN || reading == SensorReading.NYSE_BELL) return dev.stonkcraft.game.MarketClock.describe(dev.stonkcraft.game.MarketClock.now());
		if (reading == SensorReading.SIGNAL) {
			if (signalRef() == null) return "insert a Signal Card";
			double x = number(tapValue());
			return Double.isNaN(x) ? "no value" : "value " + Fmt.size(x);
		}
		if (reading.card && cardRef() == null) return "insert a Watch or Trade Card";
		SignalValue v = tapValue();
		String s;
		if (reading == SensorReading.ACCOUNT) {
			TradingAccount acct = account();
			if (acct == null) return "account unavailable";
			s = metric.label + " " + new AccountStats(acct).brief(metric, coin);
		} else if (reading == SensorReading.FILL) {
			s = v instanceof SignalValue.Event e ? (e.at() <= 0 ? "no fills yet" : "last fill " + ago(e.at())) : "account unavailable";
		} else if (v.none()) {
			return switch (reading) {
				case SPREAD, BOOK_TILT -> "loading book";
				case POSITION_PNL -> "flat";
				default -> Stonkcraft.market() != null && Stonkcraft.market().ticker(coin) == null ? "unknown coin" : "--";
			};
		} else {
			double x = v.asNumber("");
			s = switch (reading) {
				case PRICE -> "$" + Fmt.price(x);
				case CHANGE -> Fmt.pct(x) + " / " + (int) Mth.clamp(window, 1, MarketData.HISTORY) + "s";
				case FUNDING -> String.format("%.1f%% APR", x);
				case POSITION -> x == 0 ? "flat" : (x > 0 ? "long " : "short ") + Fmt.usd(Math.abs(x));
				case SIDE -> x > 0 ? "long" : x < 0 ? "short" : "flat";
				case POSITION_PNL -> Fmt.usdSigned(x);
				case OPEN_ORDERS -> (int) x + " open";
				case SPREAD -> String.format("%.1f bps", x);
				case BOOK_TILT -> String.format("%.0f%% bids", x * 100);
				default -> Fmt.size(x);
			};
		}
		if (test.windowed() && history.peekFirst() != null) {
			double x = number(v);
			if (!Double.isNaN(x)) s += String.format(" (%+.2f)", x - history.peekFirst()[1]);
		}
		return s;
	}

	/** e.g. "BTC Price ≥ $100,000", "PnL 24h falls 1,000/600s", "◇ Whale gross 0.5→2", for labels and the screen. */
	public String condition() {
		String c = coin.contains(":") ? coin.substring(coin.indexOf(':') + 1) : coin;
		String subject = switch (reading) {
			case ACCOUNT -> metric.label + (metric.perCoin || metric == AccountMetric.ORDERS && !coin.isEmpty() ? " " + c : "");
			case SIGNAL -> "◇ " + (signalRef() == null ? "Signal" : signalRef().name());
			case NYSE_OPEN, NYSE_BELL -> "♪ " + reading.label;
			default -> c + " " + reading.label;
		};
		if (abs) subject += " (either side)";
		if (reading == SensorReading.SIDE && (test == SensorTest.AT_LEAST && a == 1 || test == SensorTest.AT_MOST && a == -1)) return c + (a > 0 ? " long?" : " short?");
		if (reading == SensorReading.NYSE_OPEN && test == SensorTest.AT_LEAST && a == 1) return subject;
		return subject + switch (test) {
			case AT_LEAST -> " ≥ " + amount(a);
			case AT_MOST -> " ≤ " + amount(a);
			case ANALOG -> " " + amount(a) + "→" + amount(b);
			case RISES -> " rises " + amount(Math.abs(a)) + "/" + (int) window + "s";
			case FALLS -> " falls " + amount(Math.abs(a)) + "/" + (int) window + "s";
			case PULSE -> "";
		};
	}

	private String amount(double v) {
		return switch (reading) {
			case PRICE -> "$" + Fmt.price(v);
			case POSITION, POSITION_PNL -> Fmt.usd(v);
			case CHANGE, FUNDING -> Fmt.size(v) + "%";
			case SPREAD -> Fmt.size(v) + "bps";
			default -> Fmt.size(v);
		};
	}

	private static String ago(long time) {
		long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
		return s < 60 ? s + "s ago" : s < 3600 ? (s / 60) + "m ago" : (s / 3600) + "h ago";
	}

	@Override
	public JsonObject configJson() {
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		o.addProperty("reading", reading.name());
		o.addProperty("test", test.name());
		o.addProperty("a", a);
		o.addProperty("b", b);
		o.addProperty("window", window);
		o.addProperty("abs", abs);
		o.addProperty("metric", metric.name());
		return o;
	}

	@Override
	public JsonObject screenJson() {
		JsonObject o = super.screenJson();
		o.addProperty("readingNow", readingText);
		return o;
	}

	@Override
	public void applyConfig(JsonObject json) {
		String oldCoin = coin;
		coin = str(json, "coin", coin).trim();
		String resolved = Stonkcraft.market() == null ? null : Stonkcraft.market().resolve(coin);
		if (resolved != null) coin = resolved;
		SensorReading r = SensorReading.parse(str(json, "reading", reading.name()));
		SensorTest t = SensorTest.parse(str(json, "test", test.name()));
		// an event only pulses, and only an event can
		if (r.event() != (t == SensorTest.PULSE)) t = r.event() ? SensorTest.PULSE : SensorTest.AT_LEAST;
		AccountMetric m = AccountMetric.parse(str(json, "metric", metric.name()));
		if (r != reading || m != metric || !coin.equals(oldCoin)) history.clear();
		reading = r;
		test = t;
		metric = m;
		a = num(json, "a", a);
		b = num(json, "b", b);
		window = Mth.clamp(num(json, "window", window), 1, 3600);
		if (json.has("abs")) abs = json.get("abs").getAsBoolean();
		readingText = "";
		tap();
		sync();
	}

	@Override
	protected void writeConfig(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putString("Coin", coin);
		tag.putString("Reading", reading.name());
		tag.putString("Test", test.name());
		tag.putDouble("A", a);
		tag.putDouble("B", b);
		tag.putDouble("Window", window);
		tag.putBoolean("Abs", abs);
		tag.putString("Metric", metric.name());
		tag.putString("ReadingText", readingText);
	}

	@Override
	protected void readConfig(CompoundTag tag, HolderLookup.Provider registries) {
		if (tag.contains("Coin")) coin = tag.getString("Coin");
		if (tag.contains("Metric")) metric = AccountMetric.parse(tag.getString("Metric"));
		// "Test" marks a sensor saved since the split (older sensors used "Reading" for their label text)
		if (tag.contains("Test")) {
			reading = SensorReading.parse(tag.getString("Reading"));
			test = SensorTest.parse(tag.getString("Test"));
			a = tag.getDouble("A");
			b = tag.getDouble("B");
			window = tag.getDouble("Window");
			abs = tag.getBoolean("Abs");
		} else if (tag.contains("Mode")) {
			// a sensor saved before readings and tests: its mode becomes the preset's setup
			SensorMode.Setup s = SensorMode.parse(tag.getString("Mode")).setup(tag.getDouble("A"), tag.getDouble("B"));
			reading = s.reading();
			test = s.test();
			a = s.a();
			b = s.b();
			window = s.window();
			abs = s.abs();
			if (s.metric() != null) metric = s.metric();
		}
		readingText = tag.getString("ReadingText");
	}
}
