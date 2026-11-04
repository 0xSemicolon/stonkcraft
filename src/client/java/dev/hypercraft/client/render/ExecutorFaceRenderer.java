package dev.hypercraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.hypercraft.block.ExecutorInput;
import dev.hypercraft.block.TradeExecutorBlockEntity;
import dev.hypercraft.client.ui.Ui;
import dev.hypercraft.trading.Fmt;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** The floating label, plus each input's tag and range painted on the face that feeds it, so the wiring reads at a glance. */
public class ExecutorFaceRenderer implements BlockEntityRenderer<TradeExecutorBlockEntity> {
	private static final float PX = 1f / 100f;
	private final Font font;
	private final FloatingLabelRenderer<TradeExecutorBlockEntity> label;

	public ExecutorFaceRenderer(BlockEntityRendererProvider.Context ctx) {
		this.font = ctx.getFont();
		this.label = new FloatingLabelRenderer<>(ctx);
	}

	@Override
	public boolean shouldRenderOffScreen(TradeExecutorBlockEntity be) {
		return label.shouldRenderOffScreen(be);
	}

	@Override
	public int getViewDistance() {
		return label.getViewDistance();
	}

	@Override
	public void render(TradeExecutorBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
		label.render(be, partialTick, pose, buffers, light, overlay);
		for (Map.Entry<ExecutorInput, TradeExecutorBlockEntity.Binding> e : be.inputs.entrySet()) {
			Direction d = e.getValue().source.face();
			if (d == null) continue;
			pose.pushPose();
			pose.translate(0.5, 0.5, 0.5);
			if (d.getAxis().isHorizontal()) {
				pose.mulPose(Axis.YP.rotationDegrees(-d.toYRot()));
			} else {
				pose.mulPose(Axis.XP.rotationDegrees(d == Direction.UP ? -90 : 90));
			}
			pose.translate(0, 0, 0.502);
			pose.scale(PX, -PX, PX);
			ExecutorInput in = e.getKey();
			TradeExecutorBlockEntity.Binding b = e.getValue();
			String range = fmt(in, b.lo) + "–" + fmt(in, b.hi);
			draw(pose, buffers, in.tag, -14, 1.6f, Ui.YELLOW);
			draw(pose, buffers, range, 6, Math.min(0.9f, 70f / Math.max(1, font.width(range))), Ui.TEXT);
			pose.popPose();
		}
	}

	private static String fmt(ExecutorInput in, double v) {
		return switch (in) {
			case SIZE -> "$" + Fmt.usdCompact(v).replace("$", "");
			case LEVERAGE, MINUTES -> Math.round(v) + in.unit;
			default -> Fmt.size(v) + in.unit;
		};
	}

	private void draw(PoseStack pose, MultiBufferSource buffers, String s, float y, float scale, int color) {
		pose.pushPose();
		pose.translate(-font.width(s) * scale / 2f, y, 0);
		pose.scale(scale, scale, 1);
		font.drawInBatch(s, 0, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0xC0081214, LightTexture.FULL_BRIGHT);
		pose.popPose();
	}
}
