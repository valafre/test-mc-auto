package com.valafre.automod.gui.theme;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Primitives de dessin personnalisées : rectangles arrondis, bordures, anneaux, icônes teintées, texte. */
public final class Draw {

	private Draw() {}

	/** Opacité globale appliquée à tout ce qui est dessiné (HUD configurable). */
	private static float opacity = 1f;

	public static void setOpacity(float value) {
		opacity = Math.max(0f, Math.min(1f, value));
	}

	// ========================================
	// COULEURS
	// ========================================

	public static int alpha(int argb, float factor) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	public static int mix(int a, int b, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int ra = Math.round(lerp((a >>> 24) & 0xFF, (b >>> 24) & 0xFF, t));
		int rr = Math.round(lerp((a >> 16) & 0xFF, (b >> 16) & 0xFF, t));
		int rg = Math.round(lerp((a >> 8) & 0xFF, (b >> 8) & 0xFF, t));
		int rb = Math.round(lerp(a & 0xFF, b & 0xFF, t));
		return (ra << 24) | (rr << 16) | (rg << 8) | rb;
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static int op(int argb) {
		return opacity >= 0.999f ? argb : alpha(argb, opacity);
	}

	// ========================================
	// FORMES
	// ========================================

	public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		if (w > 0 && h > 0) {
			g.fill(x, y, x + w, y + h, op(color));
		}
	}

	/** Retrait horizontal de la ligne {@code row} (0 = haut) d'un coin de rayon {@code r}. */
	private static int inset(int row, int r) {
		double dy = r - row - 0.5;
		return (int) Math.round(r - Math.sqrt(Math.max(0, r * (double) r - dy * dy)));
	}

