package dev.stonkcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.stonkcraft.block.TickerDisplayBlockEntity;
import dev.stonkcraft.client.ClientState;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.trading.Fmt;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/** Draws coin, price, 24h change and a text sparkline on the display's front face. */
public class TickerDisplayRenderer implements BlockEntityRenderer<TickerDisplayBlockEntity> {
	private static final char[] BARS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
	/** Font pixels per block on the face. */
	private static final float PX = 1f / 100f;

	private final Font font;

	public TickerDisplayRenderer(BlockEntityRendererProvider.Context ctx) {
		this.font = ctx.getFont();
	}

	@Override
	public void render(TickerDisplayBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
		Direction facing = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
		ClientState.Ticker t = ClientState.ticker(be.coin);

		pose.pushPose();
		pose.translate(0.5, 0.5, 0.5);
		pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
		pose.translate(0, 0, 0.5025);
		pose.scale(PX, -PX, PX);
		int bright = LightTexture.FULL_BRIGHT;

		// face spans -50..50 font pixels; keep a margin. The market's logo, then its short name
		Logos.drawWorld(pose, buffers, font, be.coin, -44, -44, 32, bright);
		String name = Logos.key(be.coin);
		float ns = Math.min(1.5f, 50f / Math.max(1, font.width(name)));
		draw(pose, buffers, name, -6, -42, ns, Ui.TEXT, bright);
		if (t == null) {
			draw(pose, buffers, "no data", -40, -10, 1f, Ui.DIM, bright);
			pose.popPose();
			return;
		}
		double ch = t.dayChangePct();
		String pct = Fmt.pct(ch);
		float pctScale = 0.75f;
		draw(pose, buffers, pct, -6, -26, 0.9f, Ui.signColor(ch), bright);

		String price = "$" + Fmt.price(t.mid);
		float scale = Math.min(2.0f, 84f / Math.max(1, font.width(price)));
		long now = System.currentTimeMillis();
		int color = now - t.lastMoveAt < 700 ? (t.lastMove > 0 ? Ui.GREEN : Ui.RED) : Ui.TEXT;
		draw(pose, buffers, price, -font.width(price) * scale / 2f, -6, scale, color, bright);

		double[] hist = t.history();
		if (hist.length > 1) {
			int n = Math.min(hist.length, 20);
			double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
			for (int i = hist.length - n; i < hist.length; i++) {
				lo = Math.min(lo, hist[i]);
				hi = Math.max(hi, hist[i]);
			}
			StringBuilder sb = new StringBuilder();
			for (int i = hist.length - n; i < hist.length; i++) {
				int idx = hi > lo ? (int) Math.round((hist[i] - lo) / (hi - lo) * (BARS.length - 1)) : BARS.length / 2;
				sb.append(BARS[idx]);
			}
			String spark = sb.toString();
			float ss = Math.min(1.2f, 80f / Math.max(1, font.width(spark)));
			int sc = hist[hist.length - 1] >= hist[hist.length - n] ? Ui.GREEN : Ui.RED;
			draw(pose, buffers, spark, -font.width(spark) * ss / 2f, 16, ss, sc, bright);
		}

		String info = String.format("F %.1f%%  V %s", t.fundingApr(), Fmt.usdCompact(t.volume));
		float is = Math.min(0.7f, 80f / Math.max(1, font.width(info)));
		draw(pose, buffers, info, -font.width(info) * is / 2f, 34, is, Ui.DIM, bright);
		pose.popPose();
	}

	private void draw(PoseStack pose, MultiBufferSource buffers, String s, float x, float y, float scale, int color, int light) {
		pose.pushPose();
		pose.translate(x, y, 0);
		pose.scale(scale, scale, 1);
		font.drawInBatch(s, 0, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
		pose.popPose();
	}
}
