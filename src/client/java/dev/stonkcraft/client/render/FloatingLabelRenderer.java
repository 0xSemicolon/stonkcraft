package dev.stonkcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.stonkcraft.block.HcBlockEntity;
import dev.stonkcraft.block.KillSwitchBlockEntity;
import dev.stonkcraft.block.LeverageDialBlockEntity;
import dev.stonkcraft.block.MarketSensorBlockEntity;
import dev.stonkcraft.block.SensorTest;
import dev.stonkcraft.block.TradeExecutorBlockEntity;
import dev.stonkcraft.client.ui.Ui;
import dev.stonkcraft.trading.Fmt;
import dev.stonkcraft.trading.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Nametag-style label above sensors and executors so a redstone algo is readable at a glance. */
public class FloatingLabelRenderer<T extends HcBlockEntity> implements BlockEntityRenderer<T> {
	private static final double MAX_DIST_SQ = 14 * 14;
	/** Unfocused blocks show a compact one-liner only this close. */
	private static final double NEAR_DIST_SQ = 8 * 8;
	private final Font font;

	public FloatingLabelRenderer(BlockEntityRendererProvider.Context ctx) {
		this.font = ctx.getFont();
	}

	@Override
	public void render(T be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
		double distSq = cam.distanceToSqr(be.getBlockPos().getCenter());
		if (distSq < DECAL_DIST_SQ) decals(be, pose, buffers);
		if (distSq > MAX_DIST_SQ || mc.options.hideGui) return;
		// full label for the block under the crosshair, a small summary for nearby ones, nothing otherwise
		boolean focused = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(be.getBlockPos());
		// the focused label is wide: neighbours' one-liners would sit under it
		if (!focused && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().distManhattan(be.getBlockPos()) <= 2 && mc.level != null
				&& mc.level.getBlockEntity(hit.getBlockPos()) instanceof HcBlockEntity) return;

		String top;
		String bottom;
		String logo = null;
		int topColor;
		int bottomColor;
		if (be instanceof MarketSensorBlockEntity s) {
			int power = be.getBlockState().getValue(BlockStateProperties.POWER);
			logo = market(s);
			top = s.condition() + (s.reading.card && s.cardRef() != null ? " · " + s.cardRef().name() : "");
			bottom = (s.test == SensorTest.ANALOG ? "▮ " + power : power > 0 ? "● ON" : "○ off") + (s.readingText.isEmpty() ? "" : "  " + s.readingText);
			topColor = Ui.ACCENT;
			bottomColor = power > 0 ? Ui.GREEN : Ui.DIM;
		} else if (be instanceof TradeExecutorBlockEntity || be instanceof KillSwitchBlockEntity || be instanceof LeverageDialBlockEntity) {
			if (be instanceof TradeExecutorBlockEntity e) {
				logo = e.coin;
				top = e.describe();
				topColor = 0xFF000000 | (e.side == Side.CLOSE ? Ui.YELLOW : e.side.color);
			} else if (be instanceof KillSwitchBlockEntity k) {
				top = k.describe();
				topColor = Ui.RED;
			} else {
				top = ((LeverageDialBlockEntity) be).describe();
				topColor = Ui.YELLOW;
			}
			var ref = be.cardRef();
			if (ref == null) {
				bottom = "⚠ insert a Trade Card";
				bottomColor = Ui.YELLOW;
			} else {
				top += " · " + ref.name();
				bottom = be.lastMessage.isEmpty() ? "next: power it with redstone" : be.lastMessage;
				bottomColor = be.lastStatus > 0 ? Ui.GREEN : be.lastStatus < 0 ? Ui.RED : Ui.DIM;
			}
		} else if (be.labelTop() != null) {
			top = be.labelTop();
			topColor = be.labelColor();
			bottom = be.labelBottom();
			bottomColor = be.lastStatus > 0 ? Ui.GREEN : be.lastStatus < 0 ? Ui.RED : Ui.DIM;
		} else {
			return;
		}
		// far off, only a block that still needs something keeps its one-liner
		if (!focused && distSq > NEAR_DIST_SQ && (bottom == null || !bottom.startsWith("⚠"))) return;

		pose.pushPose();
		pose.translate(0.5, focused ? 1.45 : 1.25, 0.5);
		pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
		float scale = focused ? 0.018f : 0.011f;
		pose.scale(scale, -scale, scale);
		int bg = (int) (mc.options.getBackgroundOpacity(focused ? 0.45f : 0.25f) * 255f) << 24;
		if (focused) {
			line(pose, buffers, top, -10, topColor, bg);
			if (logo != null) Logos.drawWorld(pose, buffers, font, logo, -font.width(top) / 2f - 12, -11, 10, LightTexture.FULL_BRIGHT);
			line(pose, buffers, bottom, 1, bottomColor, bg);
		} else {
			// one block wide at this scale, so side-by-side blocks don't collide
			// a block that can't work yet says so from a distance too
			boolean needs = bottom != null && bottom.startsWith("⚠");
			line(pose, buffers, fit(needs ? bottom : top, 88), -4, ((needs ? Ui.YELLOW : topColor) & 0xFFFFFF) | 0xC0000000, bg);
		}
		pose.popPose();
	}

	private static final double DECAL_DIST_SQ = 32 * 32;

	/** The market a block trades or watches, for its face decal; null when it isn't about one market. */
	private static String market(HcBlockEntity be) {
		if (be instanceof MarketSensorBlockEntity s) return !s.reading.coin || s.coin.isEmpty() ? null : s.coin;
		if (be instanceof TradeExecutorBlockEntity e) return e.coin;
		if (be instanceof LeverageDialBlockEntity d) return d.coin.isEmpty() ? null : d.coin;
		return null;
	}

	/** The market's logo in the top-left corner of each side face, inside the gold trim, always shown. */
	private void decals(HcBlockEntity be, PoseStack pose, MultiBufferSource buffers) {
		String m = market(be);
		if (m == null) return;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			pose.pushPose();
			pose.translate(0.5, 0.5, 0.5);
			pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-d.toYRot()));
			pose.translate(0, 0, 0.5015);
			pose.scale(0.01f, -0.01f, 0.01f);
			Logos.drawWorld(pose, buffers, font, m, -40, -40, 28, LightTexture.FULL_BRIGHT);
			pose.popPose();
		}
	}

	private String fit(String s, int w) {
		return font.width(s) <= w ? s : font.plainSubstrByWidth(s, w - font.width("…")) + "…";
	}

	private void line(PoseStack pose, MultiBufferSource buffers, String s, int y, int color, int bg) {
		float x = -font.width(s) / 2f;
		font.drawInBatch(s, x, y, color, false, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, bg, LightTexture.FULL_BRIGHT);
	}

	@Override
	public boolean shouldRenderOffScreen(T be) {
		return true;
	}
}
