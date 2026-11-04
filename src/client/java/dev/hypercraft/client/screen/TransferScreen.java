package dev.hypercraft.client.screen;

import com.google.gson.JsonObject;
import dev.hypercraft.client.ClientNet;
import dev.hypercraft.client.ClientState;
import dev.hypercraft.client.ui.HcButton;
import dev.hypercraft.client.ui.HcField;
import dev.hypercraft.client.ui.HcScreen;
import dev.hypercraft.client.ui.Ui;
import dev.hypercraft.trading.Fmt;
import net.minecraft.client.gui.screens.Screen;

/**
 * The account type, and moving USDC around it. A Manual account keeps spot, perps and each HIP-3 dex apart, as
 * Hyperliquid's Manual type does: spot buys spend spot USDC, sales pay into it, perps margin comes only from perps USDC,
 * a dex's only from its own, and only free USDC (not margin in use) can move. A Unified account has one USDC balance for
 * them all, as Hyperliquid's default does. Portfolio Margin adds spot HYPE and BTC as collateral.
 */
public class TransferScreen extends HcScreen {
	private enum Way {
		TO_SPOT("Perps → Spot", "Free perps USDC, to buy spot tokens with"),
		TO_PERPS("Spot → Perps", "Spot USDC, e.g. from selling bridged goods, to margin perps with"),
		TO_DEX("Perps → xyz", "Free perps USDC, to margin stocks and commodities on trade.xyz with"),
		FROM_DEX("xyz → Perps", "Free trade.xyz USDC, back to the perps account");

		final String label, tip;

		Way(String label, String tip) {
			this.label = label;
			this.tip = tip;
		}
	}

	private static final String DEX = "xyz";

	private final Screen parent;
	private Way way;
	private HcField amount;

	public TransferScreen(Screen parent, boolean toPerps) {
		this(parent, toPerps ? Way.TO_PERPS : Way.TO_SPOT);
	}

	private TransferScreen(Screen parent, Way way) {
		super("Transfer USDC");
		this.parent = parent;
		this.way = way;
	}

	/** Opens on moving USDC over to a dex. */
	public static TransferScreen toDex(Screen parent, String dex) {
		return new TransferScreen(parent, Way.TO_DEX);
	}

	@Override
	protected int panelWidth() {
		return 300;
	}

	private static double pf(String key) {
		JsonObject pf = ClientState.portfolio;
		return pf != null && pf.has(key) ? pf.get(key).getAsDouble() : 0;
	}

	private static boolean flag(String key) {
		JsonObject pf = ClientState.portfolio;
		return pf != null && pf.has(key) && pf.get(key).getAsBoolean();
	}

	private static double dexFree() {
		JsonObject pf = ClientState.portfolio;
		if (pf == null || !pf.has("dexes") || !pf.getAsJsonObject("dexes").has(DEX)) return 0;
		return pf.getAsJsonObject("dexes").getAsJsonObject(DEX).get("withdrawable").getAsDouble();
	}

	/** What can move in the chosen direction. */
	private double max() {
		return switch (way) {
			case TO_PERPS -> pf("spotUsdc");
			case FROM_DEX -> dexFree();
			default -> pf("withdrawable");
		};
	}

	@Override
	protected String status() {
		return flag("unified") ? "One USDC balance: " + Fmt.usd(pf("withdrawable")) + " free"
				: "Spot " + Fmt.usd(pf("spotUsdc")) + " · Perps free " + Fmt.usd(pf("withdrawable")) + " · xyz free " + Fmt.usd(dexFree());
	}

	private void setType(boolean unified) {
		JsonObject o = new JsonObject();
		o.addProperty("unified", unified);
		ClientNet.send("account_type", o);
		// the portfolio comes back with the new type; show it straight away
		if (ClientState.portfolio != null) {
			ClientState.portfolio.addProperty("unified", unified);
			ClientState.portfolio.addProperty("portfolioMargin", false);
		}
		relayout();
	}

	private void setPortfolioMargin() {
		JsonObject o = new JsonObject();
		o.addProperty("portfolioMargin", true);
		ClientNet.send("account_type", o);
		// not shown until the server agrees: it takes $10,000 of account value
		if (ClientState.portfolio != null && pf("equity") >= 10_000) {
			ClientState.portfolio.addProperty("unified", true);
			ClientState.portfolio.addProperty("portfolioMargin", true);
		} else {
			toast("Portfolio Margin takes $10,000 of account value", Ui.YELLOW);
		}
		relayout();
	}

