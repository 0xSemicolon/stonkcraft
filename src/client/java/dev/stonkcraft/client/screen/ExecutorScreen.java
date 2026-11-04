package dev.stonkcraft.client.screen;

import com.google.gson.JsonObject;
import dev.stonkcraft.block.ExecutorInput;
import dev.stonkcraft.block.TradeExecutorBlockEntity.Repeat;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.HcButton;
import dev.stonkcraft.client.ui.HcDropdown;
import dev.stonkcraft.client.ui.HcField;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.trading.Fmt;
import dev.stonkcraft.trading.OrderRequest;
import dev.stonkcraft.trading.Side;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Every order option the terminal has, configured once and fired by redstone. The Inputs tab lets other circuits
 * steer size, leverage, price, TP/SL and TWAP length through the signal strength on a chosen face. The everyday
 * settings show first; TP/SL, leverage and timing sit under "More settings".
 */
public class ExecutorScreen extends BlockConfigScreen {
	private static final Side[] SIDES = {Side.BUY, Side.SELL, Side.CLOSE, Side.LONG, Side.SHORT};
	private static final String[] SIDE_LABELS = {"Buy", "Sell", "Close", "Long", "Short"};
	private static final String[] SIDE_TIPS = {"Each pulse buys", "Each pulse sells", "Each pulse closes the whole position",
			"Each pulse makes sure you're long: flips a short, does nothing if already long",
			"Each pulse makes sure you're short: flips a long, does nothing if already short"};
	private static final OrderRequest.Type[] TYPES = OrderRequest.Type.BLOCK_TYPES;
	private static final String[] TYPE_LABELS = {"Market", "Limit", "Stop", "TP", "TWAP"};
	private static final String[] TYPE_TIPS = {"Fills now at the market price", "Rests in the book at your price",
			"Triggers a market order when the price crosses", "Take-profit: triggers a market order when the price reaches a target",
			"Splits the order into slices over several minutes"};

	private Side side;
	private OrderRequest.Type type;
	private OrderRequest.Tif tif;
	private boolean offsetMode, reduceOnly, cross, inputsTab, comparatorOrder, more;
	/** 0 redstone, 1 redstone and Signal Card events, 2 events only. */
	private int fireOn;
	private boolean cycle;
	private Repeat repeat;
	private boolean spotAtLayout;
	private HcField usdBox, priceBox, tpBox, slBox, cooldownBox, levBox, cancelBox;
	private final Map<ExecutorInput, ExecutorInput.Source> sources = new EnumMap<>(ExecutorInput.class);
	private final Map<ExecutorInput, double[]> ranges = new EnumMap<>(ExecutorInput.class);
	private final Map<ExecutorInput, HcField[]> rangeBoxes = new EnumMap<>(ExecutorInput.class);
	private final Map<ExecutorInput, Integer> rowY = new EnumMap<>(ExecutorInput.class);
	/** Newest first; seeded from the block, topped up as fires come back while the screen is open. */
	private final List<String> recent = new ArrayList<>();

	@Override
	public void onResult(long pos, boolean ok, String msg) {
		super.onResult(pos, ok, msg);
		if (pos != this.pos) return;
		recent.add(0, java.time.LocalTime.now().withNano(0) + (ok ? "  ✔ " : "  ✘ ") + msg);
		while (recent.size() > 5) recent.remove(recent.size() - 1);
		relayout();
	}

