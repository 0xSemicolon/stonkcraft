package dev.hypercraft.client.screen;

import com.google.gson.JsonObject;
import dev.hypercraft.client.ClientNet;
import dev.hypercraft.client.ClientState;
import dev.hypercraft.client.ui.HcButton;
import dev.hypercraft.client.ui.HcField;
import dev.hypercraft.client.ui.HcScreen;
import dev.hypercraft.client.ui.Ui;
import dev.hypercraft.trading.Fmt;
import dev.hypercraft.trading.OrderRequest;
import dev.hypercraft.trading.Side;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/** One position: close it at a price or for a share, reverse it, set its TP/SL by price, and move an isolated position's margin. */
public class PositionScreen extends HcScreen {
	private final String coin;
	private final Screen parent;
	private HcField closePrice, tp, sl, marginAmount;
	private boolean seeded;

	public PositionScreen(String coin, Screen parent) {
		super("Position: " + ClientState.display(coin));
		this.coin = coin;
		this.parent = parent;
	}

	@Override
	protected int panelWidth() {
		return 300;
	}

	@Nullable
	private JsonObject position() {
		return ClientState.position(coin);
	}

	@Override
	protected String status() {
		JsonObject p = position();
		if (p == null) return "No position: it's closed";
		double size = p.get("size").getAsDouble();
		return (size > 0 ? "Long " : "Short ") + Fmt.size(Math.abs(size)) + " @ " + Fmt.price(p.get("entry").getAsDouble())
				+ " · PnL " + Fmt.usdSigned(p.get("upnl").getAsDouble());
	}

	private double mark() {
		JsonObject p = position();
		return p == null ? 0 : p.get("mark").getAsDouble();
	}

	/** A market order for a share of the position. */
	private void marketClose(int pct) {
		JsonObject p = position();
		if (p == null) return;
		OrderRequest r = new OrderRequest();
		r.coin = coin;
		if (pct >= 100) {
			r.side = Side.CLOSE;
		} else {
			double size = p.get("size").getAsDouble();
			r.side = size > 0 ? Side.SELL : Side.BUY;
			r.sizeCoin = Math.abs(size) * pct / 100.0;
			r.usd = r.sizeCoin * mark();
			r.reduceOnly = true;
		}
		ClientNet.order(r);
		chime();
		if (pct >= 100) onClose();
	}

	private void limitClose() {
		double px = closePrice.num(0);
		if (px <= 0) {
			toast("Enter a price", Ui.YELLOW);
			return;
		}
		OrderRequest r = new OrderRequest();
		r.coin = coin;
		r.side = Side.CLOSE;
		r.type = OrderRequest.Type.LIMIT;
		r.price = px;
		ClientNet.order(r);
		chime();
		toast("Limit close placed at " + Fmt.price(px), Ui.GREEN);
	}

	private void reverse() {
		OrderRequest r = new OrderRequest();
		r.coin = coin;
		r.side = Side.REVERSE;
		ClientNet.order(r);
		chime();
		toast("Reversing " + ClientState.display(coin), Ui.GREEN);
	}

	private void saveTpSl() {
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		o.addProperty("tp", tp.num(0));
		o.addProperty("sl", sl.num(0));
		ClientNet.send("position_tpsl", o);
		chime();
		toast("TP/SL sent", Ui.GREEN);
	}