	public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int c = op(color);
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r == 0) {
			g.fill(x, y, x + w, y + h, c);
			return;
		}
		for (int i = 0; i < r; i++) {
			int in = inset(i, r);
			g.fill(x + in, y + i, x + w - in, y + i + 1, c);
			g.fill(x + in, y + h - 1 - i, x + w - in, y + h - i, c);
		}
		g.fill(x, y + r, x + w, y + h - r, c);
	}

	/** Bordure d'1 px suivant un rectangle arrondi. */
	public static void roundBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (w <= 1 || h <= 1) {
			return;
		}
		int c = op(color);
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r == 0) {
			g.fill(x, y, x + w, y + 1, c);
			g.fill(x, y + h - 1, x + w, y + h, c);
			g.fill(x, y + 1, x + 1, y + h - 1, c);
			g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
			return;
		}
		for (int i = 0; i < r; i++) {
			int in = inset(i, r);
			if (i == 0) {
				g.fill(x + in, y, x + w - in, y + 1, c);
				g.fill(x + in, y + h - 1, x + w - in, y + h, c);
			} else {
				int next = inset(i - 1, r);
				int span = Math.max(1, next - in);
				g.fill(x + in, y + i, x + in + span, y + i + 1, c);
				g.fill(x + w - in - span, y + i, x + w - in, y + i + 1, c);
				g.fill(x + in, y + h - 1 - i, x + in + span, y + h - i, c);
				g.fill(x + w - in - span, y + h - 1 - i, x + w - in, y + h - i, c);
			}
		}
		g.fill(x, y + r, x + 1, y + h - r, c);
		g.fill(x + w - 1, y + r, x + w, y + h - r, c);
	}

	/** Rectangle arrondi plein avec bordure. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int fill, int border) {
		round(g, x, y, w, h, r, fill);
		roundBorder(g, x, y, w, h, r, border);
	}

	/** Repeint les 4 coins extérieurs d'un rectangle arrondi avec la couleur de fond (masque une image carrée). */
	public static void cornerMask(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int bg) {
		for (int i = 0; i < r; i++) {
			int in = inset(i, r);
			if (in <= 0) {
				continue;
			}
			g.fill(x, y + i, x + in, y + i + 1, bg);
			g.fill(x + w - in, y + i, x + w, y + i + 1, bg);
			g.fill(x, y + h - 1 - i, x + in, y + h - i, bg);
			g.fill(x + w - in, y + h - 1 - i, x + w, y + h - i, bg);
		}
	}

	public static void circle(GuiGraphicsExtractor g, int cx, int cy, int radius, int color) {
		round(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
	}

	/**
	 * Anneau de progression : épaisseur {@code thick}, balayage depuis le haut dans le sens horaire sur {@code fraction}
	 * (0..1) ; le reste est dessiné en {@code trackColor}.
	 */
	public static void ring(GuiGraphicsExtractor g, int cx, int cy, int radius, int thick, float fraction, int color, int trackColor) {
		float sweep = (float) (Math.max(0f, Math.min(1f, fraction)) * Math.PI * 2);
		double inner = radius - thick;
		int c = op(color);
		int t = op(trackColor);
		for (int dy = -radius; dy < radius; dy++) {
			int runStart = Integer.MIN_VALUE;
			int runColor = 0;
			for (int dx = -radius; dx <= radius; dx++) {
				double px = dx + 0.5;
				double py = dy + 0.5;
				double d2 = px * px + py * py;
				int pix = 0;
				if (d2 <= radius * (double) radius && d2 >= inner * inner) {
					double ang = Math.atan2(px, -py); // 0 en haut, sens horaire
					if (ang < 0) {
						ang += Math.PI * 2;
					}
					pix = ang <= sweep ? c : t;
				}
				if (pix != runColor) {
					if (runColor != 0) {
						g.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, runColor);
					}
					runStart = dx;
					runColor = pix;
				}
			}
			if (runColor != 0) {
				g.fill(cx + runStart, cy + dy, cx + radius + 1, cy + dy + 1, runColor);
			}
		}
	}

	// ========================================
	// TEXTURES
	// ========================================

	private static final RenderPipeline TEX = RenderPipelines.GUI_TEXTURED;

	/** Icône blanche 32x32 teintée de {@code color}, dessinée en {@code size} px. */
	public static void icon(GuiGraphicsExtractor g, Identifier texture, int x, int y, int size, int color) {
		g.blit(TEX, texture, x, y, 0f, 0f, size, size, 32, 32, 32, 32, op(color));
	}

	/** Image de {@code texW}x{@code texH} recadrée (cover) pour remplir w x h. */
	public static void cover(GuiGraphicsExtractor g, Identifier texture, int texW, int texH, int x, int y, int w, int h, int tint) {
		float want = w / (float) h;
		int srcW = texW;
		int srcH = texH;
		if (texW / (float) texH > want) {
			srcW = Math.round(texH * want);
		} else {
			srcH = Math.round(texW / want);
		}
		int u = (texW - srcW) / 2;
		int v = (texH - srcH) / 2;
		g.blit(TEX, texture, x, y, (float) u, (float) v, w, h, srcW, srcH, texW, texH, op(tint));
	}

	public static void image(GuiGraphicsExtractor g, Identifier texture, int texW, int texH, int x, int y, int w, int h) {
		g.blit(TEX, texture, x, y, 0f, 0f, w, h, texW, texH, texW, texH, op(0xFFFFFFFF));
	}

	// ========================================
	// TEXTE
	// ========================================

	public static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
		g.text(font, text, x, y, op(color), false);
	}

	public static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
		g.text(font, text, cx - font.width(text) / 2, y, op(color), false);
	}

	public static void right(GuiGraphicsExtractor g, Font font, String text, int rightX, int y, int color) {
		g.text(font, text, rightX - font.width(text), y, op(color), false);
	}

	/** Texte agrandi (échelle {@code scale}) dont le coin haut-gauche est (x, y). */
	public static void scaled(GuiGraphicsExtractor g, Font font, String text, int x, int y, float scale, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font, text, 0, 0, op(color), false);
		g.pose().popMatrix();
	}

	/** Texte tronqué avec « … » pour tenir dans {@code maxWidth}. */
	public static String fit(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ell = "…";
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end)) + font.width(ell) > maxWidth) {
			end--;
		}
		return text.substring(0, end) + ell;
	}
}