	public ExecutorScreen(JsonObject data) {
		super("Trade Executor", data);
		side = Side.parse(data.get("side").getAsString());
		type = OrderRequest.Type.parse(str("type", "MARKET"));
		tif = OrderRequest.Tif.parse(str("tif", "GTC"));
		offsetMode = !data.has("offsetMode") || data.get("offsetMode").getAsBoolean();
		reduceOnly = data.has("reduceOnly") && data.get("reduceOnly").getAsBoolean();
		cross = !data.has("cross") || data.get("cross").getAsBoolean();
		repeat = Repeat.parse(str("onRepeat", "NEW"));
		comparatorOrder = data.has("comparatorOrder") && data.get("comparatorOrder").getAsBoolean();
		fireOn = !data.has("onEvent") || !data.get("onEvent").getAsBoolean() ? 0 : data.get("eventOnly").getAsBoolean() ? 2 : 1;
		cycle = data.has("cycle") && data.get("cycle").getAsBoolean();
		if (data.has("recent")) for (var el : data.getAsJsonArray("recent")) recent.add(el.getAsString());
		JsonObject ins = data.has("inputs") ? data.getAsJsonObject("inputs") : new JsonObject();
		for (ExecutorInput in : ExecutorInput.values()) {
			JsonObject b = ins.has(in.name()) ? ins.getAsJsonObject(in.name()) : null;
			sources.put(in, b == null ? ExecutorInput.Source.FIXED : ExecutorInput.Source.parse(b.get("source").getAsString()));
			ranges.put(in, new double[]{b == null ? in.defLo : b.get("lo").getAsDouble(), b == null ? in.defHi : b.get("hi").getAsDouble()});
		}
		// open the fold when anything in it is already set
		more = num("tpPct") > 0 || num("slPct") > 0 || num("leverage") > 0 || num("cancelAfter") > 0 || repeat != Repeat.NEW || comparatorOrder || fireOn > 0 || cycle
				|| (data.has("cooldown") && num("cooldown") != 5);
	}

	/** Opens on the Inputs tab. */
	public ExecutorScreen showInputs() {
		inputsTab = true;
		return this;
	}

	private String str(String k, String def) {
		return data.has(k) ? data.get(k).getAsString() : def;
	}

	private double num(String k) {
		return data.has(k) ? data.get(k).getAsDouble() : 0;
	}

	@Override
	protected int panelWidth() {
		return 340;
	}

	@Override
	protected int accent() {
		return Ui.YELLOW;
	}

	private int boundCount() {
		int n = 0;
		for (ExecutorInput.Source s : sources.values()) if (s != ExecutorInput.Source.FIXED) n++;
		return n;
	}

	private boolean bound(ExecutorInput in) {
		return sources.get(in) != ExecutorInput.Source.FIXED;
	}

	private int labelColor(ExecutorInput in) {
		return bound(in) ? Ui.YELLOW : Ui.DIM;
	}

	/** A row of tab buttons across the field area; {@code selected} is the chosen index. */
	private void segments(Flow f, int y, String[] labels, String[] tips, int selected, java.util.function.IntConsumer pick, int color) {
		int x = f.left + 70, w = (f.width - 70 - 4 * (labels.length - 1)) / labels.length;
		for (int i = 0; i < labels.length; i++) {
			int idx = i;
			HcButton b = f.add(new HcButton(x + i * (w + 4), y, w, ROW, labels[i], HcButton.Style.TAB, btn -> {
				pick.accept(idx);
				relayout();
			}).selected(i == selected).color(color));
			if (tips != null) b.tip(tips[i]);
		}
	}

	@Override
	protected void fields(Flow f) {
		spotAtLayout = isSpot();
		int y = f.row(null, ROW);
		f.add(new HcButton(f.left, y, 70, ROW, "Order", HcButton.Style.TAB, b -> {
			inputsTab = false;
			relayout();
		}).selected(!inputsTab).color(Ui.YELLOW).tip("What each pulse does"));
		f.add(new HcButton(f.left + 74, y, 80, ROW, "Inputs" + (boundCount() > 0 ? " (" + boundCount() + ")" : ""), HcButton.Style.TAB, b -> {
			inputsTab = true;
			relayout();
		}).selected(inputsTab).color(Ui.YELLOW).tip("Let redstone strength set the size, leverage, price, TP/SL or TWAP length"));
		f.gap(2);
		if (inputsTab) inputs(f);
		else order(f);
	}