	private void moveMargin(boolean add) {
		double usd = marginAmount.num(0);
		if (usd <= 0) {
			toast("Enter an amount", Ui.YELLOW);
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		o.addProperty("usd", add ? usd : -usd);
		ClientNet.send("adjust_margin", o);
		chime();
		toast((add ? "Adding " : "Taking ") + Fmt.usd(usd) + " margin", Ui.GREEN);
	}

	@Override
	protected void layout(Flow f) {
		JsonObject p = position();
		if (p == null) {
			f.text("This position is closed.", Ui.DIM);
			return;
		}
		double size = p.get("size").getAsDouble();
		boolean isLong = size > 0;
		boolean cross = !p.has("cross") || p.get("cross").getAsBoolean();
		String mark = Fmt.plain(mark());

		f.heading("Close");
		int y = f.row("Market", ROW);
		int bw = (f.width - 70 - 3 * 4) / 4;
		int[] shares = {25, 50, 75, 100};
		for (int i = 0; i < shares.length; i++) {
			int pct = shares[i];
			f.add(new HcButton(f.left + 70 + i * (bw + 4), y, bw, ROW, pct + "%", pct == 100 ? HcButton.Style.DANGER : HcButton.Style.NORMAL, b -> marketClose(pct)))
					.tip("Close " + pct + "% of the position at market");
		}
		y = f.row("Limit", 14);
		String keptClose = closePrice == null ? mark : closePrice.getValue();
		closePrice = f.add(field(f.left + 70, y, f.width - 70 - 90, "close price", keptClose));
		closePrice.numeric(0).suffix("$");
		f.add(new HcButton(f.left + f.width - 86, y, 40, 14, "Mid", b -> {
			ClientState.Ticker t = ClientState.ticker(coin);
			if (t != null) closePrice.setValue(Fmt.plain(t.mid));
		})).tip("The market's mid price");
		f.add(new HcButton(f.left + f.width - 42, y, 42, 14, "Place", b -> limitClose())).tip("Close the whole position at this price, reduce-only");
		y = f.row("Reverse", ROW);
		f.add(new HcButton(f.left + 70, y, f.width - 70, ROW, "Reverse to " + (isLong ? "short " : "long ") + Fmt.size(Math.abs(size)), b -> reverse()))
				.tip("Close this position and open the same size the other way. Its TP/SL goes with it");

		f.rule();
		f.heading("Take profit / stop loss");
		y = f.row("TP / SL", 14);
		int half = (f.width - 70 - 4) / 2;
		String keptTp = seeded ? tp.getValue() : p.has("tp") ? Fmt.plain(p.get("tp").getAsDouble()) : "";
		String keptSl = seeded ? sl.getValue() : p.has("sl") ? Fmt.plain(p.get("sl").getAsDouble()) : "";
		seeded = true;
		tp = f.add(field(f.left + 70, y, half, "take profit", keptTp));
		tp.numeric(0).suffix("TP $").hint(isLong ? "above mark" : "below mark");
		sl = f.add(field(f.left + 74 + half, y, half, "stop loss", keptSl));
		sl.numeric(0).suffix("SL $").hint(isLong ? "below mark" : "above mark");
		double m = mark();
		tp.validate(s -> tp.num(0) > 0 && (isLong ? tp.num(0) <= m : tp.num(0) >= m) ? "TP must be " + (isLong ? "above " : "below ") + Fmt.price(m) : null);
		sl.validate(s -> sl.num(0) > 0 && (isLong ? sl.num(0) >= m : sl.num(0) <= m) ? "SL must be " + (isLong ? "below " : "above ") + Fmt.price(m) : null);
		y = f.row("", ROW);
		f.add(new HcButton(f.left + 70, y, f.width - 70, ROW, "Save TP/SL", b -> saveTpSl()))
				.tip("Covers the whole position as it grows or shrinks, and goes when it closes. Empty clears it");

		f.rule();
		f.heading("Margin");
		String liq = p.has("liq") ? Fmt.price(p.get("liq").getAsDouble()) : "none";
		if (cross) {
			f.text("Cross: margin comes from the shared balance. Liquidation price " + liq + ". Switch to Iso on the order form to give this position its own margin.", Ui.DIM);
			return;
		}
		f.text("Isolated: " + Fmt.usd(p.get("isoMargin").getAsDouble()) + " held, " + Fmt.usd(p.has("removable") ? p.get("removable").getAsDouble() : 0)
				+ " above what it needs. Liquidation price " + liq + ".", Ui.DIM);
		y = f.row("Amount", 14);
		String keptMargin = marginAmount == null ? "" : marginAmount.getValue();
		marginAmount = f.add(field(f.left + 70, y, f.width - 70 - 92, "margin amount", keptMargin));
		marginAmount.numeric(100).suffix("USDC").hint("0");
		f.add(new HcButton(f.left + f.width - 88, y, 42, 14, "Add", b -> moveMargin(true))).tip("Move USDC to this position: its liquidation price moves away");
		f.add(new HcButton(f.left + f.width - 44, y, 44, 14, "Take", b -> moveMargin(false))).tip("Take back margin it doesn't need");
	}

	@Override
	protected void footer(Footer f) {
		f.primary("Done", this::onClose);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
