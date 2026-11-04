package dev.stonkcraft.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.client.ClientConfig;
import dev.stonkcraft.client.ClientState;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * 8-bit market pictures (textures/logo/&lt;ticker&gt;.png, drawn by tools/textures/make_textures.py): generic ones only,
 * for metals, energy and currencies. Markets without one
 * drawn logo get a badge in a colour derived from their ticker, lettered with its first characters, so every
 * coin, stock and commodity has a mark in lists, on the tape and on Ticker Display faces.
 */
public final class Logos {
	private static final ResourceLocation BADGE = Stonkcraft.id("textures/logo/_badge.png");
	private static final Map<String, ResourceLocation> FOUND = new ConcurrentHashMap<>();
	private static final ResourceLocation NONE = Stonkcraft.id("none");

	private Logos() {}

	/** "xyz:GOLD" -> "GOLD", "HYPE/USDC" -> "HYPE", "kPEPE" -> "PEPE". */
	public static String key(String market) {
		String d = ClientState.display(market);
		if (d.length() > 1 && d.charAt(0) == 'k' && Character.isUpperCase(d.charAt(1))) d = d.substring(1);
		return d.toUpperCase(Locale.ROOT);
	}

	/** The drawn logo for a market, or null when it gets a lettered badge. */
	@Nullable
	public static ResourceLocation texture(String market) {
		String k = key(market);
		ResourceLocation r = FOUND.computeIfAbsent(k, kk -> {
			ResourceLocation loc = Stonkcraft.id("textures/logo/" + kk.toLowerCase(Locale.ROOT) + ".png");
			return Minecraft.getInstance().getResourceManager().getResource(loc).isPresent() ? loc : NONE;
		});
		return r == NONE ? null : r;
	}

	/** A stable, saturated colour for a ticker's badge. */
	public static int color(String market) {
		int h = key(market).hashCode();
		float hue = (h & 0xFFFF) / 65535f;
		return java.awt.Color.HSBtoRGB(hue, 0.62f, 0.78f) | 0xFF000000;
	}

	/** One or two letters for a badge. */
	public static String initials(String market) {
		String k = key(market).replaceAll("[^A-Z0-9]", "");
		if (k.isEmpty()) return "?";
		return k.length() <= 2 ? k : k.substring(0, 1);
	}

	// ------------------------------------------------------------------ screens

	/** Draws the logo in a {@code size} square at (x, y). */
	public static void draw(GuiGraphics g, Font font, String market, int x, int y, int size) {
		ResourceLocation tex = texture(market);
		if (tex != null) {
			g.blit(tex, x, y, size, size, 0, 0, 16, 16, 16, 16);
			return;
		}
		int c = color(market);
		RenderSystem.setShaderColor(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, 1f);
		g.blit(BADGE, x, y, size, size, 0, 0, 16, 16, 16, 16);
		RenderSystem.setShaderColor(1, 1, 1, 1);
		String s = initials(market);
		float scale = Math.min(size / 12f, (size * 0.8f) / Math.max(1, font.width(s)));
		g.pose().pushPose();
		g.pose().translate(x + size / 2f - font.width(s) * scale / 2f, y + size / 2f - 3.5f * scale, 0);
		g.pose().scale(scale, scale, 1);
		g.drawString(font, s, 0, 0, 0xFFFFFFFF, false);
		g.pose().popPose();
	}

	// ------------------------------------------------------------------ in the world

	/**
	 * Draws the logo as a full-bright quad in the current pose (font-pixel space: x right, y down), {@code size} across,
	 * top-left at (x, y). Lettered badges get their letters too.
	 */
	public static void drawWorld(PoseStack pose, MultiBufferSource buffers, Font font, String market, float x, float y, float size, int light) {
		ResourceLocation tex = texture(market);
		int c = tex != null ? 0xFFFFFFFF : color(market);
		quad(pose, buffers.getBuffer(RenderType.text(tex != null ? tex : BADGE)), x, y, size, c, light);
		if (tex == null) {
			String s = initials(market);
			float scale = Math.min(size / 12f, (size * 0.8f) / Math.max(1, font.width(s)));
			pose.pushPose();
			pose.translate(x + size / 2f - font.width(s) * scale / 2f, y + size / 2f - 3.5f * scale, -0.05f);
			pose.scale(scale, scale, 1);
			font.drawInBatch(s, 0, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
			pose.popPose();
		}
	}

	private static void quad(PoseStack pose, VertexConsumer vc, float x, float y, float s, int argb, int light) {
		Matrix4f m = pose.last().pose();
		float z = -0.02f;
		// both windings, so the quad shows whichever way the face was turned
		vc.addVertex(m, x, y, z).setColor(argb).setUv(0, 0).setLight(light);
		vc.addVertex(m, x, y + s, z).setColor(argb).setUv(0, 1).setLight(light);
		vc.addVertex(m, x + s, y + s, z).setColor(argb).setUv(1, 1).setLight(light);
		vc.addVertex(m, x + s, y, z).setColor(argb).setUv(1, 0).setLight(light);
		vc.addVertex(m, x + s, y, z).setColor(argb).setUv(1, 0).setLight(light);
		vc.addVertex(m, x + s, y + s, z).setColor(argb).setUv(1, 1).setLight(light);
		vc.addVertex(m, x, y + s, z).setColor(argb).setUv(0, 1).setLight(light);
		vc.addVertex(m, x, y, z).setColor(argb).setUv(0, 0).setLight(light);
	}
}
