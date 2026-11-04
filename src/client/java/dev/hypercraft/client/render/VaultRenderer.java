package dev.hypercraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.hypercraft.block.AccountVaultBlockEntity;
import dev.hypercraft.client.ui.Ui;
import dev.hypercraft.trading.Fmt;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/** Account name, kind and equity on the vault's front panel. */
public class VaultRenderer implements BlockEntityRenderer<AccountVaultBlockEntity> {
	private static final float PX = 1f / 100f;
	private final Font font;

	public VaultRenderer(BlockEntityRendererProvider.Context ctx) {
		this.font = ctx.getFont();
	}

	@Override
	public void render(AccountVaultBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
		Direction facing = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
		pose.pushPose();
		pose.translate(0.5, 0.5, 0.5);
		pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
		pose.translate(0, 0, 0.5025);
		pose.scale(PX, -PX, PX);
		int bright = LightTexture.FULL_BRIGHT;
		// the display strip of the texture spans roughly -38..38 x -40..4; the dial sits below it
		if (be.accountId == null) {
			centered(pose, buffers, "EMPTY", -28, 1f, Ui.DIM, bright);
			centered(pose, buffers, "right-click", -14, 0.6f, 0xFF4E6E6A, bright);
		} else {
			String label = be.displayLabel;
			int color = Ui.tagColor(label);
			centered(pose, buffers, be.displayName, -35, Math.min(0.75f, 70f / Math.max(1, font.width(be.displayName))), Ui.TEXT, bright);
			String eq = Double.isNaN(be.displayEquity) ? "--" : Fmt.usd(be.displayEquity);
			centered(pose, buffers, eq, -24, Math.min(1.1f, 70f / Math.max(1, font.width(eq))), color, bright);
			centered(pose, buffers, label, -10, 0.55f, (color & 0xFFFFFF) | 0xB0000000, bright);
		}
		pose.popPose();
	}

	private void centered(PoseStack pose, MultiBufferSource buffers, String s, float y, float scale, int color, int light) {
		pose.pushPose();
		pose.translate(-font.width(s) * scale / 2f, y, 0);
		pose.scale(scale, scale, 1);
		font.drawInBatch(s, 0, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
		pose.popPose();
	}
}
