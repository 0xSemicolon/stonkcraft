package dev.stonkcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.stonkcraft.block.AccountDisplayBlockEntity;
import dev.stonkcraft.client.ui.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/** Account name, metric label, the big figure, a detail line and an equity sparkline on the front face. */
public class AccountDisplayRenderer implements BlockEntityRenderer<AccountDisplayBlockEntity> {
	private static final char[] BARS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
	private static final float PX = 1f / 100f;
	private static final int GOLD = 0xFFF2C94C;

	private final Font font;

	public AccountDisplayRenderer(BlockEntityRendererProvider.Context ctx) {
		this.font = ctx.getFont();
	}

	@Override
	public void render(AccountDisplayBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
		Direction facing = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
		pose.pushPose();
		pose.translate(0.5, 0.5, 0.5);
		pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
		pose.translate(0, 0, 0.5025);
		pose.scale(PX, -PX, PX);
		int bright = LightTexture.FULL_BRIGHT;

		String label = be.metric.label;
		String name = be.dName.isEmpty() ? "no card" : be.dName;
		fit(pose, buffers, name, -40, -40, 0.8f, 80, GOLD, bright, false);
		float tagW = 0;
		if (!be.dTag.isEmpty()) {
			tagW = Math.min(40, font.width(be.dTag) * 0.6f);
			fit(pose, buffers, be.dTag, 40 - tagW, -30, 0.6f, 40, Ui.tagColor(be.dTag), bright, false);
		}
		fit(pose, buffers, label.toUpperCase(), -40, -30, 0.6f, 80 - tagW - (tagW > 0 ? 4 : 0), Ui.DIM, bright, false);

		int color = be.dTone > 0 ? Ui.GREEN : be.dTone < 0 ? Ui.RED : Ui.TEXT;
		if (be.dName.isEmpty() || be.dValue.equals("unavailable") || be.dValue.equals("set a coin")) color = Ui.DIM;
		fit(pose, buffers, be.dValue, 0, -16, 2.2f, 80, color, bright, true);
		if (!be.dSub.isEmpty()) fit(pose, buffers, be.dSub, 0, 6, 0.7f, 80, Ui.DIM, bright, true);

		float[] h = be.dSpark;
		if (h.length > 1) {
			int n = Math.min(h.length, 20);
			float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
			for (int i = h.length - n; i < h.length; i++) {
				lo = Math.min(lo, h[i]);
				hi = Math.max(hi, h[i]);
			}
			StringBuilder sb = new StringBuilder();
			for (int i = h.length - n; i < h.length; i++) {
				sb.append(BARS[hi > lo ? Math.round((h[i] - lo) / (hi - lo) * (BARS.length - 1)) : BARS.length / 2]);
			}
			int sc = h[h.length - 1] >= h[h.length - n] ? Ui.GREEN : Ui.RED;
			fit(pose, buffers, sb.toString(), 0, 24, 1.2f, 80, sc, bright, true);
		}
		pose.popPose();
	}

	/** Draws text at up to {@code maxScale}, shrunk to fit {@code width}; centred on x or left-aligned at x. */
	private void fit(PoseStack pose, MultiBufferSource buffers, String s, float x, float y, float maxScale, float width, int color, int light, boolean center) {
		float scale = Math.min(maxScale, width / Math.max(1, font.width(s)));
		float dx = center ? x - font.width(s) * scale / 2f : x;
		pose.pushPose();
		pose.translate(dx, y, 0);
		pose.scale(scale, scale, 1);
		font.drawInBatch(s, 0, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
		pose.popPose();
	}
}