	private void order(Flow f) {
		String ticket = data.has("ticket") ? data.get("ticket").getAsString() : "";
		if (!ticket.isEmpty()) f.text("▤ Running an Order Ticket. Save writes these settings onto it; sneak-click the block with an empty hand to take it out.", Ui.ACCENT);
		int y = f.row("Action", ROW);
		segments(f, y, SIDE_LABELS, SIDE_TIPS, java.util.Arrays.asList(SIDES).indexOf(side), i -> side = SIDES[i], Ui.YELLOW);
		usdBox = null;
		if (side.usesAmount()) {
			y = f.row("Size", 14, labelColor(ExecutorInput.SIZE));
			usdBox = numField(f, f.left + 70, y, 80, "Size", data.has("usd") ? num("usd") : 100, 50).suffix("$");
			usdBox.validate(s -> usdBox.num(0) <= 0 ? "Enter an amount above $0" : null);
			int bx = f.left + 156;
			for (int amt : new int[]{100, 500, 1000}) {
				String l = amt >= 1000 ? "$" + amt / 1000 + "K" : "$" + amt;
				f.add(new HcButton(bx, y, 40, 14, l, b -> usdBox.setValue(String.valueOf(amt)))).tip("Each pulse trades " + l + " of notional");
				bx += 44;
			}
		}
		y = f.row("Order", ROW);
		segments(f, y, TYPE_LABELS, TYPE_TIPS, java.util.Arrays.asList(TYPES).indexOf(type), i -> type = TYPES[i], Ui.YELLOW);
		boolean twap = type == OrderRequest.Type.TWAP;
		boolean priced = type != OrderRequest.Type.MARKET && !twap;
		priceBox = null;
		if (priced) {
			y = f.row(offsetMode ? "Offset" : "Price", 14, labelColor(ExecutorInput.PRICE));
			f.add(new HcButton(f.left + 70, y, 70, 14, "% from mid", HcButton.Style.TAB, b -> {
				offsetMode = true;
				relayout();
			}).selected(offsetMode).color(Ui.YELLOW).tip("A price relative to the market when it fires, e.g. -1 = 1% below"));
			f.add(new HcButton(f.left + 144, y, 44, 14, "Fixed", HcButton.Style.TAB, b -> {
				offsetMode = false;
				relayout();
			}).selected(!offsetMode).color(Ui.YELLOW).tip("One exact price"));
			priceBox = numField(f, f.left + 194, y, f.width - 194 + PAD - PAD, offsetMode ? "Offset" : "Price",
					offsetMode ? num("offsetPct") : num("absPrice"), offsetMode ? 0.1 : 0).suffix(offsetMode ? "%" : "$");
		} else if (twap) {
			y = f.row("Length", 14, labelColor(ExecutorInput.MINUTES));
			priceBox = numField(f, f.left + 70, y, 60, "Minutes", num("minutes") > 0 ? num("minutes") : 30, 5).suffix("min");
			priceBox.validate(s -> priceBox.num(0) < 5 || priceBox.num(0) > 1440 ? "Between 5 and 1440 minutes" : null);
		}
		if (type == OrderRequest.Type.LIMIT) {
			y = f.row("Rests", ROW);
			segments(f, y, new String[]{"Until filled", "Post-only", "Fill or kill"},
					new String[]{"GTC: stays in the book until it fills or you cancel it", "ALO: only rests, never takes; rejected if it would fill at once",
							"IOC: fills what it can now, cancels the rest"},
					tif.ordinal(), i -> tif = OrderRequest.Tif.values()[i], Ui.YELLOW);
		}
		y = f.row("Options", ROW);
		f.add(new HcButton(f.left + 70, y, 100, ROW, (reduceOnly ? "■" : "□") + " Reduce-only", b -> {
			reduceOnly = !reduceOnly;
			relayout();
		}).tip("Only ever shrinks a position, never opens or grows one"));
		f.add(new HcButton(f.left + 176, y, f.width - 176, ROW, (more ? "▾" : "▸") + " More settings", b -> {
			more = !more;
			relayout();
		}).tip("TP/SL, leverage, cooldown, repeat and the comparator"));
		tpBox = slBox = levBox = cooldownBox = cancelBox = null;
		if (more) {
			f.rule();
			boolean bracket = !type.isTrigger() && !twap && side != Side.CLOSE && !reduceOnly;
			if (bracket) {
				y = f.row("TP / SL", 14, bound(ExecutorInput.TP) || bound(ExecutorInput.SL) ? Ui.YELLOW : Ui.DIM);
				tpBox = numField(f, f.left + 70, y, 90, "TP", num("tpPct"), 0.5).suffix("% · 0 = off");
				slBox = numField(f, f.left + 166, y, 90, "SL", num("slPct"), 0.5).suffix("% · 0 = off");
				tpBox.tip("Take-profit distance in %. 0 = none");
				slBox.tip("Stop-loss distance in %. 0 = none");
			}
			if (!spotAtLayout) {
				y = f.row("Leverage", 14, labelColor(ExecutorInput.LEVERAGE));
				levBox = numField(f, f.left + 70, y, 90, "Leverage", num("leverage"), 1).suffix("x · 0 = keep");
				levBox.hint("keep").tip("Sets this leverage before each order. 0 = keep the account's current leverage");
				f.add(new HcButton(f.left + 166, y, 44, 14, "Cross", HcButton.Style.TAB, b -> {
					cross = true;
					relayout();
				}).selected(cross).color(Ui.YELLOW).tip("Cross margin: the whole account backs the position"));
				f.add(new HcButton(f.left + 214, y, 56, 14, "Isolated", HcButton.Style.TAB, b -> {
					cross = false;
					relayout();
				}).selected(!cross).color(Ui.YELLOW).tip("Isolated margin: only the position's own margin is at risk"));
			}
			y = f.row("Cooldown", 14);
			cooldownBox = numField(f, f.left + 70, y, 90, "Cooldown", data.has("cooldown") ? num("cooldown") : 5, 1).suffix("s");
			cooldownBox.tip("Pulses closer together than this are ignored");
			if (!twap) {
				y = f.row("Cancel after", 14);
				cancelBox = numField(f, f.left + 70, y, 90, "Cancel after", num("cancelAfter"), 5).suffix("s · 0 = never");
				cancelBox.hint("never").tip("Cancels the order if it hasn't filled by then. 0 = never");
				y = f.row("If resting", ROW);
				segments(f, y, new String[]{"Place new", "Amend it", "Replace it"},
						new String[]{"Each pulse places another order", "A pulse moves its resting order to the current price and size",
								"A pulse cancels its resting order and places a fresh one"},
						repeat.ordinal(), i -> repeat = Repeat.values()[i], Ui.YELLOW);
			}
			y = f.row("Comparator", ROW);
			segments(f, y, new String[]{"Last result", "Order status"},
					new String[]{"15 = last pulse succeeded, 1 = rejected", "4 resting, 8 partly filled, 15 filled, 2 cancelled, 1 rejected"},
					comparatorOrder ? 1 : 0, i -> comparatorOrder = i == 1, Ui.YELLOW);
			y = f.row("Tickets", ROW);
			segments(f, y, new String[]{"Keep", "Cycle"},
					new String[]{"An Order Ticket stays in until you take it out",
							"After each pulse the ticket goes down into a chest or hopper below, and the next one comes from a chest above: a chest of tickets is a sequence of orders"},
					cycle ? 1 : 0, i -> cycle = i == 1, Ui.YELLOW);
			y = f.row("Fires on", ROW);
			segments(f, y, new String[]{"Redstone", "+ Signal events", "Events only"},
					new String[]{"A rising redstone edge fires it", "Also fires once per event from the Signal Card: another executor's order, a fill, a bell",
							"Only the Signal Card's events fire it; redstone is ignored"},
					fireOn, i -> fireOn = i, Ui.YELLOW);
		}
		if (boundCount() > 0) f.text("Yellow settings come from redstone (Inputs tab); their value here is ignored.", Ui.YELLOW);
		if (!recent.isEmpty()) {
			f.rule();
			f.heading("Recent fires");
			for (String s : recent) {
				f.text(Ui.ellipsize(font, s, f.width), s.contains("✘") ? Ui.RED : Ui.DIM);
				f.y -= 3;
			}
		}
	}

