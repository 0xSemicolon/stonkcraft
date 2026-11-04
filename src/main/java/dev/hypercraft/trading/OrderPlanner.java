package dev.hypercraft.trading;

import dev.hypercraft.hl.HlFormat;
import dev.hypercraft.market.MarketData;
import org.jetbrains.annotations.Nullable;

/**
 * Turns an {@link OrderRequest} into concrete exchange legs, shared by the paper engine and the live account
 * so both interpret orders identically.
 */
public final class OrderPlanner {
	private OrderPlanner() {}

	/** One exchange order. Trigger legs execute as market orders when their trigger price is hit. */
	public record Leg(boolean isBuy, double size, double limitPx, boolean trigger, double triggerPx, String tpsl,
			OrderRequest.Tif tif, boolean reduceOnly, boolean immediate, boolean triggerLimit) {
		Leg(boolean isBuy, double size, double limitPx, boolean trigger, double triggerPx, String tpsl, OrderRequest.Tif tif, boolean reduceOnly, boolean immediate) {
			this(isBuy, size, limitPx, trigger, triggerPx, tpsl, tif, reduceOnly, immediate, false);
		}
	}

	/** Market TP/SL orders may slip this far, and closes this far, whatever the order form's setting (§2.8). */
	public static final double TPSL_SLIPPAGE = 0.10;
	public static final double CLOSE_SLIPPAGE = 0.08;

	/** entry plus optional bracket children (reduce-only triggers armed once the entry fills). */
	public record Plan(MarketData.Asset asset, Leg entry, @Nullable Leg tp, @Nullable Leg sl) {}

	public static final class PlanException extends Exception {
		public PlanException(String msg) {
			super(msg);
		}
	}

