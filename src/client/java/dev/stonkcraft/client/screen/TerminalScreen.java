package dev.stonkcraft.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stonkcraft.client.ClientConfig;
import dev.stonkcraft.client.ClientNet;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.trading.Fmt;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import dev.stonkcraft.trading.OrderRequest;
import dev.stonkcraft.trading.Side;
import org.lwjgl.glfw.GLFW;

/** The trading terminal: markets, live candle chart, order ticket, positions and fills. */
public class TerminalScreen extends Screen {
	private static final String[] INTERVALS = {"1m", "5m", "15m", "1h", "4h", "1d"};
	/** Size buttons: a share of what's available to trade, as Hyperliquid's size slider marks it. */
	private static final int[] QUICK_PCT = {10, 25, 50, 75, 100};
	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault());

	private record Hit(int x, int y, int w, int h, Runnable action) {}

	private final List<Hit> hits = new ArrayList<>();
	private EditBox search;
	private EditBox amount;
	private String coin;
	private String interval;
	private int listScroll;
	private int tableScroll;
	private int tab;
	private long lastCandleRequest;
	private boolean subscribed;
	private boolean accountMenu;
	/** Market list tab: 0 perps, 1 stocks and commodities (xyz HIP-3), 2 spot (with Unit tokens first). */
	private int marketTab;
	/** 0 candles, 1 order book, 2 funding history, 3 the account. */
	private int chartMode;
	/** The order form's side: one submit button, as Hyperliquid has. */
	private boolean sellSide;
	private EditBox price2Box;
	private EditBox scaleCountBox;
	private EditBox skewBox;
	/** The Pro order-type dropdown. */
	private boolean proMenu;
	private int proX, proY, proW;
	private final List<Hit> proHits = new ArrayList<>();
	private long lastHistoryRequest;
	/** The Fills tab's other half: every order and how it ended. */
	private boolean orderHistoryMode;
	/** The Account view's chart: the last hour or the last month; value or PnL. */
	private boolean accountMonth;
	private boolean accountPnl;
	/** When Close All was pressed, while it's held down (the hold-to-close setting). */
	private long holdCloseSince;
	private long soundedOrderAt;
	private long lastBookRequest;
	private long lastFundingRequest;
	private String fundingCoin = "";
	private OrderRequest.Type orderType = OrderRequest.Type.MARKET;
	private OrderRequest.Tif tif = OrderRequest.Tif.GTC;
	private boolean reduceOnly;
	private EditBox priceBox;
	private EditBox tpBox;
	private EditBox slBox;
	private int menuX, menuY;
	private final List<Hit> menuHits = new ArrayList<>();

	// layout
	private int m, headerH, bodyY, bodyH, leftX, leftW, centerX, centerW, rightX, rightW, chartH;

	public TerminalScreen() {
		this(0);
	}

	/** @param tableTab 0 positions, 1 orders, 2 fills, 3 balances */
	public TerminalScreen(int tableTab) {
		super(Component.literal("Stonkcraft Terminal"));
		tab = tableTab;
		ClientConfig cfg = ClientConfig.get();
		coin = cfg.lastCoin;
		interval = cfg.interval;
	}

	@Override
	protected void init() {
		m = 6;
		headerH = 28;
		bodyY = m + headerH + 4;
		bodyH = height - bodyY - m;
		leftW = clamp((int) (width * 0.24), 112, 170);
		rightW = clamp((int) (width * 0.22), 118, 160);
		leftX = m;
		rightX = width - m - rightW;
		centerX = leftX + leftW + 4;
		centerW = rightX - 4 - centerX;
		chartH = (int) (bodyH * 0.6);

		String prevSearch = search == null ? "" : search.getValue();
		search = new EditBox(font, leftX + 6, bodyY + 6, leftW - 12, 10, Component.literal("Search"));
		search.setBordered(false);
		search.setMaxLength(16);
		search.setHint(Component.literal("Search markets…"));
		search.setValue(prevSearch);
		search.setResponder(s -> listScroll = 0);
		addRenderableWidget(search);

		String prevAmount = amount == null ? Fmt.size(ClientConfig.get().orderUsd) : amount.getValue();
		amount = new EditBox(font, rightX + 18, bodyY + 47, rightW - 26, 10, Component.literal("USD"));
		amount.setBordered(false);
		amount.setMaxLength(12);
		amount.setFilter(s -> s.matches("[0-9]*\\.?[0-9]*"));
		amount.setValue(prevAmount);
		addRenderableWidget(amount);

		priceBox = numberBox(priceBox, "Price");
		price2Box = numberBox(price2Box, "");
		scaleCountBox = numberBox(scaleCountBox, "5");
		skewBox = numberBox(skewBox, "1");
		tpBox = numberBox(tpBox, "%");
		slBox = numberBox(slBox, "%");
		layoutTicket();

		if (!subscribed) {
			subscribed = true;
			ClientNet.send("subscribe");
			requestCandles();
		}
	}

	private EditBox numberBox(EditBox prev, String hint) {
		EditBox box = new EditBox(font, 0, 0, 40, 10, Component.literal(hint));
		box.setBordered(false);
		box.setMaxLength(14);
		box.setFilter(v -> v.matches("[0-9]*\\.?[0-9]*"));
		box.setHint(Component.literal(hint).withStyle(st -> st.withColor(0x3C5A57)));
		if (prev != null) box.setValue(prev.getValue());
		return addRenderableWidget(box);
	}

	/** Opens on an order form set up this way, for the tour's shots. */
	public TerminalScreen ticket(String market, OrderRequest.Type type, boolean sell) {
		coin = market;
		orderType = type;
		sellSide = sell;
		return this;
	}

	/** Opens on a chart view: 0 candles, 1 order book, 2 funding history, 3 the account. */
	public TerminalScreen view(int mode) {
		chartMode = mode;
		return this;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		ClientNet.send("unsubscribe");
		ClientConfig cfg = ClientConfig.get();
		cfg.lastCoin = coin;
		cfg.interval = interval;
		cfg.orderUsd = amountValue();
		ClientConfig.save();
	}

	@Override
	public void tick() {
		long now = System.currentTimeMillis();
		if (now - lastCandleRequest > 30_000) requestCandles();
		if (chartMode == 1 && now - lastBookRequest > 1_000) {
			lastBookRequest = now;
			JsonObject o = new JsonObject();
			o.addProperty("coin", coin);
			ClientNet.send("book", o);
		}
		if (chartMode == 2 && (now - lastFundingRequest > 60_000 || !fundingCoin.equals(coin))) {
			lastFundingRequest = now;
			fundingCoin = coin;
			JsonObject o = new JsonObject();
			o.addProperty("coin", coin);
			ClientNet.send("funding_history", o);
		}
		// the deep history comes on request, while something's showing it
		if ((chartMode == 3 || tab == 3) && now - lastHistoryRequest > 3_000) {
			lastHistoryRequest = now;
			ClientNet.send("history");
		}
		ClientConfig cfg = ClientConfig.get();
		if (ClientState.lastOrderAt != soundedOrderAt) {
			if (soundedOrderAt != 0 && cfg.sounds && minecraft != null) {
				var sound = ClientState.lastOrderOk ? net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value() : net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS.value();
				minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, ClientState.lastOrderOk ? 1.4f : 0.8f));
			}
			soundedOrderAt = ClientState.lastOrderAt;
		}
		if (holdCloseSince > 0 && now - holdCloseSince >= 1_000) {
			holdCloseSince = 0;
			closeAll();
		}
	}

	private void closeAll() {
		JsonObject pf = ClientState.hasAccount() ? ClientState.portfolio : null;
		for (JsonElement el : arr(pf, "positions")) ClientNet.order(el.getAsJsonObject().get("coin").getAsString(), "CLOSE", 0);
	}

	private void requestCandles() {
		lastCandleRequest = System.currentTimeMillis();
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		o.addProperty("interval", interval);
		ClientNet.send("candles", o);
	}

	private void select(String c) {
		if (c.equals(coin)) return;
		coin = c;
		requestCandles();
	}

	private double amountValue() {
		try {
			return Double.parseDouble(amount.getValue());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// ---------------------------------------------------------------- rendering

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
		hits.clear();
		g.fill(0, 0, width, height, Ui.BG);
		drawHeader(g, mx, my);
		drawMarkets(g, mx, my);
		drawChart(g, mx, my);
		drawTable(g, mx, my);
		drawTicket(g, mx, my);
	}

	private void hit(int x, int y, int w, int h, Runnable r) {
		hits.add(new Hit(x, y, w, h, r));
	}

	private void drawHeader(GuiGraphics g, int mx, int my) {
		int x = m, y = m, w = width - 2 * m;
		Ui.panel(g, x, y, w, headerH);
		boolean narrow = width < 600;
		String logo = narrow ? "◆" : "◆ STONKCRAFT";
		Ui.textScaled(g, font, logo, x + 8, y + 6, 1.5f, Ui.ACCENT);
		int cx = x + 8 + (int) (font.width(logo) * 1.5f) + 8;

		String mode = ClientState.portfolio == null ? ClientState.mode
				: ClientState.portfolio.has("tag") ? ClientState.portfolio.get("tag").getAsString() : ClientState.portfolio.get("mode").getAsString();
		int modeColor = Ui.tagColor(mode);
		int bw = font.width(mode) + 10;
		g.fill(cx, y + 8, cx + bw, y + 20, (modeColor & 0xFFFFFF) | 0x30000000);
		Ui.outline(g, cx, y + 8, bw, 12, modeColor);
		Ui.text(g, font, mode, cx + 5, y + 10, modeColor);
		cx += bw + 6;
		int desks = ClientState.portfolio != null && ClientState.portfolio.has("copyDesks") ? ClientState.portfolio.get("copyDesks").getAsInt() : 0;
		if (desks > 0) {
			String cs = "⧉ COPYING";
			int cw = font.width(cs) + 10;
			g.fill(cx, y + 8, cx + cw, y + 20, 0x307FD3FF);
			Ui.outline(g, cx, y + 8, cw, 12, 0xFF7FD3FF);
			Ui.text(g, font, cs, cx + 5, y + 10, 0xFF7FD3FF);
			cx += cw + 6;
		}
		boolean live = ClientState.healthy && System.currentTimeMillis() - ClientState.lastMarketAt < 5000;
		boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
		g.fill(cx, y + 12, cx + 4, y + 16, live ? (blink ? Ui.GREEN : 0xFF1E7A5B) : Ui.RED);
		if (!narrow || !live) {
			Ui.text(g, font, live ? "LIVE" : "OFFLINE", cx + 7, y + 10, live ? Ui.DIM : Ui.RED);
			cx += 7 + font.width(live ? "LIVE" : "OFFLINE") + 10;
		} else {
			cx += 10;
		}

		// account switcher
		String acctName = ClientState.portfolio != null && ClientState.portfolio.has("accountName")
				&& !ClientState.portfolio.get("accountName").getAsString().isEmpty()
				? ClientState.portfolio.get("accountName").getAsString() : "No account";
		String chip = "◈ " + Ui.ellipsize(font, acctName, narrow ? 50 : 120) + (ClientState.accounts.size() > 1 ? "  ▾" : "");
		int chipW = font.width(chip) + 12;
		boolean hov = Ui.inside(mx, my, cx, y + 7, chipW, 14);
		g.fill(cx, y + 7, cx + chipW, y + 21, hov || accountMenu ? Ui.PANEL_HI : 0xFF0A1517);
		Ui.outline(g, cx, y + 7, chipW, 14, hov || accountMenu ? Ui.ACCENT : Ui.BORDER);
		Ui.text(g, font, chip, cx + 6, y + 10, ClientState.hasAccount() ? Ui.TEXT : Ui.YELLOW);
		menuX = cx;
		menuY = y + 22;
		hit(cx, y + 7, chipW, 14, () -> accountMenu = !accountMenu);
		cx += chipW + 10;
		int leftEdge = cx + 6;
		if (ClientState.hasAccount() && ClientState.portfolio.has("accountId") && !"WATCH".equals(ClientState.portfolio.get("mode").getAsString())) {
			String fundId = ClientState.portfolio.get("accountId").getAsString();
			boolean empty = ClientState.portfolio.get("equity").getAsDouble() < 1;
			boolean pulse = empty && (System.currentTimeMillis() / 600) % 2 == 0;
			Ui.chip(g, font, "+ Fund", cx, y + 7, 42, 14, empty ? Ui.YELLOW : Ui.GREEN, pulse, mx, my);
			hit(cx, y + 7, 42, 14, () -> minecraft.setScreen(new FundScreen(fundId, this)));
			leftEdge += 46;
		}
		int gearX = leftEdge - 6;
		Ui.chip(g, font, "⚙", gearX, y + 7, 14, 14, Ui.ACCENT_DIM, false, mx, my);
		hit(gearX, y + 7, 14, 14, () -> minecraft.setScreen(new SettingsScreen(this)));
		leftEdge += 18;

		JsonObject pf = ClientState.hasAccount() ? ClientState.portfolio : null;
		if (pf == null) return;
		double eq = pf.get("equity").getAsDouble();
		double upnl = pf.get("upnl").getAsDouble();
		int right = x + w - 8;
		JsonArray hist = pf.getAsJsonArray("equityHistory");
		int sparkW = Math.min(110, w / 6);
		// fit the right-hand stats into what the left side leaves, dropping the least important first
		String eqS = Fmt.usd(eq);
		String upnlS = Fmt.usdSigned(upnl);
		double start = pf.has("start") ? pf.get("start").getAsDouble() : 0;
		double ret = start == 0 ? 0 : (eq - start) / start * 100;
		String rs = Fmt.pct(ret);
		int eqW = (int) (font.width(eqS) * 1.5f);
		int upnlW = Math.max(font.width(upnlS), 24) + 12;
		int retW = pf.has("start") ? Math.max(font.width(rs), 30) + 12 : 0;
		int avail = right - leftEdge;
		eqW = (int) (font.width(eqS) * 1.25f) + 4;
		// the margin ratio matters more than the return when there's risk on, so it gives way only after the sparkline
		double ratio = num(pf, "marginRatio", 0);
		String ratioS = String.format(Locale.ROOT, "%.0f%%", ratio * 100);
		int ratioW = ratio > 0 ? Math.max(font.width(ratioS), font.width("Margin")) + 12 : 0;
		boolean showSpark = hist.size() > 1 && avail >= sparkW + 10 + upnlW + retW + ratioW + eqW;
		boolean showRatio = ratioW > 0 && avail >= upnlW + ratioW + eqW;
		boolean showRet = retW > 0 && avail >= upnlW + retW + (showRatio ? ratioW : 0) + eqW;
		boolean showUpnl = avail >= upnlW + eqW;
		float eqScale = showUpnl ? 1.25f : 1.0f;
		if (showSpark) {
			double[] data = new double[hist.size()];
			for (int i = 0; i < data.length; i++) data[i] = hist.get(i).getAsDouble();
			Ui.sparkline(g, data, right - sparkW, y + 5, sparkW, headerH - 10, Ui.signColor(data[data.length - 1] - data[0]) | 0xFF000000);
			right -= sparkW + 10;
		}
		if (showRatio) {
			Ui.textRight(g, font, ratioS, right, y + 15, ratioColor(ratio));
			Ui.textRight(g, font, "Margin", right, y + 5, Ui.DIM);
			right -= ratioW;
		}
		if (showUpnl) {
			Ui.textRight(g, font, upnlS, right, y + 15, Ui.signColor(upnl));
			Ui.textRight(g, font, "uPnL", right, y + 5, Ui.DIM);
			right -= upnlW;
		}
		if (showRet) {
			Ui.textRight(g, font, rs, right, y + 15, Ui.signColor(ret));
			Ui.textRight(g, font, "Return", right, y + 5, Ui.DIM);
			right -= retW;
		}
		int ew = (int) (font.width(eqS) * eqScale);
		if (right - leftEdge < ew) return;
		Ui.textScaled(g, font, eqS, right - ew, eqScale > 1 ? y + 14 : y + 15, eqScale, Ui.TEXT);
		Ui.textRight(g, font, "Equity", right, y + 5, Ui.DIM);
	}

	private List<ClientState.Ticker> marketRows() {
		String q = search.getValue().trim().toLowerCase(Locale.ROOT);
		ClientConfig cfg = ClientConfig.get();
		List<ClientState.Ticker> watched = new ArrayList<>();
		List<ClientState.Ticker> rest = new ArrayList<>();
		List<ClientState.Ticker> units = new ArrayList<>();
		for (ClientState.Ticker t : ClientState.TICKERS.values()) {
			int tab = t.spot ? 2 : "xyz".equals(t.dex) ? 0 : 1;
			if (tab != marketTab) continue;
			if ("unit".equals(t.dex) && q.isEmpty() && !cfg.isWatched(t.name)) {
				units.add(t);
				continue;
			}
			if (!q.isEmpty() && !t.name.toLowerCase(Locale.ROOT).contains(q)) continue;
			(cfg.isWatched(t.name) ? watched : rest).add(t);
		}
		watched.addAll(units);
		watched.addAll(rest);
		return watched;
	}

	private int listTop() {
		return bodyY + 36;
	}

	private int listRowsVisible() {
		return (bodyY + bodyH - listTop() - 4) / 11;
	}

	private void drawMarkets(GuiGraphics g, int mx, int my) {
		int x = leftX, y = bodyY, w = leftW, h = bodyH;
		Ui.panel(g, x, y, w, h);
		g.fill(x + 3, y + 3, x + w - 3, y + 18, 0xFF081214);
		Ui.outline(g, x + 3, y + 3, w - 6, 15, search.isFocused() ? Ui.ACCENT_DIM : Ui.BORDER);
		int tw = (w - 10) / 3;
		String[] tabs = {"Stocks", "Perps", "Spot"};
		for (int i = 0; i < tabs.length; i++) {
			int idx = i;
			Ui.chip(g, font, tabs[i], x + 3 + i * (tw + 2), y + 21, tw, 12, Ui.ACCENT, marketTab == i, mx, my);
			hit(x + 3 + i * (tw + 2), y + 21, tw, 12, () -> {
				marketTab = idx;
				listScroll = 0;
			});
		}

		List<ClientState.Ticker> rows = marketRows();
		int visible = listRowsVisible();
		listScroll = clamp(listScroll, 0, Math.max(0, rows.size() - visible));
		boolean showPct = w >= 140;
		int ry = listTop();
		long now = System.currentTimeMillis();
		ClientConfig cfg = ClientConfig.get();
		if (rows.isEmpty()) {
			Ui.textCentered(g, font, ClientState.TICKERS.isEmpty() ? "Loading markets…" : "No matches", x + w / 2, ry + 10, Ui.DIM);
		}
		for (int i = listScroll; i < Math.min(rows.size(), listScroll + visible); i++) {
			ClientState.Ticker t = rows.get(i);
			boolean sel = t.name.equals(coin);
			boolean hover = Ui.inside(mx, my, x + 2, ry, w - 4, 11);
			if (sel) {
				g.fill(x + 2, ry, x + w - 2, ry + 11, Ui.PANEL_HI);
				g.fill(x + 2, ry, x + 3, ry + 11, Ui.ACCENT);
			} else if (hover) {
				g.fill(x + 2, ry, x + w - 2, ry + 11, 0x18FFFFFF);
			}
			boolean watched = cfg.isWatched(t.name);
			Ui.text(g, font, watched ? "★" : "☆", x + 5, ry + 2, watched ? Ui.YELLOW : 0xFF3C5A57);
			double ch = t.dayChangePct();
			int priceRight = showPct ? x + w - 44 : x + w - 6;
			String label = ClientState.display(t.name) + ("unit".equals(t.dex) ? " ◆" : "");
			dev.stonkcraft.client.render.Logos.draw(g, font, t.name, x + 15, ry + 1, 9);
			String px = Fmt.price(t.mid);
			// the name gives way to the price, however many digits a small coin's price has
			Ui.text(g, font, Ui.ellipsize(font, label, priceRight - (x + 27) - font.width(px) - 4), x + 27, ry + 2, sel ? Ui.ACCENT : "unit".equals(t.dex) ? Ui.YELLOW : Ui.TEXT);
			int flash = now - t.lastMoveAt < 700 ? (t.lastMove > 0 ? Ui.GREEN : Ui.RED) : (showPct ? Ui.TEXT : Ui.signColor(ch));
			Ui.textRight(g, font, px, priceRight, ry + 2, flash);
			if (showPct) Ui.textRight(g, font, String.format(Locale.ROOT, "%+.1f%%", ch), x + w - 5, ry + 2, Ui.signColor(ch));
			final String name = t.name;
			hit(x + 2, ry, 12, 11, () -> cfg.toggleWatch(name));
			hit(x + 14, ry, w - 16, 11, () -> select(name));
			ry += 11;
		}
		if (rows.size() > visible) {
			int trackH = visible * 11;
			int thumbH = Math.max(8, trackH * visible / rows.size());
			int thumbY = listTop() + (trackH - thumbH) * listScroll / Math.max(1, rows.size() - visible);
			g.fill(x + w - 3, thumbY, x + w - 2, thumbY + thumbH, Ui.ACCENT_DIM);
		}
	}

	private void drawChart(GuiGraphics g, int mx, int my) {
		int x = centerX, y = bodyY, w = centerW, h = chartH;
		Ui.panel(g, x, y, w, h);
		ClientState.Ticker t = ClientState.ticker(coin);

		int vw = Math.min(44, (w - 10) / 4);
		// title row
		// the title row shares its line with the interval chips: shrink, then drop, whatever doesn't fit
		String name = ClientState.display(coin);
		int titleRoom = (chartMode == 0 ? x + w - 6 - INTERVALS.length * 22 : x + w - 6) - 6 - (x + 26);
		String pxS = t == null ? "" : Fmt.price(t.mid);
		String chS = t == null ? "" : Fmt.pct(t.dayChangePct());
		float ts = font.width(name + pxS) * 1.5f + 8 + font.width(chS) + 8 <= titleRoom ? 1.5f : 1.0f;
		boolean showCh = t != null && font.width(name + pxS) * ts + 16 + font.width(chS) <= titleRoom;
		boolean showPx = t != null && font.width(name + pxS) * ts + 8 <= titleRoom;
		dev.stonkcraft.client.render.Logos.draw(g, font, coin, x + 6, y + 4, 16);
		Ui.textScaled(g, font, Ui.ellipsize(font, name, (int) (titleRoom / ts)), x + 26, y + (ts > 1 ? 6 : 8), ts, Ui.ACCENT);
		int tx = x + 26 + (int) (font.width(name) * ts) + 8;
		if (t != null) {
			long now = System.currentTimeMillis();
			int flash = now - t.lastMoveAt < 700 ? (t.lastMove > 0 ? Ui.GREEN : Ui.RED) : Ui.TEXT;
			if (showPx) Ui.textScaled(g, font, pxS, tx, y + (ts > 1 ? 6 : 8), ts, flash);
			tx += (int) (font.width(pxS) * ts) + 8;
			double ch = t.dayChangePct();
			if (showCh) Ui.text(g, font, chS, tx, y + 9, Ui.signColor(ch));
			String info = "unit".equals(t.dex) ? "Backed by goods deposited at a Commodity Exchange, priced at " + ClientState.display(t.apiCoin)
					: t.spot ? String.format(Locale.ROOT, "Spot · 24h Vol %s", Fmt.usdCompact(t.volume))
					: String.format(Locale.ROOT, "%sFunding %.1f%% APR in %s · Mark %s · Oracle %s · 24h Vol %s · %dx", "",
							t.fundingApr(), fundingCountdown(), Fmt.price(t.mark), Fmt.price(t.oracle), Fmt.usdCompact(t.volume), t.maxLeverage);
			if (w - 4 * vw - 22 > 40) Ui.text(g, font, Ui.ellipsize(font, info, w - 4 * vw - 22), x + 8, y + 22, Ui.DIM);
		}
		// view chips: candles / order book / funding / account
		String[] views = {"Chart", "Book", "Funding", "Account"};
		int vx = x + w - 6 - 4 * vw;
		for (int i = 0; i < views.length; i++) {
			int idx = i;
			Ui.chip(g, font, views[i], vx + i * vw, y + 20, vw - 2, 11, Ui.ACCENT_DIM, chartMode == i, mx, my);
			hit(vx + i * vw, y + 20, vw - 2, 11, () -> {
				chartMode = idx;
				lastBookRequest = 0;
				lastFundingRequest = 0;
				lastHistoryRequest = 0;
			});
		}
		if (chartMode == 1) {
			drawBook(g, x + 6, y + 34, w - 12, h - 40);
			return;
		}
		if (chartMode == 2) {
			drawFundingHistory(g, mx, my, x + 6, y + 34, w - 12, h - 40);
			return;
		}
		if (chartMode == 3) {
			drawAccount(g, mx, my, x + 6, y + 34, w - 12, h - 40);
			return;
		}

		// interval chips
		int cw = 20, cx = x + w - 6 - INTERVALS.length * (cw + 2);
		for (String iv : INTERVALS) {
			boolean active = iv.equals(interval);
			Ui.chip(g, font, iv, cx, y + 5, cw, 12, Ui.ACCENT, active, mx, my);
			hit(cx, y + 5, cw, 12, () -> {
				interval = iv;
				requestCandles();
			});
			cx += cw + 2;
		}

		int axisW = t == null ? 44 : Math.max(44, font.width(Fmt.price(t.mid)) + 8);
		int px0 = x + 6, py0 = y + 34, pw = w - 12 - axisW, ph = h - 34 - 14;
		if (pw < 20 || ph < 20) return;

		JsonObject cd = ClientState.candles;
		List<double[]> candles = new ArrayList<>();
		if (cd != null && cd.get("coin").getAsString().equals(coin) && cd.get("interval").getAsString().equals(interval)) {
			for (JsonElement el : cd.getAsJsonArray("candles")) {
				JsonArray a = el.getAsJsonArray();
				candles.add(new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble(),
						a.get(4).getAsDouble(), a.get(5).getAsDouble()});
			}
		}
		if (candles.isEmpty()) {
			if (t != null && t.history().length > 1) {
				Ui.sparkline(g, t.history(), px0, py0, pw, ph, Ui.ACCENT);
			}
			Ui.textCentered(g, font, "Loading chart…", px0 + pw / 2, py0 + ph / 2 - 4, Ui.DIM);
			return;
		}
		// patch the forming candle with the live price so the chart ticks in real time
		if (t != null && !Double.isNaN(t.mid)) {
			double[] last = candles.get(candles.size() - 1);
			last[4] = t.mid;
			last[2] = Math.max(last[2], t.mid);
			last[3] = Math.min(last[3], t.mid);
		}

		int n = Math.min(candles.size(), Math.max(10, pw / 4));
		List<double[]> shown = candles.subList(candles.size() - n, candles.size());
		double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, vmax = 0;
		for (double[] c : shown) {
			lo = Math.min(lo, c[3]);
			hi = Math.max(hi, c[2]);
			vmax = Math.max(vmax, c[5]);
		}
		double pad = (hi - lo) * 0.08 + 1e-12;
		lo -= pad;
		hi += pad;
		final double flo = lo, fhi = hi;
		java.util.function.DoubleToIntFunction yOf = v -> py0 + ph - 1 - (int) Math.round((v - flo) / (fhi - flo) * (ph - 1));

		// grid + axis labels: as many as fit, none where the live price tag or the crosshair label sits
		int lines = Math.max(2, Math.min(4, ph / 22));
		double lastClose = candles.get(candles.size() - 1)[4];
		int tagY = yOf.applyAsInt(lastClose);
		boolean crossOn = mx >= px0 && mx < px0 + pw && my >= py0 && my < py0 + ph;
		for (int i = 0; i <= lines; i++) {
			double v = lo + (hi - lo) * i / lines;
			int gy = yOf.applyAsInt(v);
			g.fill(px0, gy, px0 + pw, gy + 1, Ui.GRID);
			int ly0 = Math.max(py0, Math.min(py0 + ph - 8, gy - 4));
			if (Math.abs(ly0 - (tagY - 3)) < 11 || (crossOn && Math.abs(ly0 - (my - 3)) < 11)) continue;
			Ui.text(g, font, Fmt.price(v), px0 + pw + 4, ly0, Ui.DIM);
		}

		double slot = (double) pw / n;
		int bodyW = Math.max(1, (int) Math.floor(slot * 0.65));
		int hovered = -1;
		for (int i = 0; i < n; i++) {
			double[] c = shown.get(i);
			int cxm = px0 + (int) (slot * i + slot / 2);
			boolean up = c[4] >= c[1];
			int col = up ? Ui.GREEN : Ui.RED;
			// volume
			if (vmax > 0) {
				int vh = (int) (c[5] / vmax * ph * 0.18);
				g.fill(cxm - bodyW / 2, py0 + ph - vh, cxm - bodyW / 2 + bodyW, py0 + ph, (col & 0xFFFFFF) | 0x30000000);
			}
			int yh = yOf.applyAsInt(c[2]), yl = yOf.applyAsInt(c[3]);
			int yo = yOf.applyAsInt(c[1]), yc = yOf.applyAsInt(c[4]);
			g.fill(cxm, yh, cxm + 1, yl + 1, col);
			g.fill(cxm - bodyW / 2, Math.min(yo, yc), cxm - bodyW / 2 + bodyW, Math.max(yo, yc) + 1, col);
			if (mx >= px0 + slot * i && mx < px0 + slot * (i + 1) && my >= py0 && my < py0 + ph) hovered = i;
		}

		// time labels
		DateTimeFormatter tf = interval.equals("1d") || interval.equals("4h") ? MD : HM;
		int labels = Math.max(2, pw / 70);
		for (int i = 0; i < labels; i++) {
			int idx = (int) ((n - 1) * (i + 0.5) / labels);
			int lx = px0 + (int) (slot * idx + slot / 2);
			Ui.textCentered(g, font, tf.format(Instant.ofEpochMilli((long) shown.get(idx)[0])), lx, py0 + ph + 3, Ui.DIM);
		}

		// last price line + tag
		double[] last = shown.get(n - 1);
		int ly = yOf.applyAsInt(last[4]);
		int lc = last[4] >= last[1] ? Ui.GREEN : Ui.RED;
		for (int dx = 0; dx < pw; dx += 4) g.fill(px0 + dx, ly, px0 + Math.min(pw, dx + 2), ly + 1, (lc & 0xFFFFFF) | 0xA0000000);
		g.fill(px0 + pw + 1, ly - 5, px0 + pw + axisW, ly + 6, lc);
		Ui.text(g, font, Fmt.price(last[4]), px0 + pw + 4, ly - 3, 0xFF061012);

		// entry line for an open position
		JsonObject pos = ClientState.position(coin);
		if (pos != null) {
			double entry = pos.get("entry").getAsDouble();
			if (entry > lo && entry < hi) {
				int ey = yOf.applyAsInt(entry);
				for (int dx = 0; dx < pw; dx += 6) g.fill(px0 + dx, ey, px0 + Math.min(pw, dx + 3), ey + 1, Ui.YELLOW);
				g.fill(px0 + 1, ey - 10, px0 + 3 + font.width("ENTRY"), ey - 1, Ui.PANEL);
				Ui.text(g, font, "ENTRY", px0 + 2, ey - 9, Ui.YELLOW);
			}
			if (pos.has("liq")) {
				double liq = pos.get("liq").getAsDouble();
				if (liq > lo && liq < hi) {
					int ly2 = yOf.applyAsInt(liq);
					for (int dx = 0; dx < pw; dx += 6) g.fill(px0 + dx, ly2, px0 + Math.min(pw, dx + 3), ly2 + 1, Ui.RED);
					g.fill(px0 + 1, ly2 - 10, px0 + 3 + font.width("LIQ"), ly2 - 1, Ui.PANEL);
					Ui.text(g, font, "LIQ", px0 + 2, ly2 - 9, Ui.RED);
				}
			}
		}

		// open orders on this market
		for (JsonElement el : arr(ClientState.hasAccount() ? ClientState.portfolio : null, "orders")) {
			JsonObject o = el.getAsJsonObject();
			if (!o.get("coin").getAsString().equals(coin)) continue;
			double opx = o.get("px").getAsDouble();
			if (opx <= lo || opx >= hi) continue;
			String type = o.get("type").getAsString();
			int oc = type.startsWith("Stop") ? Ui.RED : type.startsWith("Take") ? Ui.GREEN : 0xFF6FA8E8;
			if (o.get("pending").getAsBoolean()) oc = (oc & 0xFFFFFF) | 0x80000000;
			int oy = yOf.applyAsInt(opx);
			for (int dx = 0; dx < pw; dx += 5) g.fill(px0 + dx, oy, px0 + Math.min(pw, dx + 3), oy + 1, oc);
			String tag = type + " " + (o.get("buy").getAsBoolean() ? "buy " : "sell ") + Fmt.size(o.get("size").getAsDouble());
			Ui.textRight(g, font, tag, px0 + pw - 2, oy - 9, oc);
		}

		// crosshair
		if (hovered >= 0) {
			double[] c = shown.get(hovered);
			int hx = px0 + (int) (slot * hovered + slot / 2);
			g.fill(hx, py0, hx + 1, py0 + ph, 0x40FFFFFF);
			g.fill(px0, my, px0 + pw, my + 1, 0x40FFFFFF);
			double v = hi - (double) (my - py0) / (ph - 1) * (hi - lo);
			// the live price tag wins where the two would overlap
			if (Math.abs(my - ly) >= 11) {
				g.fill(px0 + pw + 1, my - 5, px0 + pw + axisW, my + 6, 0xFF2A4A4A);
				Ui.text(g, font, Fmt.price(v), px0 + pw + 4, my - 3, Ui.TEXT);
			}
			String info = String.format(Locale.ROOT, "%s  O %s  H %s  L %s  C %s", tf.format(Instant.ofEpochMilli((long) c[0])),
					Fmt.price(c[1]), Fmt.price(c[2]), Fmt.price(c[3]), Fmt.price(c[4]));
			info = Ui.ellipsize(font, info, pw - 6);
			g.fill(px0 + 1, py0 + 1, px0 + 5 + font.width(info), py0 + 12, 0xD0061012);
			Ui.text(g, font, info, px0 + 3, py0 + 3, c[4] >= c[1] ? Ui.GREEN : Ui.RED);
		}
	}

	private static final String[] TABLE_TABS = {"Positions", "Orders", "TWAP", "Fills", "Funding", "Balances"};
	private static final String[] SHORT_TABS = {"Pos", "Orders", "TWAP", "Fills", "Fund", "Bal"};
	private static final String[] TINY_TABS = {"Pos", "Ord", "TWP", "Fil", "Fnd", "Bal"};

	/** Closing a position takes two clicks within 3s: the first arms that action (keyed "close GOLD"), the second runs it. */
	private String armedAction = "";
	private long armedAt;

	private boolean armed(String key) {
		return key.equals(armedAction) && System.currentTimeMillis() - armedAt < 3000;
	}

	private boolean confirmClick(String key) {
		if (armed(key)) {
			armedAction = "";
			return true;
		}
		armedAction = key;
		armedAt = System.currentTimeMillis();
		return false;
	}

	private void drawBook(GuiGraphics g, int x, int y, int w, int h) {
		JsonObject b = ClientState.book;
		if (b == null || !b.get("coin").getAsString().equals(coin)) {
			Ui.textCentered(g, font, "Loading order book...", x + w / 2, y + h / 2, Ui.DIM);
			return;
		}
		JsonArray bids = b.getAsJsonArray("bids"), asks = b.getAsJsonArray("asks");
		int rows = Math.max(3, Math.min(12, (h - 14) / 2 / 10));
		double maxCum = 0, cum = 0;
		for (int i = 0; i < Math.min(rows, asks.size()); i++) maxCum = Math.max(maxCum, cum += asks.get(i).getAsJsonArray().get(1).getAsDouble());
		cum = 0;
		for (int i = 0; i < Math.min(rows, bids.size()); i++) maxCum = Math.max(maxCum, cum += bids.get(i).getAsJsonArray().get(1).getAsDouble());
		int mid = y + 12 + rows * 10;
		// the size column sits right of the widest price shown, so long prices never run into it
		int priceW = 0, sizeW = 0;
		for (JsonArray side : new JsonArray[]{asks, bids}) {
			for (int i = 0; i < Math.min(rows, side.size()); i++) {
				priceW = Math.max(priceW, font.width(Fmt.price(side.get(i).getAsJsonArray().get(0).getAsDouble())));
				sizeW = Math.max(sizeW, font.width(Fmt.size(side.get(i).getAsJsonArray().get(1).getAsDouble())));
			}
		}
		int sizeX = Math.max(x + w / 2, x + 4 + priceW + 6 + sizeW);
		Ui.text(g, font, "Price", x + 4, y - 1, Ui.DIM);
		// sizes are in the coin itself
		String sizeHead = "Size (" + ClientState.display(coin) + ")";
		Ui.textRight(g, font, font.width(sizeHead) < sizeX - x - 4 - priceW - 6 ? sizeHead : "Size", sizeX, y - 1, Ui.DIM);
		Ui.textRight(g, font, "Total", x + w - 4, y - 1, Ui.DIM);
		cum = 0;
		for (int i = 0; i < Math.min(rows, asks.size()); i++) {
			JsonArray l = asks.get(i).getAsJsonArray();
			cum += l.get(1).getAsDouble();
			int ry = mid - 12 - i * 10;
			int bar = (int) ((w - 8) * cum / Math.max(1e-12, maxCum));
			g.fill(x + w - 4 - bar, ry - 1, x + w - 4, ry + 9, 0x30FF5C6C);
			Ui.text(g, font, Fmt.price(l.get(0).getAsDouble()), x + 4, ry, Ui.RED);
			Ui.textRight(g, font, Fmt.size(l.get(1).getAsDouble()), sizeX, ry, Ui.TEXT);
			if (x + w - 4 - font.width(Fmt.size(cum)) > sizeX + 4) Ui.textRight(g, font, Fmt.size(cum), x + w - 4, ry, Ui.DIM);
		}
		if (!bids.isEmpty() && !asks.isEmpty()) {
			double bb = bids.get(0).getAsJsonArray().get(0).getAsDouble(), ba = asks.get(0).getAsJsonArray().get(0).getAsDouble();
			double spread = ba - bb;
			g.fill(x, mid - 1, x + w, mid + 9, 0x40081214);
			String sp = "Spread " + (spread >= 1 ? String.format(java.util.Locale.ROOT, "%,.2f", spread) : Fmt.price(spread));
			String bps = String.format(java.util.Locale.ROOT, "  (%.2f bps)", spread / ((ba + bb) / 2) * 10_000);
			Ui.textCentered(g, font, font.width(sp + bps) <= w - 8 ? sp + bps : sp, x + w / 2, mid, Ui.DIM);
		}
		cum = 0;
		for (int i = 0; i < Math.min(rows, bids.size()); i++) {
			JsonArray l = bids.get(i).getAsJsonArray();
			cum += l.get(1).getAsDouble();
			int ry = mid + 12 + i * 10;
			int bar = (int) ((w - 8) * cum / Math.max(1e-12, maxCum));
			g.fill(x + w - 4 - bar, ry - 1, x + w - 4, ry + 9, 0x303FE0A6);
			Ui.text(g, font, Fmt.price(l.get(0).getAsDouble()), x + 4, ry, Ui.GREEN);
			Ui.textRight(g, font, Fmt.size(l.get(1).getAsDouble()), sizeX, ry, Ui.TEXT);
			if (x + w - 4 - font.width(Fmt.size(cum)) > sizeX + 4) Ui.textRight(g, font, Fmt.size(cum), x + w - 4, ry, Ui.DIM);
		}
	}

	private void drawFundingHistory(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
		ClientState.Ticker t = ClientState.ticker(coin);
		if (t != null && t.spot) {
			Ui.textCentered(g, font, "Spot markets don't pay funding", x + w / 2, y + h / 2, Ui.DIM);
			return;
		}
		JsonObject f = ClientState.fundingHistory;
		if (f == null || !f.get("coin").getAsString().equals(coin) || f.getAsJsonArray("rates").isEmpty()) {
			Ui.textCentered(g, font, "Loading funding history...", x + w / 2, y + h / 2, Ui.DIM);
			return;
		}
		JsonArray rates = f.getAsJsonArray("rates");
		double maxAbs = 1e-9, sum24 = 0;
		int n = rates.size();
		for (int i = 0; i < n; i++) {
			double r = rates.get(i).getAsJsonArray().get(1).getAsDouble();
			maxAbs = Math.max(maxAbs, Math.abs(r));
			if (i >= n - 24) sum24 += r;
		}
		double avg24 = sum24 / Math.min(24, n);
		int top = y + 14, plotH = h - 28, zero = top + plotH / 2;
		g.fill(x, zero, x + w, zero + 1, Ui.BORDER);
		double slot = (double) w / n;
		int hovered = -1;
		for (int i = 0; i < n; i++) {
			double r = rates.get(i).getAsJsonArray().get(1).getAsDouble();
			int bh = (int) Math.round(Math.abs(r) / maxAbs * (plotH / 2 - 2));
			int bx = x + (int) (i * slot);
			int bw = Math.max(1, (int) slot - 1);
			if (r >= 0) g.fill(bx, zero - bh, bx + bw, zero, Ui.GREEN);
			else g.fill(bx, zero + 1, bx + bw, zero + 1 + bh, Ui.RED);
			if (mx >= bx && mx < bx + bw + 1 && my >= top && my < top + plotH) hovered = i;
		}
		// the hovered hour replaces the summary line, so it never covers the bars
		if (hovered >= 0) {
			JsonArray row = rates.get(hovered).getAsJsonArray();
			double r = row.get(1).getAsDouble();
			String tip = HM.format(Instant.ofEpochMilli(row.get(0).getAsLong())) + String.format(java.util.Locale.ROOT, " paid %.4f%%/h (%.1f%% APR)", r * 100, r * 24 * 365 * 100);
			Ui.text(g, font, Ui.ellipsize(font, tip, w - 4), x + 2, y, r >= 0 ? Ui.GREEN : Ui.RED);
		} else {
			Ui.text(g, font, Ui.ellipsize(font, String.format(java.util.Locale.ROOT, "Funding now %.2f%% APR · paid over 24h %.2f%% APR · longs pay when positive",
					t == null ? 0 : t.fundingApr(), avg24 * 24 * 365 * 100), w - 4), x + 2, y, Ui.DIM);
		}
		// the scale: bars reach these heights at the largest hourly rate shown
		String scale = String.format(java.util.Locale.ROOT, "%.1f%% APR", maxAbs * 24 * 365 * 100);
		// backed, so the bars never hide the scale
		g.fill(x + w - 3 - font.width("+" + scale), top - 1, x + w - 1, top + 8, Ui.PANEL);
		g.fill(x + w - 3 - font.width("−" + scale), top + plotH - 10, x + w - 1, top + plotH - 1, Ui.PANEL);
		Ui.textRight(g, font, "+" + scale, x + w - 2, top, 0xFF4E6E6A);
		Ui.textRight(g, font, "−" + scale, x + w - 2, top + plotH - 9, 0xFF4E6E6A);
		Ui.text(g, font, "72h ago", x, top + plotH + 3, Ui.DIM);
		Ui.textRight(g, font, "now", x + w, top + plotH + 3, Ui.DIM);
	}

	/** Time to the next hourly funding payment. */
	private static String fundingCountdown() {
		long left = 3_600_000L - System.currentTimeMillis() % 3_600_000L;
		return String.format(Locale.ROOT, "%02d:%02d", left / 60_000, left / 1000 % 60);
	}

	private static double num(@org.jetbrains.annotations.Nullable JsonObject o, String key, double def) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : def;
	}

	private static int ratioColor(double ratio) {
		return ratio >= 0.8 ? Ui.RED : ratio >= 0.5 ? Ui.YELLOW : Ui.GREEN;
	}

	/** The account summary: what it's worth, how close it is to trouble, and how it's been going. */
	private void drawAccount(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
		JsonObject pf = ClientState.hasAccount() ? ClientState.portfolio : null;
		if (pf == null) {
			Ui.textCentered(g, font, "No account", x + w / 2, y + h / 2, Ui.DIM);
			return;
		}
		boolean chart = w >= 300;
		int sw = chart ? Math.min(190, w / 2) : w;
		boolean unified = pf.has("unified") && pf.get("unified").getAsBoolean();
		boolean pm = pf.has("portfolioMargin") && pf.get("portfolioMargin").getAsBoolean();
		double ratio = num(pf, "marginRatio", 0);
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[]{"Account value", Fmt.usd(pf.get("equity").getAsDouble())});
		rows.add(new String[]{"Type", pm ? "Portfolio Margin" : unified ? "Unified" : "Manual"});
		rows.add(new String[]{pm ? "Portfolio ratio" : unified ? "Account ratio" : "Margin ratio", ""});
		rows.add(new String[]{"Maint. margin", Fmt.usd(num(pf, "maint", 0))});
		rows.add(new String[]{"Leverage", String.format(Locale.ROOT, "%.2fx", num(pf, "accountLeverage", 0))});
		if (!unified) {
			rows.add(new String[]{"Perps equity", Fmt.usd(num(pf, "perpEquity", 0))});
			rows.add(new String[]{"Spot equity", Fmt.usd(num(pf, "spotEquity", 0))});
			if (pf.has("dexes")) for (var e : pf.getAsJsonObject("dexes").entrySet()) {
				rows.add(new String[]{"· on " + Fmt.pool(e.getKey()), Fmt.usd(e.getValue().getAsJsonObject().get("usdc").getAsDouble())});
			}
		}
		JsonObject fee = pf.has("feeInfo") ? pf.getAsJsonObject("feeInfo") : null;
		if (fee != null) {
			rows.add(new String[]{"Fee tier " + fee.get("tier").getAsInt(), String.format(Locale.ROOT, "%.3f%% / %.3f%%", fee.get("perpTaker").getAsDouble() * 100, fee.get("perpMaker").getAsDouble() * 100)});
			double next = fee.get("next").getAsDouble();
			rows.add(new String[]{"14D volume", Fmt.usdCompact(fee.get("volume").getAsDouble()) + (next > 0 ? " / " + Fmt.usdCompact(next) : "")});
		}
		rows.add(new String[]{"Max drawdown", Fmt.usd(num(pf, "drawdown", 0))});
		rows.add(new String[]{"Fees paid", Fmt.usd(num(pf, "fees", 0))});
		int ry = y;
		for (String[] r : rows) {
			if (ry + 9 > y + h) break;
			// the value keeps its room; the label gives way
			int valueW = Math.min(font.width(r[1]), sw - 40);
			Ui.text(g, font, Ui.ellipsize(font, r[0], sw - valueW - 8), x + 2, ry, Ui.DIM);
			if (r[1].isEmpty()) {
				// the gauge: full at 100%, where the account liquidates
				String pct = String.format(Locale.ROOT, "%.1f%%", ratio * 100);
				int gw = Math.max(0, sw - font.width(r[0]) - font.width(pct) - 14);
				if (gw >= 12) {
					int gx = x + 2 + font.width(r[0]) + 5;
					g.fill(gx, ry + 2, gx + gw, ry + 6, 0xFF081214);
					g.fill(gx, ry + 2, gx + (int) (gw * Math.min(1, ratio)), ry + 6, ratioColor(ratio));
				}
				Ui.textRight(g, font, pct, x + sw - 2, ry, ratioColor(ratio));
			} else {
				Ui.textRight(g, font, Ui.ellipsize(font, r[1], valueW), x + sw - 2, ry, Ui.TEXT);
			}
			ry += 10;
		}
		if (!chart) return;
		// the chart: the last hour from the portfolio, the last month from the history
		int cx = x + sw + 8, cw = w - sw - 8;
		Ui.chip(g, font, "1H", cx, y - 1, 22, 11, Ui.ACCENT_DIM, !accountMonth, mx, my);
		hit(cx, y - 1, 22, 11, () -> accountMonth = false);
		Ui.chip(g, font, "30D", cx + 24, y - 1, 26, 11, Ui.ACCENT_DIM, accountMonth, mx, my);
		hit(cx + 24, y - 1, 26, 11, () -> {
			accountMonth = true;
			lastHistoryRequest = 0;
		});
		Ui.chip(g, font, "Value", cx + cw - 58, y - 1, 30, 11, Ui.ACCENT_DIM, !accountPnl, mx, my);
		hit(cx + cw - 58, y - 1, 30, 11, () -> accountPnl = false);
		Ui.chip(g, font, "PnL", cx + cw - 26, y - 1, 26, 11, Ui.ACCENT_DIM, accountPnl, mx, my);
		hit(cx + cw - 26, y - 1, 26, 11, () -> accountPnl = true);
		double deposits = num(pf, "start", 0);
		double[] data;
		JsonObject hist = ClientState.history;
		if (accountMonth && hist != null && hist.has("longEquity")) {
			JsonArray eq = hist.getAsJsonArray("longEquity"), dep = hist.getAsJsonArray("longDeposits");
			data = new double[eq.size()];
			for (int i = 0; i < data.length; i++) data[i] = eq.get(i).getAsDouble() - (accountPnl ? dep.get(i).getAsDouble() : 0);
		} else {
			JsonArray eq = arr(pf, "equityHistory");
			data = new double[eq.size()];
			for (int i = 0; i < data.length; i++) data[i] = eq.get(i).getAsDouble() - (accountPnl ? deposits : 0);
		}
		int top = y + 13, ph = h - 13 - 10;
		if (data.length < 2 || ph < 16) {
			Ui.textCentered(g, font, "Not enough history yet", cx + cw / 2, y + h / 2, Ui.DIM);
			return;
		}
		double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
		for (double d : data) {
			lo = Math.min(lo, d);
			hi = Math.max(hi, d);
		}
		Ui.sparkline(g, data, cx, top, cw, ph, Ui.signColor(data[data.length - 1] - data[0]) | 0xFF000000);
		Ui.text(g, font, Ui.ellipsize(font, Fmt.usdCompact(hi) + " high · " + Fmt.usdCompact(lo) + " low", cw - 30), cx, top + ph + 2, Ui.DIM);
		Ui.textRight(g, font, "now", cx + cw, top + ph + 2, Ui.DIM);
	}

	private void drawTable(GuiGraphics g, int mx, int my) {
		int x = centerX, y = bodyY + chartH + 4, w = centerW, h = bodyH - chartH - 4;
		Ui.panel(g, x, y, w, h);
		JsonObject pf = ClientState.hasAccount() ? ClientState.portfolio : null;
		JsonArray positions = arr(pf, "positions");
		JsonArray orders = arr(pf, "orders");
		JsonArray fills = arr(pf, "fills");
		JsonArray balances = arr(pf, "balances");
		JsonArray twaps = arr(pf, "twaps");
		JsonArray funding = arr(pf, "funding");
		int[] counts = {positions.size(), orders.size(), twaps.size(), -1, -1, balances.size()};
		int full = 0;
		for (int i = 0; i < TABLE_TABS.length; i++) full += font.width(TABLE_TABS[i] + (counts[i] > 0 ? " (" + counts[i] + ")" : "")) + 12;
		boolean compact = full > w - 10;
		int shortFull = 0;
		for (int i = 0; i < TABLE_TABS.length; i++) shortFull += font.width(SHORT_TABS[i] + (counts[i] > 0 ? " " + counts[i] : "")) + 12;
		boolean tiny = shortFull > w - 10;

		int tx = x + 5;
		for (int i = 0; i < TABLE_TABS.length; i++) {
			String label = tiny ? TINY_TABS[i] + (counts[i] > 0 ? counts[i] : "")
					: (compact ? SHORT_TABS[i] : TABLE_TABS[i]) + (counts[i] > 0 ? (compact ? " " + counts[i] : " (" + counts[i] + ")") : "");
			int tw = font.width(label) + (tiny ? 6 : 10);
			Ui.chip(g, font, label, tx, y + 4, tw, 12, Ui.ACCENT, tab == i, mx, my);
			int idx = i;
			hit(tx, y + 4, tw, 12, () -> {
				tab = idx;
				tableScroll = 0;
			});
			tx += tw + 2;
		}
		long dead = pf != null && pf.has("scheduledCancel") ? pf.get("scheduledCancel").getAsLong() : 0;
		if (tab == 1 && pf != null) {
			// order actions sit on the column-header row, right of the (empty) flags column header
			int rx = x + w - 5, ay = y + 19;
			// never over the Price header: shorter labels on a narrow table, and the dead-man chip goes first
			int limit = x + (int) (w * 0.70);
			boolean narrow = rx - 58 - font.width("\u23F1 Dead-man 60s") - 10 < limit;
			if (!orders.isEmpty()) {
				String ca = narrow ? "\u2715 All" : "Cancel all";
				int cw = font.width(ca) + 10;
				Ui.chip(g, font, ca, rx - cw, ay, cw, 11, Ui.RED, false, mx, my);
				hit(rx - cw, ay, cw, 11, () -> ClientNet.send("cancel_all", new JsonObject()));
				rx -= cw + 3;
			}
			String left = dead > 0 ? Math.max(0, (dead - System.currentTimeMillis()) / 1000) + "s \u2715" : narrow ? "60s" : "Dead-man 60s";
			String dm = "\u23F1 " + left;
			int dw = font.width(dm) + 10;
			if (rx - dw >= limit) {
				Ui.chip(g, font, dm, rx - dw, ay, dw, 11, Ui.YELLOW, dead > 0, mx, my);
				hit(rx - dw, ay, dw, 11, () -> {
					JsonObject o = new JsonObject();
					o.addProperty("seconds", dead > 0 ? 0 : 60);
					ClientNet.send("schedule_cancel", o);
				});
			}
		} else if (tab == 3 && pf != null) {
			// the Fills tab's other half
			String mode = orderHistoryMode ? (compact ? "Fills" : "Trade history") : compact ? "Ord" : "Order history";
			int mw = font.width(mode) + 8;
			if (tx + mw < x + w - 4) {
				Ui.chip(g, font, mode, x + w - 5 - mw, y + 4, mw, 12, Ui.ACCENT_DIM, false, mx, my);
				hit(x + w - 5 - mw, y + 4, mw, 12, () -> {
					orderHistoryMode = !orderHistoryMode;
					tableScroll = 0;
				});
			}
		} else if (tab == 0 && !positions.isEmpty()) {
			ClientConfig cfg = ClientConfig.get();
			boolean holding = holdCloseSince > 0;
			String ca = holding ? "Hold..." : armed("close all") ? "Sure?" : compact ? "✕ All" : "Close all";
			int cw = font.width(ca) + 8;
			if (tx + cw < x + w - 4) {
				Ui.chip(g, font, ca, x + w - 5 - cw, y + 4, cw, 12, Ui.RED, holding || armed("close all"), mx, my);
				hit(x + w - 5 - cw, y + 4, cw, 12, () -> {
					if (cfg.holdToCloseAll) holdCloseSince = System.currentTimeMillis();
					else if (!cfg.confirmCloses || confirmClick("close all")) closeAll();
				});
			}
		} else if (tab != 5 && pf != null && pf.has("margin")) {
			String m = "Margin " + Fmt.usd(pf.get("margin").getAsDouble());
			if (tx + font.width(m) < x + w - 8) Ui.textRight(g, font, m, x + w - 6, y + 6, Ui.DIM);
		}

		int hy = y + 20;
		int rowsVisible = Math.max(1, (h - 32) / 10);
		g.fill(x + 3, hy + 9, x + w - 3, hy + 10, Ui.BORDER);
		int ry = hy + 12;
		switch (tab) {
			case 0 -> {
				// narrow: keep what you act on (coin, size, PnL); the rest comes back as there's room, and on hover
				int layout = w < 320 ? 0 : w < 480 ? 1 : 2;
				int wc = w - 11;
				int[] c = layout == 0 ? cols(wc, new double[]{0, 0.34, 0.66})
						: layout == 1 ? cols(wc, new double[]{0, 0.17, 0.32, 0.45, 0.70, 0.86})
						: cols(wc, new double[]{0, 0.11, 0.21, 0.29, 0.38, 0.47, 0.61, 0.70, 0.80, 0.88});
				if (layout == 0) header(g, x, hy, c, wc, "Asset", "Size (qty)|Size", "PnL");
				else if (layout == 1) header(g, x, hy, c, wc, "Asset", "Size", "Value", "PnL (ROE)|PnL", "Liq", "Margin");
				else header(g, x, hy, c, wc, "Asset", "Size", "Value", "Entry", "Mark", "PnL (ROE)", "Liq.", "Margin", "Funding", "TP/SL");
				if (positions.isEmpty()) Ui.textCentered(g, font, "No open positions. Trade here, or wire up some redstone.", x + w / 2, hy + 22, Ui.DIM);
				tableScroll = clamp(tableScroll, 0, Math.max(0, positions.size() - rowsVisible));
				JsonObject hovered = null;
				for (int i = tableScroll; i < Math.min(positions.size(), tableScroll + rowsVisible); i++) {
					JsonObject p = positions.get(i).getAsJsonObject();
					String cn = p.get("coin").getAsString();
					double size = p.get("size").getAsDouble();
					double upnl = p.get("upnl").getAsDouble();
					boolean rowHov = Ui.inside(mx, my, x + 3, ry - 1, w - 6, 10);
					if (rowHov) {
						g.fill(x + 3, ry - 1, x + w - 3, ry + 9, 0x2012FFE0);
						hovered = p;
					}
					g.fill(x + 3, ry - 1, x + 4, ry + 9, size > 0 ? Ui.GREEN : Ui.RED);
					String lev = p.has("lev") ? " " + p.get("lev").getAsInt() + "x" : "";
					cell(g, ClientState.display(cn) + (layout == 0 ? "" : lev), x, c, 0, wc, ry, cn.equals(coin) ? Ui.ACCENT : Ui.TEXT);
					cell(g, (size > 0 ? "" : "-") + Fmt.size(Math.abs(size)), x, c, 1, wc, ry, size > 0 ? Ui.GREEN : Ui.RED);
					boolean iso = p.has("cross") && !p.get("cross").getAsBoolean();
					double roe = num(p, "roe", Double.NaN);
					String pnl = Fmt.usdCompactSigned(upnl) + (Double.isNaN(roe) ? "" : String.format(Locale.ROOT, " (%+.1f%%)", roe));
					String liq = p.has("liq") ? Fmt.price(p.get("liq").getAsDouble()) : "--";
					String margin = p.has("margin") ? Fmt.usdCompact(p.get("margin").getAsDouble()) + (iso ? " iso" : "") : "--";
					String value = p.has("value") ? Fmt.usdCompact(p.get("value").getAsDouble()) : "--";
					if (layout == 0) {
						cell(g, Fmt.usdSigned(upnl), x, c, 2, wc, ry, Ui.signColor(upnl));
					} else if (layout == 1) {
						cell(g, value, x, c, 2, wc, ry, Ui.TEXT);
						cell(g, pnl, x, c, 3, wc, ry, Ui.signColor(upnl));
						cell(g, liq, x, c, 4, wc, ry, Ui.DIM);
						cell(g, margin, x, c, 5, wc, ry, Ui.DIM);
					} else {
						cell(g, value, x, c, 2, wc, ry, Ui.TEXT);
						cell(g, Fmt.price(p.get("entry").getAsDouble()), x, c, 3, wc, ry, Ui.TEXT);
						cell(g, Fmt.price(p.get("mark").getAsDouble()), x, c, 4, wc, ry, Ui.TEXT);
						cell(g, pnl, x, c, 5, wc, ry, Ui.signColor(upnl));
						cell(g, liq, x, c, 6, wc, ry, Ui.DIM);
						cell(g, margin, x, c, 7, wc, ry, Ui.DIM);
						double posFunding = num(p, "funding", 0);
						cell(g, Fmt.usdCompactSigned(posFunding), x, c, 8, wc, ry, posFunding == 0 ? Ui.DIM : Ui.signColor(posFunding));
						cell(g, (p.has("tp") ? Fmt.price(p.get("tp").getAsDouble()) : "--") + "/" + (p.has("sl") ? Fmt.price(p.get("sl").getAsDouble()) : "--"), x, c, 9, wc, ry, Ui.DIM);
					}
					int bx = x + w - 16;
					boolean hov = Ui.inside(mx, my, bx, ry - 1, 11, 10);
					Ui.text(g, font, armed("close " + cn) ? "?" : "✕", bx + 2, ry, armed("close " + cn) ? Ui.RED : hov ? Ui.YELLOW : Ui.DIM);
					hit(bx, ry - 1, 11, 10, () -> { if (!ClientConfig.get().confirmCloses || confirmClick("close " + cn)) ClientNet.order(cn, "CLOSE", 0); });
					// manage it: limit close, reverse, TP/SL, margin
					boolean editable = pf != null && !"WATCH".equals(pf.has("mode") ? pf.get("mode").getAsString() : "");
					if (editable) {
						boolean mhov = Ui.inside(mx, my, bx - 11, ry - 1, 11, 10);
						Ui.text(g, font, "⋯", bx - 9, ry, mhov ? Ui.ACCENT : Ui.DIM);
						hit(bx - 11, ry - 1, 11, 10, () -> minecraft.setScreen(new PositionScreen(cn, this)));
					}
					hit(x + 3, ry - 1, w - 33, 10, () -> select(cn));
					ry += 10;
				}
				if (hovered != null && layout < 2 && !Ui.inside(mx, my, x + w - 27, hy, 24, h)) positionTooltip(g, hovered, mx, my);
			}			case 1 -> {
				boolean wideOrders = w >= 480;
				int[] c = wideOrders ? cols(w, new double[]{0, 0.10, 0.25, 0.32, 0.42, 0.52, 0.62, 0.72, 0.87})
						: cols(w, new double[]{0, 0.13, 0.30, 0.41, 0.55, 0.70});
				if (wideOrders) header(g, x, hy, c, w, "Asset", "Type", "Side", "Size", "Original", "Value", "Price", "Trigger", "");
				else header(g, x, hy, c, w, "Asset", "Type", "Side", "Size (qty)|Size", "Price ($)|Price", "");
				if (orders.isEmpty()) Ui.textCentered(g, font, "No open orders", x + w / 2, hy + 22, Ui.DIM);
				tableScroll = clamp(tableScroll, 0, Math.max(0, orders.size() - rowsVisible));
				for (int i = tableScroll; i < Math.min(orders.size(), tableScroll + rowsVisible); i++) {
					JsonObject o = orders.get(i).getAsJsonObject();
					String cn = o.get("coin").getAsString();
					boolean buy = o.get("buy").getAsBoolean();
					long oid = o.get("oid").getAsLong();
					boolean pending = o.get("pending").getAsBoolean();
					int dim = pending ? 0xFF4E6E6A : Ui.TEXT;
					if (Ui.inside(mx, my, x + 3, ry - 1, w - 6, 10)) g.fill(x + 3, ry - 1, x + w - 3, ry + 9, 0x2012FFE0);
					cell(g, ClientState.display(cn), x, c, 0, w, ry, cn.equals(coin) ? Ui.ACCENT : dim);
					cell(g, Ui.ellipsize(font, o.get("type").getAsString(), c[2] - c[1] - 2), x, c, 1, w, ry, dim);
					cell(g, buy ? "Buy" : "Sell", x, c, 2, w, ry, pending ? dim : buy ? Ui.GREEN : Ui.RED);
					cell(g, Fmt.size(o.get("size").getAsDouble()), x, c, 3, w, ry, dim);
					String flags = (o.get("reduceOnly").getAsBoolean() ? "RO " : "") + (pending ? "armed on fill" : "");
					if (wideOrders) {
						double orderPx = o.has("limit") ? o.get("limit").getAsDouble() : o.get("px").getAsDouble();
						cell(g, Fmt.size(num(o, "orig", o.get("size").getAsDouble())), x, c, 4, w, ry, dim);
						cell(g, Fmt.usdCompact(o.get("size").getAsDouble() * orderPx), x, c, 5, w, ry, dim);
						cell(g, o.has("trigger") && !o.has("limit") ? "Market" : Fmt.price(orderPx), x, c, 6, w, ry, dim);
						cell(g, o.has("trigger") ? o.get("trigger").getAsString() : "N/A", x, c, 7, w, ry, dim);
						cell(g, Ui.ellipsize(font, flags, w - 24 - c[8] - 12), x, c, 8, w, ry, 0xFF4E6E6A);
					} else {
						cell(g, Fmt.price(o.get("px").getAsDouble()), x, c, 4, w, ry, dim);
						cell(g, Ui.ellipsize(font, flags, w - 24 - c[5] - 12), x, c, 5, w, ry, 0xFF4E6E6A);
					}
					int bx = x + w - 16;
					boolean hov = Ui.inside(mx, my, bx, ry - 1, 11, 10);
					Ui.text(g, font, "✕", bx + 2, ry, hov ? Ui.RED : Ui.DIM);
					hit(bx, ry - 1, 11, 10, () -> {
						JsonObject req = new JsonObject();
						req.addProperty("coin", cn);
						req.addProperty("oid", oid);
						ClientNet.send("cancel", req);
					});
					hit(x + 3, ry - 1, w - 22, 10, () -> select(cn));
					ry += 10;
				}
			}
			case 2 -> {
				int[] c = cols(w, new double[]{0, 0.13, 0.25, 0.55, 0.82});
				header(g, x, hy, c, w, "Asset", "Side", "Done / total", "Progress", "Ends");
				if (twaps.isEmpty()) Ui.textCentered(g, font, Ui.ellipsize(font, "No running TWAPs. Pick TWAP in the order panel.", w - 12), x + w / 2, hy + 22, Ui.DIM);
				long now = System.currentTimeMillis();
				for (int i = 0; i < Math.min(twaps.size(), rowsVisible); i++) {
					JsonObject t = twaps.get(i).getAsJsonObject();
					String cn = t.get("coin").getAsString();
					boolean buy = t.get("buy").getAsBoolean();
					double total = t.get("total").getAsDouble(), done = t.get("filled").getAsDouble();
					long end = t.get("start").getAsLong() + t.get("minutes").getAsLong() * 60_000L;
					cell(g, ClientState.display(cn), x, c, 0, w, ry, cn.equals(coin) ? Ui.ACCENT : Ui.TEXT);
					cell(g, buy ? "Buy" : "Sell", x, c, 1, w, ry, buy ? Ui.GREEN : Ui.RED);
					cell(g, Ui.ellipsize(font, Fmt.size(done) + " / " + Fmt.size(total), c[3] - c[2] - 6), x, c, 2, w, ry, Ui.TEXT);
					int bw = c[4] - c[3] - 8;
					g.fill(x + 6 + c[3], ry + 1, x + 6 + c[3] + bw, ry + 7, 0xFF081214);
					g.fill(x + 6 + c[3], ry + 1, x + 6 + c[3] + (int) (bw * Math.min(1, done / Math.max(1e-12, total))), ry + 7, buy ? Ui.GREEN : Ui.RED);
					cell(g, Math.max(0, (end - now) / 60_000) + "m", x, c, 4, w, ry, Ui.DIM);
					long id = t.get("id").getAsLong();
					int bx = x + w - 16;
					boolean hov = Ui.inside(mx, my, bx, ry - 1, 11, 10);
					Ui.text(g, font, "\u2715", bx + 2, ry, hov ? Ui.RED : Ui.DIM);
					hit(bx, ry - 1, 11, 10, () -> {
						JsonObject req = new JsonObject();
						req.addProperty("coin", cn);
						req.addProperty("id", id);
						ClientNet.send("twap_cancel", req);
					});
					ry += 10;
				}
			}
			case 4 -> {
				boolean wideFunding = w >= 400;
				int[] c = wideFunding ? cols(w, new double[]{0, 0.14, 0.30, 0.46, 0.56, 0.78}) : cols(w, new double[]{0, 0.20, 0.42, 0.68});
				if (wideFunding) header(g, x, hy, c, w, "Time", "Asset", "Size", "Side", "Payment", "Rate /h");
				else header(g, x, hy, c, w, "Time", "Asset", "Payment", "Rate /h");
				double total = 0;
				for (JsonElement el : funding) total += el.getAsJsonObject().get("usdc").getAsDouble();
				if (!funding.isEmpty()) Ui.textRight(g, font, "Total " + Fmt.usdSigned(total), x + w - 6, hy, Ui.signColor(total));
				if (funding.isEmpty()) Ui.textCentered(g, font, Ui.ellipsize(font, "No funding payments yet. Perps settle funding every hour.", w - 12), x + w / 2, hy + 22, Ui.DIM);
				for (int i = 0; i < Math.min(funding.size(), rowsVisible); i++) {
					JsonObject f = funding.get(i).getAsJsonObject();
					double usdc = f.get("usdc").getAsDouble();
					cell(g, HM.format(Instant.ofEpochMilli(f.get("t").getAsLong())), x, c, 0, w, ry, Ui.DIM);
					cell(g, ClientState.display(f.get("coin").getAsString()), x, c, 1, w, ry, Ui.TEXT);
					String rate = String.format(java.util.Locale.ROOT, "%.4f%%", f.get("rate").getAsDouble() * 100);
					if (wideFunding) {
						double fsize = num(f, "size", 0);
						cell(g, Fmt.size(Math.abs(fsize)), x, c, 2, w, ry, Ui.TEXT);
						cell(g, fsize == 0 ? "--" : fsize > 0 ? "Long" : "Short", x, c, 3, w, ry, fsize > 0 ? Ui.GREEN : Ui.RED);
						cell(g, Fmt.usdSigned(usdc), x, c, 4, w, ry, Ui.signColor(usdc));
						cell(g, rate, x, c, 5, w, ry, Ui.DIM);
					} else {
						cell(g, Fmt.usdSigned(usdc), x, c, 2, w, ry, Ui.signColor(usdc));
						cell(g, rate, x, c, 3, w, ry, Ui.DIM);
					}
					ry += 10;
				}
			}
			case 3 -> {
				// the deep history once it's here, unless a newer fill has come in the portfolio since
				JsonObject deep = ClientState.history;
				if (orderHistoryMode) {
					JsonArray rows = deep != null && deep.has("orderHistory") && deep.getAsJsonArray("orderHistory").size() >= arr(pf, "orderHistory").size()
							? deep.getAsJsonArray("orderHistory") : arr(pf, "orderHistory");
					int[] c = w >= 400 ? cols(w, new double[]{0, 0.15, 0.27, 0.45, 0.55, 0.68, 0.82}) : cols(w, new double[]{0, 0.20, 0.42, 0.72});
					if (w >= 400) header(g, x, hy, c, w, "Time", "Asset", "Type", "Side", "Size", "Filled", "Status");
					else header(g, x, hy, c, w, "Time", "Asset", "Type", "Status");
					if (rows.isEmpty()) Ui.textCentered(g, font, "No orders yet", x + w / 2, hy + 22, Ui.DIM);
					tableScroll = clamp(tableScroll, 0, Math.max(0, rows.size() - rowsVisible));
					for (int i = tableScroll; i < Math.min(rows.size(), tableScroll + rowsVisible); i++) {
						JsonObject o = rows.get(i).getAsJsonObject();
						String status = o.get("status").getAsString();
						int sc = status.equals("Filled") ? Ui.GREEN : status.equals("Rejected") ? Ui.RED : Ui.DIM;
						boolean buy = o.get("buy").getAsBoolean();
						cell(g, HM.format(Instant.ofEpochMilli(o.get("t").getAsLong())), x, c, 0, w, ry, Ui.DIM);
						cell(g, ClientState.display(o.get("coin").getAsString()), x, c, 1, w, ry, Ui.TEXT);
						cell(g, o.get("type").getAsString(), x, c, 2, w, ry, Ui.TEXT);
						if (w >= 400) {
							cell(g, buy ? "Buy" : "Sell", x, c, 3, w, ry, buy ? Ui.GREEN : Ui.RED);
							cell(g, Fmt.size(o.get("size").getAsDouble()), x, c, 4, w, ry, Ui.TEXT);
							cell(g, Fmt.size(o.get("filled").getAsDouble()), x, c, 5, w, ry, Ui.TEXT);
							cell(g, status, x, c, 6, w, ry, sc);
						} else {
							cell(g, status, x, c, 3, w, ry, sc);
						}
						ry += 10;
					}
					return;
				}
				JsonArray rows = fills;
				if (deep != null && deep.has("fills") && deep.getAsJsonArray("fills").size() >= fills.size()) {
					JsonArray d = deep.getAsJsonArray("fills");
					boolean fresh = fills.isEmpty() || !d.isEmpty() && d.get(0).getAsJsonObject().get("t").getAsLong() >= fills.get(0).getAsJsonObject().get("t").getAsLong();
					if (fresh) rows = d;
				}
				boolean wideFills = w >= 400;
				int[] c = wideFills ? cols(w, new double[]{0, 0.14, 0.26, 0.40, 0.54, 0.68, 0.80}) : cols(w, new double[]{0, 0.17, 0.36, 0.54, 0.74});
				if (wideFills) header(g, x, hy, c, w, "Time", "Asset", "Size", "Price", "Value", "Fee", "Closed PnL");
				else header(g, x, hy, c, w, "Time", "Asset", "Size (qty)|Size", "Price ($)|Price", "PnL");
				if (rows.isEmpty()) Ui.textCentered(g, font, "No fills yet", x + w / 2, hy + 22, Ui.DIM);
				tableScroll = clamp(tableScroll, 0, Math.max(0, rows.size() - rowsVisible));
				for (int i = tableScroll; i < Math.min(rows.size(), tableScroll + rowsVisible); i++) {
					JsonObject f = rows.get(i).getAsJsonObject();
					double size = f.get("size").getAsDouble();
					double pnl = f.get("pnl").getAsDouble();
					boolean liquidated = f.has("kind");
					cell(g, HMS.format(Instant.ofEpochMilli(f.get("t").getAsLong())), x, c, 0, w, ry, liquidated ? Ui.RED : Ui.DIM);
					cell(g, ClientState.display(f.get("coin").getAsString()), x, c, 1, w, ry, Ui.TEXT);
					cell(g, (size > 0 ? "+" : "-") + Fmt.size(Math.abs(size)), x, c, 2, w, ry, size > 0 ? Ui.GREEN : Ui.RED);
					cell(g, Fmt.price(f.get("px").getAsDouble()), x, c, 3, w, ry, Ui.TEXT);
					// an opening fill realizes nothing: a dash, so the column doesn't look broken
					int pnlCol = wideFills ? 6 : 4;
					if (wideFills) {
						cell(g, Fmt.usdCompact(num(f, "value", Math.abs(size) * f.get("px").getAsDouble())), x, c, 4, w, ry, Ui.TEXT);
						cell(g, Fmt.usd(num(f, "fee", 0)), x, c, 5, w, ry, Ui.DIM);
					}
					cell(g, pnl == 0 ? "—" : Fmt.usdSigned(pnl), x, c, pnlCol, w, ry, pnl == 0 ? Ui.DIM : Ui.signColor(pnl));
					if (liquidated && Ui.inside(mx, my, x + 3, ry - 1, w - 6, 10)) g.renderTooltip(font, Component.literal(f.get("kind").getAsString()), mx, my);
					ry += 10;
				}
			}			default -> {
				int[] c = cols(w, new double[]{0, 0.22, 0.46, 0.70});
				header(g, x, hy, c, w, "Token", "Amount", "Value", "PnL");
				// the transfer sits on the tab row, clear of the column headers
				String tr = pf != null && pf.has("unified") && pf.get("unified").getAsBoolean() ? "Unified ▾" : "⇄ Transfer";
				if (pf != null && pf.has("spotUsdc") && tx + font.width(tr) + 8 < x + w - 8) {
					int trx = x + w - 6 - font.width(tr) - 6;
					Ui.chip(g, font, tr, trx, y + 3, font.width(tr) + 6, 12, Ui.ACCENT, false, mx, my);
					hit(trx, y + 3, font.width(tr) + 6, 12, () -> minecraft.setScreen(new TransferScreen(this, false)));
				}
				// USDC on each side first, as Hyperliquid's Balances table lists it
				if (pf != null && pf.has("spotUsdc") && !(pf.has("unified") && pf.get("unified").getAsBoolean())) {
					for (String[] side : new String[][]{{"USD spot", "spotUsdc", "Spot"}, {"USD perps", "cash", "Perps"}}) {
						double usdc = pf.get(side[1]).getAsDouble();
						cell(g, font.width(side[0]) <= c[1] - c[0] - 3 ? side[0] : side[2], x, c, 0, w, ry, Ui.TEXT);
						cell(g, Fmt.size(usdc), x, c, 1, w, ry, Ui.TEXT);
						cell(g, Fmt.usd(usdc), x, c, 2, w, ry, Ui.TEXT);
						cell(g, "—", x, c, 3, w, ry, Ui.DIM);
						ry += 10;
					}
					// a HIP-3 dex's own USDC, once it has some
					if (pf.has("dexes")) for (var e : pf.getAsJsonObject("dexes").entrySet()) {
						double usdc = e.getValue().getAsJsonObject().get("usdc").getAsDouble();
						if (usdc == 0) continue;
						String label = "USD " + e.getKey();
						cell(g, font.width(label) <= c[1] - c[0] - 3 ? label : e.getKey(), x, c, 0, w, ry, Ui.TEXT);
						cell(g, Fmt.size(usdc), x, c, 1, w, ry, Ui.TEXT);
						cell(g, Fmt.usd(usdc), x, c, 2, w, ry, Ui.TEXT);
						cell(g, "—", x, c, 3, w, ry, Ui.DIM);
						ry += 10;
					}
				}
				int usdcRows = (ry - (hy + 12)) / 10;
				if (balances.isEmpty()) Ui.textCentered(g, font, Ui.ellipsize(font, "No spot tokens. Pick a market in the Spot tab.", w - 12), x + w / 2, ry + 12, Ui.DIM);
				for (int i = 0; i < Math.min(balances.size(), rowsVisible - usdcRows); i++) {
					JsonObject b = balances.get(i).getAsJsonObject();
					double value = b.get("value").getAsDouble();
					double pnl = value - b.get("cost").getAsDouble();
					String market = b.get("market").getAsString();
					cell(g, b.get("token").getAsString(), x, c, 0, w, ry, market.equals(coin) ? Ui.ACCENT : Ui.TEXT);
					cell(g, Fmt.size(b.get("qty").getAsDouble()), x, c, 1, w, ry, Ui.TEXT);
					cell(g, Fmt.usd(value), x, c, 2, w, ry, Ui.TEXT);
					cell(g, Fmt.usdSigned(pnl), x, c, 3, w, ry, Ui.signColor(pnl));
					hit(x + 3, ry - 1, w - 6, 10, () -> select(market));
					ry += 10;
				}
			}
		}
	}

	/** Everything about a position, for a table too narrow to show it. */
	private void positionTooltip(GuiGraphics g, JsonObject p, int mx, int my) {
		List<Component> lines = new ArrayList<>();
		double size = p.get("size").getAsDouble();
		lines.add(Component.literal(ClientState.display(p.get("coin").getAsString()) + (size > 0 ? " long " : " short ") + Fmt.size(Math.abs(size))));
		lines.add(Component.literal("Entry " + Fmt.price(p.get("entry").getAsDouble()) + " · Mark " + Fmt.price(p.get("mark").getAsDouble())));
		if (p.has("value")) lines.add(Component.literal("Value " + Fmt.usd(p.get("value").getAsDouble())));
		lines.add(Component.literal("Liq. price " + (p.has("liq") ? Fmt.price(p.get("liq").getAsDouble()) : "none")));
		if (p.has("margin")) lines.add(Component.literal("Margin " + Fmt.usd(p.get("margin").getAsDouble()) + (p.get("cross").getAsBoolean() ? " (Cross)" : " (Isolated)")));
		if (p.has("funding")) lines.add(Component.literal("Funding since open " + Fmt.usdSigned(p.get("funding").getAsDouble())));
		if (p.has("tp") || p.has("sl")) lines.add(Component.literal("TP " + (p.has("tp") ? Fmt.price(p.get("tp").getAsDouble()) : "--") + " / SL " + (p.has("sl") ? Fmt.price(p.get("sl").getAsDouble()) : "--")));
		g.renderComponentTooltip(font, lines, mx, my);
	}

	private static JsonArray arr(JsonObject o, String key) {
		return o != null && o.has(key) ? o.getAsJsonArray(key) : new JsonArray();
	}

	/** Column headings; "Size (qty)|Size" uses the first form that fits the column. */
	private void header(GuiGraphics g, int x, int y, int[] c, int w, String... labels) {
		for (int i = 0; i < labels.length; i++) {
			int room = (i + 1 < c.length ? c[i + 1] : w - 24) - c[i] - 3;
			String label = labels[i];
			for (String form : labels[i].split("\\|")) {
				label = form;
				if (font.width(form) <= room) break;
			}
			cell(g, label, x, c, i, w, y, Ui.DIM);
		}
	}

	/** Text in table column i, cut to the column's width so it never runs into the next one. */
	private void cell(GuiGraphics g, String s, int x, int[] c, int i, int w, int y, int color) {
		int right = i + 1 < c.length ? c[i + 1] : w - 24;
		Ui.text(g, font, Ui.ellipsize(font, s, right - c[i] - 3), x + 6 + c[i], y, color);
	}

	private static int[] cols(int w, double[] fractions) {
		int[] out = new int[fractions.length];
		for (int i = 0; i < fractions.length; i++) out[i] = (int) ((w - 24) * fractions[i]);
		return out;
	}

	/** The order form's Pro dropdown, as Hyperliquid orders it; spot has only Scale and TWAP (§2.2). */
	private static final OrderRequest.Type[] PRO_TYPES = {OrderRequest.Type.SCALE, OrderRequest.Type.STOP_LIMIT, OrderRequest.Type.STOP,
			OrderRequest.Type.TAKE_LIMIT, OrderRequest.Type.TAKE_PROFIT, OrderRequest.Type.TRAILING, OrderRequest.Type.TWAP};
	private static final String[] TIF_LABELS = {"GTC", "Post", "IOC"};

	private static String proLabel(OrderRequest.Type type) {
		return switch (type) {
			case STOP -> "Stop Market";
			case STOP_LIMIT -> "Stop Limit";
			case TAKE_PROFIT -> "Take Market";
			case TAKE_LIMIT -> "Take Limit";
			case TRAILING -> "Trailing Stop";
			case SCALE -> "Scale";
			case TWAP -> "TWAP";
			default -> type.label;
		};
	}

	private static boolean onSpot(OrderRequest.Type type) {
		return type == OrderRequest.Type.MARKET || type == OrderRequest.Type.LIMIT || type == OrderRequest.Type.SCALE || type == OrderRequest.Type.TWAP;
	}

	/** The market's dex's own balance, if a Manual account margins it from one. */
	@org.jetbrains.annotations.Nullable
	private static JsonObject dexBalance(@org.jetbrains.annotations.Nullable ClientState.Ticker t) {
		JsonObject pf = ClientState.portfolio;
		if (t == null || t.spot || t.dex.isEmpty() || pf == null || !pf.has("dexes") || !pf.getAsJsonObject("dexes").has(t.dex)) return null;
		return pf.getAsJsonObject("dexes").getAsJsonObject(t.dex);
	}

	/** Spot USDC for a spot market; for a perp, the free margin of the balance it draws on. */
	private double available(@org.jetbrains.annotations.Nullable ClientState.Ticker t, boolean spot) {
		JsonObject pf = ClientState.portfolio;
		if (spot) return Math.max(0, num(pf, "spotUsdc", 0));
		JsonObject dex = dexBalance(t);
		if (dex != null) return Math.max(0, dex.get("available").getAsDouble());
		return Math.max(0, pf != null && pf.has("available") ? pf.get("available").getAsDouble() : num(pf, "withdrawable", 0));
	}

	/** The price sizes convert at: the limit price on a limit, else the mid. */
	private double refPrice(@org.jetbrains.annotations.Nullable ClientState.Ticker t) {
		boolean limitPriced = orderType == OrderRequest.Type.LIMIT || orderType == OrderRequest.Type.STOP || orderType == OrderRequest.Type.TAKE_PROFIT;
		double px = limitPriced ? parse(priceBox) : orderType.isTriggerLimit() ? parse(price2Box) : 0;
		if (orderType == OrderRequest.Type.SCALE && parse(priceBox) > 0 && parse(price2Box) > 0) px = (parse(priceBox) + parse(price2Box)) / 2;
		return px > 0 ? px : t == null ? 0 : t.mid;
	}

	/** The position an order on this side would shrink, as a positive size; 0 when it would open or grow. */
	private double opposingSize() {
		double pos = ClientState.positionSize(coin);
		return (pos > 0) == sellSide ? Math.abs(pos) : 0;
	}

	/**
	 * What 100% on the size buttons is, in dollars (§2.6): the side's Available to Trade times leverage, and an order
	 * against a position also gets back that position's size and margin. Reduce-only is the position; a spot sell, what's held.
	 */
	private double buyingPower(@org.jetbrains.annotations.Nullable ClientState.Ticker t, boolean spot) {
		double ref = refPrice(t);
		JsonObject fees = ClientState.portfolio != null && ClientState.portfolio.has("feeInfo") ? ClientState.portfolio.getAsJsonObject("feeInfo") : null;
		double fee = fees == null ? 0.0007 : fees.get(spot ? "spotTaker" : "perpTaker").getAsDouble();
		if (spot) return sellSide ? spotBalance() * ref : available(t, true) / (1 + fee);
		if (reduceOnly) return opposingSize() * ref;
		int lev = t == null ? 1 : currentLeverage(t);
		return available(t, false) * lev / (1 + fee * lev) + 2 * opposingSize() * ref;
	}

	/** The size box's value as a dollar amount, whichever unit it's in. */
	private double amountUsd(@org.jetbrains.annotations.Nullable ClientState.Ticker t) {
		return ClientConfig.get().sizeInCoin ? amountValue() * refPrice(t) : amountValue();
	}

	/** Sets the size from a share of what's available. Cents and lots are rounded down, so an order never asks for a hair too much. */
	private void setPct(@org.jetbrains.annotations.Nullable ClientState.Ticker t, boolean spot, int pct) {
		double power = buyingPower(t, spot);
		double ref = refPrice(t);
		boolean exact = pct == 100 && (spot && sellSide || reduceOnly);
		if (ClientConfig.get().sizeInCoin) {
			double coins = exact ? (spot ? spotBalance() : opposingSize()) : ref <= 0 ? 0 : power * pct / 100.0 / ref;
			int dp = t == null ? 5 : t.szDecimals;
			amount.setValue(new java.math.BigDecimal(coins).setScale(dp, exact ? java.math.RoundingMode.UP : java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString());
		} else {
			// a dollar over on a "sell it all" is clipped to what's there; a dollar under would leave dust
			amount.setValue(Fmt.plain(exact ? Math.ceil(power) + 1 : Math.floor(power * pct / 100.0)));
		}
	}

	private void toggleUnit(@org.jetbrains.annotations.Nullable ClientState.Ticker t) {
		ClientConfig cfg = ClientConfig.get();
		double ref = refPrice(t);
		double v = amountValue();
		cfg.sizeInCoin = !cfg.sizeInCoin;
		ClientConfig.save();
		if (v > 0 && ref > 0) amount.setValue(cfg.sizeInCoin
				? new java.math.BigDecimal(v / ref).setScale(t == null ? 5 : t.szDecimals, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()
				: Fmt.plain(Math.floor(v * ref)));
	}

	private int currentLeverage(ClientState.Ticker t) {
		JsonObject pf = ClientState.portfolio;
		if (pf != null && pf.has("leverage") && pf.getAsJsonObject("leverage").has(coin)) {
			return pf.getAsJsonObject("leverage").getAsJsonObject(coin).get("lev").getAsInt();
		}
		JsonObject pos = ClientState.position(coin);
		if (pos != null && pos.has("lev")) return pos.get("lev").getAsInt();
		int def = pf != null && pf.has("defaultLev") ? pf.get("defaultLev").getAsInt() : 2;
		return t == null ? def : Math.min(def, t.maxLeverage);
	}

	private boolean currentCross() {
		JsonObject pf = ClientState.portfolio;
		if (pf != null && pf.has("leverage") && pf.getAsJsonObject("leverage").has(coin)) {
			return pf.getAsJsonObject("leverage").getAsJsonObject(coin).get("cross").getAsBoolean();
		}
		JsonObject pos = ClientState.position(coin);
		return pos == null || !pos.has("cross") || pos.get("cross").getAsBoolean();
	}

	private void sendLeverage(int lev, boolean cross) {
		JsonObject o = new JsonObject();
		o.addProperty("coin", coin);
		o.addProperty("leverage", lev);
		o.addProperty("cross", cross);
		ClientNet.send("set_leverage", o);
	}

	/** Writes the order on the ticket panel onto an Order Ticket, for a Trade Executor to run. */
	private void saveTicket(String side) {
		ticketSentAt = System.currentTimeMillis();
		if (!orderType.onBlocks()) {
			ClientState.lastVaultOk = false;
			ClientState.lastVaultMsg = "Tickets carry Market, Limit, Stop, TP and TWAP";
			ClientState.lastVaultAt = ticketSentAt;
			return;
		}
		OrderRequest r = request(side);
		// a ticket's in dollars, whatever the terminal's unit: a block's pulse can't size in coin
		r.sizeCoin = 0;
		r.maxSlippage = 0;
		ClientNet.send("ticket_save", r.toJson());
	}

	/** When Save as ticket was last clicked: its answer shows in the ticket's header for a few seconds. */
	private long ticketSentAt;

	private void submit(String side) {
		ClientNet.order(request(side));
	}

	private OrderRequest request(String side) {
		OrderRequest r = new OrderRequest();
		ClientConfig cfg = ClientConfig.get();
		ClientState.Ticker t = ClientState.ticker(coin);
		r.coin = coin;
		r.side = Side.parse(side);
		r.usd = amountUsd(t);
		if (cfg.sizeInCoin) r.sizeCoin = amountValue();
		r.type = orderType;
		r.tif = tif;
		r.reduceOnly = reduceOnly;
		r.maxSlippage = cfg.maxSlippagePct / 100;
		switch (orderType) {
			case LIMIT, STOP, TAKE_PROFIT -> r.price = parse(priceBox);
			case STOP_LIMIT, TAKE_LIMIT -> {
				r.price = parse(priceBox);
				r.limitPrice = parse(price2Box);
			}
			case TWAP -> {
				r.minutes = (int) Math.max(5, Math.min(OrderRequest.MAX_TWAP_MINUTES, parse(priceBox)));
				r.twapLimit = parse(price2Box);
			}
			case SCALE -> {
				r.scaleStart = parse(priceBox);
				r.scaleEnd = parse(price2Box);
				r.scaleOrders = scaleCountBox.getValue().isEmpty() ? 5 : (int) parse(scaleCountBox);
				r.scaleSkew = skewBox.getValue().isEmpty() ? 1 : parse(skewBox);
			}
			case TRAILING -> {
				r.trailPct = parse(priceBox);
				r.activationPx = parse(price2Box);
			}
			default -> {}
		}
		if (cfg.tpslByPrice) {
			r.tpPx = parse(tpBox);
			r.slPx = parse(slBox);
		} else {
			r.tpPct = parse(tpBox);
			r.slPct = parse(slBox);
		}
		return r;
	}

	private static double parse(EditBox box) {
		try {
			return box.getValue().isEmpty() ? 0 : Double.parseDouble(box.getValue());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** The ticket's edit boxes move with its layout: {@link #drawTicket} places them each frame. */
	private void layoutTicket() {
		for (EditBox b : new EditBox[]{priceBox, price2Box, scaleCountBox, skewBox, amount, tpBox, slBox}) b.setPosition(-1000, -1000);
	}

	/** Second click within 4 s confirms an order that would tie up more than half the free margin. */
	private String confirmSide;
	private long confirmAt;

	private boolean needsConfirm(String side) {
		ClientState.Ticker t = ClientState.ticker(coin);
		if (!ClientConfig.get().confirmOrders || t == null || orderType == OrderRequest.Type.TWAP) return false;
		// selling what you hold ties nothing up
		if (t.spot && sellSide) return false;
		double usd = amountUsd(t);
		double margin = t.spot ? usd : usd / Math.max(1, currentLeverage(t));
		if (reduceOnly || margin <= available(t, t.spot) * 0.5) return false;
		if (side.equals(confirmSide) && System.currentTimeMillis() - confirmAt < 4000) return false;
		confirmSide = side;
		confirmAt = System.currentTimeMillis();
		return true;
	}

	/** Liquidation price if this order filled, or NaN (none, or nothing to estimate). */
	private double estimateLiq(ClientState.Ticker t, double usd, double ref) {
		if (t == null || t.spot || !(ref > 0) || !(usd > 0)) return Double.NaN;
		JsonObject pf = ClientState.portfolio;
		JsonObject dex = dexBalance(t);
		JsonObject pos = ClientState.position(coin);
		double mark = t.mark > 0 ? t.mark : t.mid;
		double cur = pos == null ? 0 : pos.get("size").getAsDouble();
		double delta = (sellSide ? -1 : 1) * usd / ref;
		if (reduceOnly) delta = Math.copySign(Math.min(Math.abs(delta), Math.abs(cur)), -cur);
		double after = cur + delta;
		if (Math.abs(after) < 1e-12) return Double.NaN;
		double notional = Math.abs(after) * mark;
		double mm = dev.stonkcraft.trading.MarginMath.maintenance(coin, t.maxLeverage, notional);
		double rate = dev.stonkcraft.trading.MarginMath.maintenanceRate(coin, t.maxLeverage, notional);
		double fillPnl = delta * (mark - ref);
		double avail;
		if (currentCross()) {
			double eq = dex != null ? dex.get("crossEquity").getAsDouble() : num(pf, "crossEquity", 0);
			double others = (dex != null ? dex.get("crossMaint").getAsDouble() : num(pf, "crossMaint", 0)) - num(pos, "maint", 0);
			avail = eq + fillPnl - others - mm;
		} else {
			double held = num(pos, "isoMargin", 0);
			double entryPnl = pos == null ? 0 : pos.get("upnl").getAsDouble();
			double initial = dev.stonkcraft.trading.MarginMath.initialMargin(coin, t.maxLeverage, currentLeverage(t), Math.abs(delta) * ref);
			avail = (Math.signum(after) == Math.signum(cur) || cur == 0 ? held + initial : initial) + entryPnl + fillPnl - mm;
		}
		return dev.stonkcraft.trading.MarginMath.liquidationPrice(mark, after, avail, rate);
	}

	private void drawTicket(GuiGraphics g, int mx, int my) {
		int x = rightX, y = bodyY, w = rightW, h = bodyH;
		Ui.panel(g, x, y, w, h);
		boolean tight = h < 240;
		ClientState.Ticker t = ClientState.ticker(coin);
		boolean spot = t != null && t.spot;
		boolean has = ClientState.hasAccount();
		ClientConfig cfg = ClientConfig.get();
		if (spot && !onSpot(orderType)) orderType = OrderRequest.Type.MARKET;
		boolean market = orderType == OrderRequest.Type.MARKET;
		boolean twap = orderType == OrderRequest.Type.TWAP;
		boolean scale = orderType == OrderRequest.Type.SCALE;
		boolean bracketable = !spot && (market || orderType == OrderRequest.Type.LIMIT);
		boolean twoBoxes = orderType.isTriggerLimit() || scale || twap || orderType == OrderRequest.Type.TRAILING;
		priceBox.visible = has && !market;
		price2Box.visible = has && twoBoxes;
		scaleCountBox.visible = skewBox.visible = has && scale;
		amount.visible = has;
		tpBox.visible = has && bracketable;
		slBox.visible = tpBox.visible;
		layoutTicket();

		String name = ClientState.display(coin);
		int cy;
		if (tight) {
			Ui.textScaled(g, font, Ui.ellipsize(font, name, (int) ((w - 60) / 1.25f)), x + 8, y + 6, 1.25f, Ui.ACCENT);
			if (t != null) Ui.textRight(g, font, Fmt.price(t.mid), x + w - 8, y + 7, Ui.TEXT);
			cy = y + 20;
		} else {
			boolean answered = ClientState.lastVaultAt >= ticketSentAt && ticketSentAt > 0 && System.currentTimeMillis() - ClientState.lastVaultAt < 5000;
			if (answered) Ui.text(g, font, Ui.ellipsize(font, ClientState.lastVaultMsg, w - 16), x + 8, y + 6, ClientState.lastVaultOk ? Ui.GREEN : Ui.RED);
			else Ui.text(g, font, spot ? "SPOT ORDER" : "PERP ORDER", x + 8, y + 6, Ui.DIM);
			if (has && !answered) {
				// this order, written on an Order Ticket for a Trade Executor
				String tk = "▤ ticket";
				int tkx = x + w - 8 - font.width(tk);
				boolean hov = Ui.inside(mx, my, tkx, y + 5, font.width(tk), 10);
				Ui.text(g, font, tk, tkx, y + 6, hov ? Ui.ACCENT : 0xFF4E6E6A);
				hit(tkx, y + 4, font.width(tk), 11, () -> saveTicket(sellSide ? "SELL" : "BUY"));
				if (hov) g.renderTooltip(font, Component.literal("Write this order on an Order Ticket for a Trade Executor"), mx, my);
			}
			Ui.textScaled(g, font, Ui.ellipsize(font, name, (int) ((w - 60) / 1.5f)), x + 8, y + 16, 1.5f, Ui.ACCENT);
			if (t != null) {
				long now = System.currentTimeMillis();
				int flash = now - t.lastMoveAt < 700 ? (t.lastMove > 0 ? Ui.GREEN : Ui.RED) : Ui.TEXT;
				Ui.textRight(g, font, Fmt.price(t.mid), x + w - 8, y + 19, flash);
			}
			cy = y + 32;
		}
		if (!has) {
			String msg = ClientState.accounts.isEmpty()
					? "No account yet. Press H to craft an Account Vault, place it and open one there."
					: "Pick an account from the ◈ menu in the header.";
			List<FormattedCharSequence> lines = font.split(Component.literal(msg), w - 16);
			for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 8, cy + 8 + i * 10, Ui.YELLOW, false);
			return;
		}

		// Market | Limit | Pro ▾
		int cw = (w - 12 - 4) / 3;
		Ui.chip(g, font, "Market", x + 6, cy, cw, 12, Ui.ACCENT, market, mx, my);
		hit(x + 6, cy, cw, 12, () -> selectType(OrderRequest.Type.MARKET, t));
		Ui.chip(g, font, "Limit", x + 6 + cw + 2, cy, cw, 12, Ui.ACCENT, orderType == OrderRequest.Type.LIMIT, mx, my);
		hit(x + 6 + cw + 2, cy, cw, 12, () -> selectType(OrderRequest.Type.LIMIT, t));
		boolean pro = !market && orderType != OrderRequest.Type.LIMIT;
		int px = x + 6 + 2 * (cw + 2);
		Ui.chip(g, font, Ui.ellipsize(font, (pro ? proLabel(orderType).replace(" Market", "").replace(" Stop", "") : "Pro") + " ▾", cw - 2), px, cy, cw, 12, Ui.ACCENT, pro, mx, my);
		proX = Math.min(px, x + w - 76);
		proY = cy + 13;
		proW = Math.max(cw, 70);
		hit(px, cy, cw, 12, () -> proMenu = !proMenu);
		cy += 15;

		// Buy / Long | Sell / Short
		int sw = (w - 12 - 2) / 2;
		String buyL = spot ? "Buy" : font.width("Buy / Long") + 4 <= sw ? "Buy / Long" : "Long";
		String sellL = spot ? "Sell" : font.width("Sell / Short") + 4 <= sw ? "Sell / Short" : "Short";
		Ui.chip(g, font, buyL, x + 6, cy, sw, 12, Ui.GREEN, !sellSide, mx, my);
		hit(x + 6, cy, sw, 12, () -> sellSide = false);
		Ui.chip(g, font, sellL, x + 6 + sw + 2, cy, sw, 12, Ui.RED, sellSide, mx, my);
		hit(x + 6 + sw + 2, cy, sw, 12, () -> sellSide = true);
		cy += 15;

		// price: nothing on a market order but a word on a roomy panel, one box or two otherwise
		if (market) {
			if (!tight) {
				Ui.text(g, font, Ui.ellipsize(font, "Fills now at market", w - 16), x + 8, cy, 0xFF4E6E6A);
				cy += 12;
			}
		} else if (!twoBoxes) {
			Ui.text(g, font, orderType == OrderRequest.Type.LIMIT ? "Limit price" : "Trigger price", x + 8, cy, Ui.DIM);
			if (t != null) {
				String mid = "mid";
				boolean hov = Ui.inside(mx, my, x + w - 8 - font.width(mid), cy, font.width(mid), 9);
				Ui.textRight(g, font, mid, x + w - 8, cy, hov ? Ui.ACCENT : 0xFF4E6E6A);
				hit(x + w - 8 - font.width(mid), cy, font.width(mid), 9, () -> priceBox.setValue(Fmt.plain(t.mid)));
			}
			box(g, x + 6, cy + 9, w - 12, priceBox.isFocused());
			priceBox.setPosition(x + 10, cy + 13);
			priceBox.setWidth(w - 20);
			cy += tight ? 26 : 28;
		} else {
			String[] labels = switch (orderType) {
				case STOP_LIMIT, TAKE_LIMIT -> new String[]{"Trigger", "Limit"};
				case SCALE -> new String[]{"Start", "End"};
				case TWAP -> new String[]{"Minutes", "Limit (opt)"};
				default -> new String[]{"Retrace %", "Start at (opt)"};
			};
			int half = (w - 12 - 2) / 2;
			Ui.text(g, font, Ui.ellipsize(font, labels[0], half - 2), x + 8, cy, Ui.DIM);
			Ui.text(g, font, Ui.ellipsize(font, labels[1], half - 2), x + 8 + half + 2, cy, Ui.DIM);
			box(g, x + 6, cy + 9, half, priceBox.isFocused());
			box(g, x + 6 + half + 2, cy + 9, half, price2Box.isFocused());
			priceBox.setPosition(x + 10, cy + 13);
			priceBox.setWidth(half - 8);
			price2Box.setPosition(x + 10 + half + 2, cy + 13);
			price2Box.setWidth(half - 8);
			cy += tight ? 26 : 28;
			if (scale) {
				String countL = half >= 80 ? "Orders" : "Num";
				Ui.text(g, font, countL, x + 8, cy + 3, Ui.DIM);
				Ui.text(g, font, "Skew", x + 8 + half + 2, cy + 3, Ui.DIM);
				int lw = font.width(countL) + 4, kw = font.width("Skew") + 4;
				box(g, x + 8 + lw, cy, half - lw - 2, scaleCountBox.isFocused());
				box(g, x + 8 + half + 2 + kw, cy, half - kw - 2, skewBox.isFocused());
				scaleCountBox.setPosition(x + 12 + lw, cy + 4);
				scaleCountBox.setWidth(half - lw - 10);
				skewBox.setPosition(x + 12 + half + 2 + kw, cy + 4);
				skewBox.setWidth(half - kw - 10);
				cy += 18;
			}
		}

		// size: what's available to trade on this side of the account, and the box in dollars or coin
		String av;
		if (spot) av = sellSide ? Fmt.size(spotBalance()) + " " + name : "spot " + Fmt.usdCompact(available(t, true)) + " ⇄";
		else if (dexBalance(t) != null) av = Fmt.pool(t.dex) + " " + Fmt.usdCompact(available(t, false)) + " ⇄";
		else av = "free " + Fmt.usdCompact(available(t, false)) + " ⇄";
		boolean both = font.width("Size") + font.width(av) + 24 <= w;
		if (both) Ui.text(g, font, "Size", x + 8, cy, Ui.DIM);
		int avw = Math.min(font.width(av), w - 16);
		int avx = both ? x + w - 8 - avw : x + 8;
		boolean avHov = Ui.inside(mx, my, avx, cy - 1, avw, 10);
		Ui.text(g, font, Ui.ellipsize(font, av, w - 16), avx, cy, avHov ? Ui.ACCENT : 0xFF4E6E6A);
		if (!(spot && sellSide)) {
			String dex = dexBalance(t) != null ? t.dex : "";
			hit(avx, cy - 2, avw, 11, () -> minecraft.setScreen(dex.isEmpty() ? new TransferScreen(this, !spot) : TransferScreen.toDex(this, dex)));
			if (avHov) g.renderTooltip(font, Component.literal(spot ? "Spot USD: what spot buys spend. Click to move USD from perps"
					: !dex.isEmpty() ? "Free " + dex + " USD: " + dex + " margins from its own balance. Click to move USD over"
					: "Free perps USD (margin not in use). Click to move USD between spot and perps"), mx, my);
		}
		box(g, x + 6, cy + 9, w - 12, amount.isFocused());
		String unit = cfg.sizeInCoin ? Ui.ellipsize(font, name, 30) : "USD";
		int uw = font.width(unit) + 6;
		boolean uHov = Ui.inside(mx, my, x + w - 7 - uw, cy + 10, uw, 13);
		Ui.text(g, font, unit + "", x + w - 7 - uw + 3, cy + 13, uHov ? Ui.ACCENT : Ui.DIM);
		hit(x + w - 7 - uw, cy + 10, uw, 13, () -> toggleUnit(t));
		amount.setPosition(x + 10, cy + 13);
		amount.setWidth(w - 22 - uw);
		cy += tight ? 26 : 28;
		int qw = (w - 12 - 2 * (QUICK_PCT.length - 1)) / QUICK_PCT.length;
		for (int i = 0; i < QUICK_PCT.length; i++) {
			int pct = QUICK_PCT[i];
			int qx = x + 6 + i * (qw + 2);
			Ui.chip(g, font, pct == 100 ? "Max" : pct + "%", qx, cy, qw, 11, Ui.ACCENT_DIM, false, mx, my);
			hit(qx, cy, qw, 11, () -> setPct(t, spot, pct));
		}
		cy += 15;

		// time in force (resting orders) / est size
		double usd = amountUsd(t);
		double ref = refPrice(t);
		if (orderType == OrderRequest.Type.LIMIT || scale) {
			int tw = (w - 12 - 4) / 3;
			for (int i = 0; i < 3; i++) {
				OrderRequest.Tif v = OrderRequest.Tif.values()[i];
				int tx = x + 6 + i * (tw + 2);
				Ui.chip(g, font, TIF_LABELS[i], tx, cy, tw, 11, Ui.ACCENT_DIM, tif == v, mx, my);
				hit(tx, cy, tw, 11, () -> tif = v);
			}
			cy += 15;
		} else if (t != null && t.mid > 0) {
			String est;
			if (twap) {
				int mins = (int) Math.max(5, Math.min(OrderRequest.MAX_TWAP_MINUTES, parse(priceBox)));
				est = "≈ " + Fmt.size(usd / t.mid) + " over " + mins + "m, every 30s";
			} else {
				double coins = usd / Math.max(1e-12, ref);
				// three significant figures keep the coin name on screen
				est = cfg.sizeInCoin ? "≈ " + Fmt.usdCompact(usd) : "≈ " + (coins < 1 ? String.format(Locale.ROOT, "%.3g", coins) : Fmt.size(coins)) + " " + name;
			}
			Ui.text(g, font, Ui.ellipsize(font, est, w - 16), x + 8, cy + 1, Ui.DIM);
			cy += 13;
		}

		// TP / SL bracket, by distance or by price
		if (tpBox.visible) {
			int half = (w - 12) / 2;
			String unitS = cfg.tpslByPrice ? "$" : "%";
			boolean tpHov = Ui.inside(mx, my, x + 6, cy, 20, 15), slHov = Ui.inside(mx, my, x + 6 + half + 2, cy, 20, 15);
			Ui.text(g, font, "TP" + unitS, x + 8, cy + 4, tpHov ? Ui.ACCENT : Ui.GREEN);
			Ui.text(g, font, "SL" + unitS, x + 6 + half + 4, cy + 4, slHov ? Ui.ACCENT : Ui.RED);
			Runnable flip = () -> {
				cfg.tpslByPrice = !cfg.tpslByPrice;
				tpBox.setValue("");
				slBox.setValue("");
				ClientConfig.save();
			};
			hit(x + 6, cy, 20, 15, flip);
			hit(x + 6 + half + 2, cy, 20, 15, flip);
			if (tpHov || slHov) g.renderTooltip(font, Component.literal(cfg.tpslByPrice ? "By price. Click for a distance in %" : "By distance from the entry. Click for a price"), mx, my);
			box(g, x + 26, cy, half - 22, tpBox.isFocused());
			box(g, x + 6 + half + 22, cy, half - 22, slBox.isFocused());
			tpBox.setPosition(x + 30, cy + 4);
			tpBox.setWidth(half - 30);
			slBox.setPosition(x + 6 + half + 26, cy + 4);
			slBox.setWidth(half - 30);
			cy += 19;
		}

		// leverage + reduce-only
		if (!spot && t != null) {
			int lev = currentLeverage(t);
			boolean cross = currentCross();
			Ui.text(g, font, lev + "x", x + 8, cy + 2, lev > 5 ? Ui.YELLOW : Ui.TEXT);
			int lx = x + 8 + font.width("20x") + 4;
			Ui.chip(g, font, "-", lx, cy, 12, 12, Ui.ACCENT_DIM, false, mx, my);
			Ui.chip(g, font, "+", lx + 14, cy, 12, 12, Ui.ACCENT_DIM, false, mx, my);
			hit(lx, cy, 12, 12, () -> sendLeverage(Math.max(1, lev - (hasShiftDown() ? 5 : 1)), cross));
			hit(lx + 14, cy, 12, 12, () -> sendLeverage(Math.min(t.maxLeverage, lev + (hasShiftDown() ? 5 : 1)), cross));
			String mode = cross ? "Cross" : "Iso";
			Ui.chip(g, font, mode, lx + 30, cy, 32, 12, Ui.YELLOW, false, mx, my);
			hit(lx + 30, cy, 32, 12, () -> sendLeverage(lev, !cross));
			String ro = (reduceOnly ? "■" : "□") + " RO";
			int roW = font.width(ro);
			int roX = x + w - 8 - roW, roY = cy + 2;
			if (roX < lx + 30 + 32 + 4) {
				// no room beside the leverage chips: reduce-only gets its own line
				cy += 14;
				roX = x + 8;
				roY = cy + 1;
				ro = (reduceOnly ? "■" : "□") + " Reduce-only";
				roW = font.width(ro);
			}
			boolean hov = Ui.inside(mx, my, roX, roY, roW, 9);
			Ui.text(g, font, ro, roX, roY, reduceOnly ? Ui.YELLOW : hov ? Ui.TEXT : Ui.DIM);
			hit(roX, roY - 2, roW, 12, () -> reduceOnly = !reduceOnly);
			cy += 16;
		}

		// one button, for the side that's chosen
		int bh = tight ? 14 : 17;
		String side = sellSide ? "SELL" : "BUY";
		boolean confirming = side.equals(confirmSide) && System.currentTimeMillis() - confirmAt < 4000;
		String action = confirming ? "CONFIRM: over half your margin"
				: market ? (sellSide ? (spot ? "SELL" : "SELL / SHORT") : spot ? "BUY" : "BUY / LONG")
				: "PLACE " + (sellSide ? "SELL" : "BUY");
		if (!confirming && font.width(action + "  [" + side.charAt(0) + "]") <= w - 20) action += "  [" + side.charAt(0) + "]";
		int sideColor = sellSide ? Ui.RED : Ui.GREEN;
		boolean hb = Ui.chip(g, font, Ui.ellipsize(font, action, w - 16), x + 6, cy, w - 12, bh, sideColor, confirming, mx, my);
		hit(x + 6, cy, w - 12, bh, () -> {
			if (!needsConfirm(side)) {
				confirmSide = null;
				submit(side);
			}
		});
		if (hb) Ui.outline(g, x + 5, cy - 1, w - 10, bh + 2, sideColor & 0x60FFFFFF);
		cy += bh + 4;

		// the info rows, as far as they fit, and always leaving a line for the position
		int bottom = y + h - 4;
		JsonObject feeInfo = ClientState.portfolio.has("feeInfo") ? ClientState.portfolio.getAsJsonObject("feeInfo") : null;
		List<String[]> info = new ArrayList<>();
		if (!spot) {
			double liq = estimateLiq(t, usd, ref);
			info.add(new String[]{"Liq. price", usd <= 0 ? "--" : Double.isNaN(liq) ? "None" : Fmt.price(liq)});
		}
		info.add(new String[]{w >= 140 ? "Order value" : "Value", usd <= 0 ? "--" : Fmt.usd(usd)});
		if (!spot && t != null) info.add(new String[]{"Margin", usd <= 0 ? "--" : Fmt.usd(dev.stonkcraft.trading.MarginMath.initialMargin(coin, t.maxLeverage, currentLeverage(t), usd))});
		if (market) info.add(new String[]{"Slippage", String.format(Locale.ROOT, "%.2f%% / %.0f%%", 0.02, cfg.maxSlippagePct)});
		if (feeInfo != null) info.add(new String[]{"Fees", String.format(Locale.ROOT, "%.3f / %.3f%%", feeInfo.get(spot ? "spotTaker" : "perpTaker").getAsDouble() * 100, feeInfo.get(spot ? "spotMaker" : "perpMaker").getAsDouble() * 100)});
		for (String[] row : info) {
			if (cy + 9 + 12 > bottom) break;
			Ui.text(g, font, row[0], x + 8, cy, 0xFF4E6E6A);
			Ui.textRight(g, font, Ui.ellipsize(font, row[1], w - 20 - font.width(row[0])), x + w - 8, cy, row[0].equals("Liq. price") ? Ui.YELLOW : Ui.DIM);
			if (row[0].equals("Slippage")) {
				boolean sh = Ui.inside(mx, my, x + 8, cy, w - 16, 9);
				if (sh) g.renderTooltip(font, Component.literal("Estimated / the most it may slip. Click to set it. Closes use 8%, market TP/SL 10%"), mx, my);
				hit(x + 8, cy, w - 16, 9, () -> minecraft.setScreen(new SettingsScreen(this)));
			}
			cy += 10;
		}
		cy += 4;

		// position, if there's room left
		int py = cy;
		if (py + 9 > bottom) return;
		g.fill(x + 6, py - 3, x + w - 6, py - 2, Ui.BORDER);
		JsonObject pos = ClientState.position(coin);
		double spotQty = spotBalance();
		if (spot) {
			Ui.text(g, font, "Holding", x + 8, py, Ui.DIM);
			Ui.textRight(g, font, Ui.ellipsize(font, Fmt.size(spotQty) + " " + name, w - 60), x + w - 8, py, spotQty > 0 ? Ui.TEXT : Ui.DIM);
			if (spotQty > 0 && py + 25 <= bottom) {
				Ui.chip(g, font, armed("sell " + coin) ? "CLICK AGAIN TO SELL ALL" : "SELL ALL", x + 6, py + 12, w - 12, 13, Ui.YELLOW, armed("sell " + coin), mx, my);
				hit(x + 6, py + 12, w - 12, 13, () -> { if (!cfg.confirmCloses || confirmClick("sell " + coin)) ClientNet.order(coin, "CLOSE", 0); });
			}
			py += 26;
		} else if (pos == null) {
			Ui.text(g, font, "Position", x + 8, py, Ui.DIM);
			Ui.textRight(g, font, "Flat", x + w - 8, py, Ui.DIM);
			py += 12;
		} else {
			double size = pos.get("size").getAsDouble();
			double upnl = pos.get("upnl").getAsDouble();
			String upnlS = Fmt.usdSigned(upnl);
			Ui.text(g, font, Ui.ellipsize(font, (size > 0 ? "LONG " : "SHORT ") + Fmt.size(Math.abs(size)), w - 22 - font.width(upnlS)), x + 8, py, size > 0 ? Ui.GREEN : Ui.RED);
			Ui.textRight(g, font, upnlS, x + w - 8, py, Ui.signColor(upnl));
			if (py + 19 <= bottom) {
				String liq = pos.has("liq") ? "liq " + Fmt.price(pos.get("liq").getAsDouble()) : "";
				Ui.text(g, font, Ui.ellipsize(font, "@ " + Fmt.price(pos.get("entry").getAsDouble()), w - 22 - font.width(liq)), x + 8, py + 10, Ui.DIM);
				if (!liq.isEmpty()) Ui.textRight(g, font, liq, x + w - 8, py + 10, Ui.DIM);
			}
			if (py + 35 <= bottom) {
				Ui.chip(g, font, armed("close " + coin) ? "CLICK AGAIN TO CLOSE" : "CLOSE POSITION", x + 6, py + 22, w - 12, 13, Ui.YELLOW, armed("close " + coin), mx, my);
				hit(x + 6, py + 22, w - 12, 13, () -> { if (!cfg.confirmCloses || confirmClick("close " + coin)) ClientNet.order(coin, "CLOSE", 0); });
			}
			py += 40;
		}

		// last result
		if (!ClientState.lastOrderMsg.isEmpty() && System.currentTimeMillis() - ClientState.lastOrderAt < 15_000) {
			List<FormattedCharSequence> lines = font.split(Component.literal(ClientState.lastOrderMsg), w - 16);
			for (int i = 0; i < Math.min(3, lines.size()) && py + i * 10 + 9 <= bottom; i++) {
				g.drawString(font, lines.get(i), x + 8, py + i * 10, ClientState.lastOrderOk ? Ui.GREEN : Ui.RED, false);
			}
		}
		// the account at the foot of the ticket, where a tall window would leave a blank band: what's free, what's in use
		JsonObject pf = ClientState.hasAccount() ? ClientState.portfolio : null;
		int fy = bottom - 21;
		if (pf != null && pf.has("margin") && pf.has("equity") && fy > py + 34) {
			double eq = pf.get("equity").getAsDouble(), used = pf.get("margin").getAsDouble();
			g.fill(x + 6, fy - 4, x + w - 6, fy - 3, Ui.BORDER);
			Ui.text(g, font, "Free", x + 8, fy, Ui.DIM);
			Ui.textRight(g, font, Fmt.usd(Math.max(0, num(pf, "available", eq - used))), x + w - 8, fy, Ui.TEXT);
			Ui.text(g, font, "In use", x + 8, fy + 11, Ui.DIM);
			// the share of equity only when the narrow ticket has room for it
			String inUse = Fmt.usd(used) + (eq > 0 ? String.format(java.util.Locale.ROOT, " (%.0f%%)", used / eq * 100) : "");
			if (font.width("In use") + 6 + font.width(inUse) > w - 16) inUse = Fmt.usd(used);
			Ui.textRight(g, font, inUse, x + w - 8, fy + 11, Ui.DIM);
		}
	}

	private void selectType(OrderRequest.Type type, @org.jetbrains.annotations.Nullable ClientState.Ticker t) {
		boolean wasPriced = orderType == OrderRequest.Type.LIMIT || orderType == OrderRequest.Type.STOP || orderType == OrderRequest.Type.TAKE_PROFIT || orderType.isTriggerLimit();
		orderType = type;
		proMenu = false;
		boolean priced = type == OrderRequest.Type.LIMIT || type == OrderRequest.Type.STOP || type == OrderRequest.Type.TAKE_PROFIT || type.isTriggerLimit();
		String mid = t == null ? "" : Fmt.plain(t.mid);
		switch (type) {
			case MARKET -> {}
			case TWAP -> {
				priceBox.setValue("30");
				price2Box.setValue("");
			}
			case TRAILING -> {
				priceBox.setValue("5");
				price2Box.setValue("");
			}
			case SCALE -> {
				priceBox.setValue("");
				price2Box.setValue("");
				scaleCountBox.setValue("5");
				skewBox.setValue("1");
			}
			default -> {
				if (priced && (!wasPriced || priceBox.getValue().isEmpty())) priceBox.setValue(mid);
				if (type.isTriggerLimit() && price2Box.getValue().isEmpty()) price2Box.setValue(mid);
			}
		}
	}

	/** The Pro menu, over everything else on the form. */
	private void renderProMenu(GuiGraphics g, int mx, int my) {
		proHits.clear();
		ClientState.Ticker t = ClientState.ticker(coin);
		boolean spot = t != null && t.spot;
		List<OrderRequest.Type> types = new ArrayList<>();
		for (OrderRequest.Type type : PRO_TYPES) if (!spot || onSpot(type)) types.add(type);
		int rowH = 12;
		g.fill(proX, proY, proX + proW, proY + types.size() * rowH + 2, 0xF5081214);
		Ui.outline(g, proX, proY, proW, types.size() * rowH + 2, Ui.ACCENT_DIM);
		int ry = proY + 1;
		for (OrderRequest.Type type : types) {
			boolean hov = Ui.inside(mx, my, proX + 1, ry, proW - 2, rowH);
			if (hov || orderType == type) g.fill(proX + 1, ry, proX + proW - 1, ry + rowH, orderType == type ? Ui.PANEL_HI : 0x2012FFE0);
			Ui.text(g, font, Ui.ellipsize(font, proLabel(type), proW - 8), proX + 4, ry + 2, orderType == type ? Ui.ACCENT : Ui.TEXT);
			proHits.add(new Hit(proX, ry, proW, rowH, () -> selectType(type, t)));
			ry += rowH;
		}
	}
	private double spotBalance() {
		JsonObject pf = ClientState.portfolio;
		for (JsonElement el : arr(pf, "balances")) {
			JsonObject b = el.getAsJsonObject();
			if (b.get("market").getAsString().equals(coin)) return b.get("qty").getAsDouble();
		}
		return 0;
	}

	private void box(GuiGraphics g, int x, int y, int w, boolean focused) {
		g.fill(x, y, x + w, y + 15, 0xFF081214);
		Ui.outline(g, x, y, w, 15, focused ? Ui.ACCENT : Ui.BORDER);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		super.render(g, mx, my, pt);
		menuHits.clear();
		if (proMenu) {
			g.pose().pushPose();
			g.pose().translate(0, 0, 300);
			renderProMenu(g, mx, my);
			g.pose().popPose();
		}
		if (!accountMenu) return;
		int w = Math.min(240, width - menuX - 4), rowH = 14;
		int n = Math.max(1, ClientState.accounts.size());
		// above the terminal, like any dropdown
		g.pose().pushPose();
		g.pose().translate(0, 0, 300);
		renderAccountMenu(g, mx, my, w, rowH, n);
		g.pose().popPose();
	}

	private void renderAccountMenu(GuiGraphics g, int mx, int my, int w, int rowH, int n) {
		g.fill(menuX, menuY, menuX + w, menuY + n * rowH + 4, 0xF5081214);
		Ui.outline(g, menuX, menuY, w, n * rowH + 4, Ui.ACCENT_DIM);
		if (ClientState.accounts.isEmpty()) {
			Ui.text(g, font, "No accounts. Place an Account Vault.", menuX + 6, menuY + 5, Ui.DIM);
			return;
		}
		int ry = menuY + 2;
		for (JsonElement el : ClientState.accounts) {
			JsonObject a = el.getAsJsonObject();
			boolean sel = a.get("selected").getAsBoolean();
			boolean hov = Ui.inside(mx, my, menuX + 1, ry, w - 2, rowH);
			if (sel || hov) g.fill(menuX + 1, ry, menuX + w - 1, ry + rowH, sel ? Ui.PANEL_HI : 0x2012FFE0);
			int lc = Ui.tagColor(Ui.tag(a));
			g.fill(menuX + 4, ry + 4, menuX + 7, ry + 10, lc);
			String access = a.has("access") ? a.get("access").getAsString() : (a.get("owned").getAsBoolean() ? "OWNER" : "CARD");
			String tag = switch (access) {
				case "TRADE" -> " (trade)";
				case "VIEW" -> " (view)";
				case "CARD" -> " (card, view)";
				default -> "";
			};
			if (a.has("frozen") && a.get("frozen").getAsBoolean()) tag += " ❄";
			String name = a.get("name").getAsString() + tag;
			String net = Ui.netMark(a);
			String eq = a.has("paused") ? "" : a.get("available").getAsBoolean() ? Fmt.usdCompact(a.get("equity").getAsDouble()) : "n/a";
			int netW = net.isEmpty() ? 0 : font.width(net) + 6;
			Ui.text(g, font, Ui.ellipsize(font, name, w - 60 - netW), menuX + 11, ry + 3, sel ? Ui.ACCENT : Ui.TEXT);
			Ui.textRight(g, font, eq, menuX + w - 6, ry + 3, Ui.DIM);
			if (!net.isEmpty()) Ui.textRight(g, font, net, menuX + w - 6 - (eq.isEmpty() ? 0 : font.width(eq) + 6), ry + 3, net.equals("paused") ? Ui.DIM : Ui.YELLOW);
			String id = a.get("id").getAsString();
			menuHits.add(new Hit(menuX, ry, w, rowH, () -> {
				JsonObject o = new JsonObject();
				o.addProperty("id", id);
				ClientNet.send("select_account", o);
				accountMenu = false;
			}));
			ry += rowH;
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		holdCloseSince = 0;
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (proMenu) {
			for (Hit h : proHits) {
				if (Ui.inside(mx, my, h.x, h.y, h.w, h.h)) {
					h.action.run();
					return true;
				}
			}
			proMenu = false;
			// a click on the chip that opened it only closes it
			return true;
		}
		if (accountMenu) {
			for (Hit h : menuHits) {
				if (Ui.inside(mx, my, h.x, h.y, h.w, h.h)) {
					h.action.run();
					return true;
				}
			}
			accountMenu = false;
			return true;
		}
		if (super.mouseClicked(mx, my, button)) return true;
		if (button == 0) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit h = hits.get(i);
				if (Ui.inside(mx, my, h.x, h.y, h.w, h.h)) {
					setFocused(null);
					h.action.run();
					return true;
				}
			}
			setFocused(null);
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (Ui.inside(mx, my, leftX, bodyY, leftW, bodyH)) {
			listScroll -= (int) Math.signum(sy) * 3;
			return true;
		}
		if (Ui.inside(mx, my, centerX, bodyY + chartH, centerW, bodyH - chartH)) {
			tableScroll -= (int) Math.signum(sy);
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean keyPressed(int key, int scan, int mods) {
		if (search.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
			List<ClientState.Ticker> rows = marketRows();
			if (!rows.isEmpty()) select(rows.get(0).name);
			search.setValue("");
			setFocused(null);
			return true;
		}

		if (amount.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
			setFocused(null);
			return true;
		}
		// anywhere: Ctrl+K searches markets, Ctrl+S stars the market on screen
		if (hasControlDown() && key == GLFW.GLFW_KEY_K) {
			setFocused(search);
			return true;
		}
		if (hasControlDown() && key == GLFW.GLFW_KEY_S) {
			ClientConfig.get().toggleWatch(coin);
			return true;
		}
		// shortcuts while no box is being typed in: / searches, B and S place the ticket's order (same confirm as clicking)
		if (!(getFocused() instanceof EditBox e && e.isFocused())) {
			if (key == GLFW.GLFW_KEY_SLASH) {
				setFocused(search);
				return true;
			}
			if ((key == GLFW.GLFW_KEY_B || key == GLFW.GLFW_KEY_S) && ClientState.hasAccount()) {
				String side = key == GLFW.GLFW_KEY_B ? "BUY" : "SELL";
				sellSide = key == GLFW.GLFW_KEY_S;
				if (!needsConfirm(side)) {
					confirmSide = null;
					submit(side);
				}
				return true;
			}
		}
		return super.keyPressed(key, scan, mods);
	}
}