	private void inputs(Flow f) {
		List<HcDropdown.Option<ExecutorInput.Source>> opts = new ArrayList<>();
		for (ExecutorInput.Source s : ExecutorInput.Source.values()) {
			String help = switch (s) {
				case FIXED -> "Use the value on the Order tab";
				case TRIGGER -> "The strength of the pulse that fires it";
				case SIGNAL -> "The inserted Signal Card's value, 0 to 1 across the range";
				default -> "The redstone strength fed into this face";
			};
			opts.add(new HcDropdown.Option<>(s, s.label, "", help));
		}
		for (ExecutorInput in : ExecutorInput.values()) {
			HcField[] old = rangeBoxes.get(in);
			if (old != null) ranges.put(in, new double[]{parse(old[0], in.defLo), parse(old[1], in.defHi)});
		}
		rangeBoxes.clear();
		for (ExecutorInput in : ExecutorInput.values()) {
			int y = f.row(in.label, 14, bound(in) ? Ui.TEXT : Ui.DIM);
			rowY.put(in, y);
			f.add(new HcDropdown<>(f.left + 80, y - 1, 96, 16, opts, sources.get(in), s -> {
				sources.put(in, s);
				relayout();
			}));
			if (bound(in)) {
				double[] r = ranges.get(in);
				HcField lo = numField(f, f.left + 182, y, 36, "lo " + in, r[0], 1);
				HcField hi = numField(f, f.left + 232, y, 36, "hi " + in, r[1], 1);
				lo.tip("Value at signal 0");
				hi.tip("Value at signal 15");
				rangeBoxes.put(in, new HcField[]{lo, hi});
			}
		}
		f.text("Feed a face with a comparator, repeater or dust: its strength 0–15 sets the value across the range. "
				+ "Bound faces are labelled on the block and never fire it; any other face does.", Ui.DIM);
	}