	@Override
	protected void layout(Flow f) {
		int y = f.row("Account", ROW);
		int tw = (f.width - 70 - 8) / 3;
		boolean uni = flag("unified"), pm = flag("portfolioMargin");
		f.add(new HcButton(f.left + 70, y, tw, ROW, "Manual", HcButton.Style.TAB, b -> setType(false)).selected(!uni)
				.tip("Spot, perps and each dex's USDC kept apart: move it with a transfer. Selling spot pays spot, not your perps margin"));
		f.add(new HcButton(f.left + 74 + tw, y, tw, ROW, "Unified", HcButton.Style.TAB, b -> setType(true)).selected(uni && !pm)
				.tip("Hyperliquid's default: one USDC balance for spot buys and all perps margin, no transfers"));
		f.add(new HcButton(f.left + 78 + 2 * tw, y, tw, ROW, "Portfolio", HcButton.Style.TAB, b -> setPortfolioMargin()).selected(pm)
				.tip("Unified, plus spot HYPE (65%) and BTC (50%) count as collateral. Takes $10,000 of account value"));
		if (uni) {
			amount = null;
			f.text(pm ? "Spot HYPE counts at 65% of its value and BTC at 50% as perps collateral, and USDC can go negative against them. "
					+ "Past a 95% ratio the backstop takes positions a fifth at a time, then sells the collateral."
					: "One USDC balance pays for spot buys and margins perps on every dex, and spot sales pay straight into it. Spot tokens themselves "
					+ "are never perps collateral. Switch to Manual to keep them apart.", Ui.DIM);
			return;
		}
		y = f.row("Direction", ROW);
		int w = (f.width - 70 - 4) / 2;
		Way[] ways = Way.values();
		for (int i = 0; i < ways.length; i++) {
			Way option = ways[i];
			if (i == 2) y = f.row("", ROW);
			f.add(new HcButton(f.left + 70 + (i % 2) * (w + 4), y, w, ROW, option.label, HcButton.Style.TAB, b -> {
				way = option;
				relayout();
			}).selected(way == option).tip(option.tip));
		}
		y = f.row("Amount", 14);
		String kept = amount == null ? "" : amount.getValue();
		amount = f.add(field(f.left + 70, y, f.width - 70 - 50, "transfer amount", kept));
		amount.numeric(100).suffix("USDC").hint("0");
		amount.validate(s -> amount.num(0) > max() + 0.005 ? "More than the " + Fmt.usd(max()) + " available" : null);
		f.add(new HcButton(f.left + f.width - 44, y, 44, 14, "Max", b -> amount.setValue(Fmt.plain(Math.floor(max() * 100) / 100))))
				.tip("Everything that can move: " + Fmt.usd(max()));
		f.text(switch (way) {
			case TO_PERPS -> "Spot USDC margins perps once it's here. Spot holdings themselves never count as collateral: sell them first.";
			case TO_SPOT -> "Only free perps USDC can move: margin held by open positions stays, and profit only above 10% of position value.";
			case TO_DEX -> "trade.xyz margins from its own USDC, so stocks and commodities need some moved here. A liquidation there can't touch your perps.";
			case FROM_DEX -> "Only free xyz USDC can move: margin held by its open positions stays.";
		}, Ui.DIM);
	}

	@Override
	protected void footer(Footer f) {
		if (flag("unified")) {
			f.primary("Done", this::onClose);
			return;
		}
		f.right("Cancel", HcButton.Style.NORMAL, this::onClose);
		f.primary("Transfer", () -> {
			double usd = amount.num(0);
			if (usd <= 0) {
				toast("Enter an amount", Ui.YELLOW);
				return;
			}
			JsonObject o = new JsonObject();
			o.addProperty("usd", usd);
			if (way == Way.TO_DEX || way == Way.FROM_DEX) {
				o.addProperty("dex", DEX);
				o.addProperty("toDex", way == Way.TO_DEX);
				ClientNet.send("dex_transfer", o);
			} else {
				o.addProperty("toPerps", way == Way.TO_PERPS);
				ClientNet.send("spot_transfer", o);
			}
			chime();
			onClose();
		});
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