	/**
	 * @param position signed perp size, or spot base balance for spot markets
	 * @param slippage fraction used to cap market orders (e.g. 0.01)
	 */
	public static Plan plan(OrderRequest r, MarketData.Asset asset, double mid, double position, double slippage) throws PlanException {
		if (Double.isNaN(mid) || mid <= 0) throw new PlanException("No price for " + asset.name() + " yet");
		if (r.side.usesAmount() && r.usd <= 0 && r.sizeCoin <= 0) throw new PlanException("Amount must be positive");
		boolean spot = asset.spot();
		if (spot && r.type.isTrigger()) throw new PlanException("Stops and take-profits are perps-only");
		if (spot && (r.side == Side.SHORT || r.side == Side.REVERSE)) throw new PlanException("Can't short spot. Use the perp market");
		if ((r.side == Side.CLOSE || r.side == Side.REVERSE) && position == 0) throw new PlanException("No " + asset.name() + " position to " + (r.side == Side.CLOSE ? "close" : "reverse"));

		double px = r.type == OrderRequest.Type.MARKET ? mid : r.resolvePrice(mid);
		if (px <= 0) throw new PlanException("Price must be positive");
		px = asset.roundPrice(px);
		if (r.type.isTriggerLimit() && r.limitPrice <= 0) throw new PlanException("Enter the limit price it rests at once triggered");

		// sized in coin, the dollars are only a way to reach the same sums
		double usd = r.sizeCoin > 0 ? r.sizeCoin * px : r.usd;
		double delta;
		if (spot && r.side == Side.SELL) {
			delta = -Math.min(usd / px, Math.max(0, position));
			if (position <= 0) throw new PlanException("No " + asset.base() + " to sell");
		} else if (spot && r.side == Side.LONG) {
			delta = position > 0 ? 0 : usd / px;
		} else {
			delta = r.side.delta(position, px, usd);
		}
		if (r.sizeCoin > 0 && (r.side == Side.BUY || r.side == Side.SELL && !spot)) delta = Math.copySign(r.sizeCoin, delta);
		double size = HlFormat.roundSize(Math.abs(delta), asset.szDecimals());
		if (size == 0) {
			if (r.side == Side.LONG || r.side == Side.SHORT) throw new PlanException("Already " + (position > 0 ? "long " : "short ") + asset.name());
			throw new PlanException("Order too small for " + asset.name() + " lot size");
		}
		boolean isBuy = delta > 0;
		boolean reduceOnly = !spot && (r.reduceOnly || r.side == Side.CLOSE);

		Leg entry = switch (r.type) {
			case MARKET, TWAP, SCALE, TRAILING -> new Leg(isBuy, size, asset.roundPrice(mid * (isBuy ? 1 + slippage : 1 - slippage)), false, 0, "",
					OrderRequest.Tif.IOC, reduceOnly, true);
			case LIMIT -> new Leg(isBuy, size, px, false, 0, "", r.tif, reduceOnly, r.tif == OrderRequest.Tif.IOC);
			case STOP, TAKE_PROFIT -> new Leg(isBuy, size, asset.roundPrice(px * (isBuy ? 1 + TPSL_SLIPPAGE : 1 - TPSL_SLIPPAGE)), true, px,
					r.type == OrderRequest.Type.STOP ? "sl" : "tp", OrderRequest.Tif.GTC, reduceOnly, false);
			case STOP_LIMIT, TAKE_LIMIT -> new Leg(isBuy, size, asset.roundPrice(r.limitPrice), true, px,
					r.type == OrderRequest.Type.STOP_LIMIT ? "sl" : "tp", OrderRequest.Tif.GTC, reduceOnly, false, true);
		};

		Leg tp = null, sl = null;
		boolean opening = !reduceOnly && !r.type.isTrigger() && !spot;
		if (opening && (r.tpPx > 0 || r.tpPct > 0)) {
			double tpx = asset.roundPrice(r.tpPx > 0 ? r.tpPx : px * (isBuy ? 1 + r.tpPct / 100 : 1 - r.tpPct / 100));
			if (isBuy ? tpx <= px : tpx >= px) throw new PlanException("TP price must be " + (isBuy ? "above " : "below ") + Fmt.price(px));
			tp = new Leg(!isBuy, size, asset.roundPrice(tpx * (isBuy ? 1 - TPSL_SLIPPAGE : 1 + TPSL_SLIPPAGE)), true, tpx, "tp", OrderRequest.Tif.GTC, true, false);
		}
		if (opening && (r.slPx > 0 || r.slPct > 0)) {
			if (r.slPx <= 0 && r.slPct >= 100 && isBuy) throw new PlanException("Stop-loss must be under 100%");
			double spx = asset.roundPrice(r.slPx > 0 ? r.slPx : px * (isBuy ? 1 - r.slPct / 100 : 1 + r.slPct / 100));
			if (isBuy ? spx >= px : spx <= px) throw new PlanException("SL price must be " + (isBuy ? "below " : "above ") + Fmt.price(px));
			sl = new Leg(!isBuy, size, asset.roundPrice(spx * (isBuy ? 1 - TPSL_SLIPPAGE : 1 + TPSL_SLIPPAGE)), true, spx, "sl", OrderRequest.Tif.GTC, true, false);
		}
		return new Plan(asset, entry, tp, sl);
	}

	/** Short human description, e.g. "Limit buy 0.01 BTC @ 80,000 (TP 88,000, SL 76,000)". */
	public static String describe(Plan p) {
		Leg e = p.entry();
		String kind = e.trigger() ? (e.tpsl().equals("sl") ? "Stop" : "Take-profit") + (e.triggerLimit() ? " limit" : "") : e.immediate() ? "Market" : "Limit";
		StringBuilder sb = new StringBuilder(kind).append(e.isBuy() ? " buy " : " sell ").append(Fmt.size(e.size())).append(' ').append(p.asset().name());
		if (e.trigger()) sb.append(" @ ").append(Fmt.price(e.triggerPx()));
		else if (!e.immediate()) sb.append(" @ ").append(Fmt.price(e.limitPx()));
		if (e.reduceOnly()) sb.append(" (reduce-only)");
		if (p.tp() != null || p.sl() != null) {
			sb.append(" [");
			if (p.tp() != null) sb.append("TP ").append(Fmt.price(p.tp().triggerPx()));
			if (p.tp() != null && p.sl() != null) sb.append(", ");
			if (p.sl() != null) sb.append("SL ").append(Fmt.price(p.sl().triggerPx()));
			sb.append(']');
		}
		return sb.toString();
	}
}