	private boolean isSpot() {
		ClientState.Ticker t = coinBox == null ? null : ClientState.ticker(coinBox.getValue());
		return t != null && t.spot;
	}

	@Override
	public void tick() {
		super.tick();
		if (armedAt > 0 && System.currentTimeMillis() - armedAt > 4000) {
			armedAt = 0;
			relayout();
		}
		if (!inputsTab && more && isSpot() != spotAtLayout) relayout();
	}

	@Override
	protected void footerExtras(Footer f) {
		HcButton[] fire = new HcButton[1];
		fire[0] = f.left("Fire now", HcButton.Style.NORMAL, () -> {
			// two clicks: it places an order, so the first click only arms it
			if (System.currentTimeMillis() - armedAt > 4000) {
				armedAt = System.currentTimeMillis();
				fire[0].label("Sure?");
				toast("Click again to fire once: " + summary().replace("Each pulse ", "").replaceFirst(" · .*", ""), Ui.YELLOW);
				return;
			}
			armedAt = 0;
			fire[0].label("Fire now");
			fireNow();
		}).color(Ui.YELLOW);
		fire[0].tip("Saves, then fires once, as a redstone pulse would. Click twice");
	}

	private long armedAt;

	@Override
	protected String summary() {
		String base = switch (side) {
			case BUY -> "Each pulse buys";
			case SELL -> "Each pulse sells";
			case CLOSE -> "Each pulse closes the position";
			case LONG -> "Each pulse goes long";
			case SHORT -> "Each pulse goes short";
			case REVERSE -> "Each pulse reverses the position";
		};
		String size = side.usesAmount() ? " " + (bound(ExecutorInput.SIZE) ? "$~" : "$" + (usdBox == null ? Fmt.size(num("usd")) : usdBox.getValue())) : "";
		String c = coin().isEmpty() ? "" : " " + ClientState.display(coin());
		String how = switch (type) {
			case MARKET -> " at market";
			case LIMIT -> " with a limit order";
			case STOP -> " with a stop";
			case TAKE_PROFIT -> " with a take-profit";
			case TWAP -> " over " + (priceBox == null ? "30" : priceBox.getValue()) + " min";
			default -> "";
		};
		// the money a pulse ties up: at the leverage set here, or the account's own when 0 keeps it
		double lev = levBox == null ? num("leverage") : levBox.num(0);
		if (lev < 1 && data.has("accountLev")) lev = data.get("accountLev").getAsInt();
		ClientState.Ticker t = ClientState.ticker(coin());
		String margin = side.usesAmount() && !bound(ExecutorInput.SIZE) && lev >= 1 && (t == null || !t.spot)
				? " · ≈ " + Fmt.usd((usdBox == null ? num("usd") : parse(usdBox, 0)) / lev) + " margin at " + (int) lev + "x" : "";
		return base + size + c + how + margin;
	}

