package dev.hypercraft.client.mixin;

import dev.hypercraft.client.ui.UiLint;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Feeds drawn text and scissor changes to the dev-only {@link UiLint}; does nothing unless it is recording. */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
	@Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I", at = @At("HEAD"))
	private void hypercraft$lintString(Font font, String text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> cir) {
		if (UiLint.recording && text != null) UiLint.text(((GuiGraphics) (Object) this).pose().last().pose(), x, y, font.width(text), text);
	}

	@Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I", at = @At("HEAD"))
	private void hypercraft$lintSequence(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> cir) {
		if (!UiLint.recording) return;
		StringBuilder sb = new StringBuilder();
		text.accept((i, style, cp) -> {
			sb.appendCodePoint(cp);
			return true;
		});
		UiLint.text(((GuiGraphics) (Object) this).pose().last().pose(), x, y, font.width(text), sb.toString());
	}

	@Inject(method = "enableScissor", at = @At("HEAD"))
	private void hypercraft$scissorOn(int x0, int y0, int x1, int y1, CallbackInfo ci) {
		UiLint.pushScissor(x0, y0, x1, y1);
	}

	@Inject(method = "disableScissor", at = @At("HEAD"))
	private void hypercraft$scissorOff(CallbackInfo ci) {
		UiLint.popScissor();
	}
}