	@Override
	protected void writeFields(JsonObject out) {
		out.addProperty("side", side.name());
		out.addProperty("usd", usdBox == null ? num("usd") : parse(usdBox, 100));
		out.addProperty("type", type.name());
		out.addProperty("offsetMode", offsetMode);
		boolean twap = type == OrderRequest.Type.TWAP;
		double px = priceBox == null ? 0 : parse(priceBox, 0);
		out.addProperty("offsetPct", offsetMode && !twap ? px : 0);
		out.addProperty("absPrice", offsetMode || twap ? 0 : px);
		out.addProperty("minutes", twap ? Math.max(5, Math.min(1440, (int) px)) : (int) num("minutes"));
		out.addProperty("tif", tif.name());
		out.addProperty("reduceOnly", reduceOnly);
		out.addProperty("tpPct", tpBox == null ? num("tpPct") : parse(tpBox, 0));
		out.addProperty("slPct", slBox == null ? num("slPct") : parse(slBox, 0));
		out.addProperty("cooldown", cooldownBox == null ? (data.has("cooldown") ? num("cooldown") : 5) : parse(cooldownBox, 5));
		out.addProperty("leverage", (int) (levBox == null ? num("leverage") : parse(levBox, 0)));
		out.addProperty("cross", cross);
		out.addProperty("onRepeat", repeat.name());
		out.addProperty("comparatorOrder", comparatorOrder);
		out.addProperty("cycle", cycle);
		out.addProperty("onEvent", fireOn > 0);
		out.addProperty("eventOnly", fireOn == 2);
		out.addProperty("cancelAfter", (int) (cancelBox == null ? num("cancelAfter") : parse(cancelBox, 0)));
		JsonObject ins = new JsonObject();
		for (ExecutorInput in : ExecutorInput.values()) {
			JsonObject b = new JsonObject();
			b.addProperty("source", sources.get(in).name());
			HcField[] boxes = rangeBoxes.get(in);
			double[] r = ranges.get(in);
			b.addProperty("lo", boxes == null ? r[0] : parse(boxes[0], in.defLo));
			b.addProperty("hi", boxes == null ? r[1] : parse(boxes[1], in.defHi));
			ins.add(in.name(), b);
		}
		out.add("inputs", ins);
	}

	private int signal(ExecutorInput.Source s) {
		JsonObject sig = data.has("signals") ? data.getAsJsonObject("signals") : null;
		return sig != null && sig.has(s.name()) ? sig.get(s.name()).getAsInt() : 0;
	}

	@Override
	protected void drawContent(GuiGraphics g, int mx, int my) {
		super.drawContent(g, mx, my);
		if (!inputsTab) return;
		for (ExecutorInput in : ExecutorInput.values()) {
			Integer y = rowY.get(in);
			HcField[] boxes = rangeBoxes.get(in);
			if (y == null || boxes == null) continue;
			Ui.text(g, font, "→", PAD + 222, y + 3, Ui.DIM);
			double v = ExecutorInput.map(signal(sources.get(in)), parse(boxes[0], in.defLo), parse(boxes[1], in.defHi));
			String now = in == ExecutorInput.SIZE ? "$" + Fmt.size(Math.round(v)) : in == ExecutorInput.LEVERAGE || in == ExecutorInput.MINUTES
					? Math.round(v) + in.unit : Fmt.size(v) + in.unit;
			Ui.textRight(g, font, Ui.ellipsize(font, now, pw - PAD - (PAD + 274)), pw - PAD, y + 3, Ui.YELLOW);
		}
	}
}
