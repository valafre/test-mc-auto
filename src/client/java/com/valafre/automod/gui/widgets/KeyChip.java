package com.valafre.automod.gui.widgets;

import com.valafre.automod.gui.theme.Draw;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Pastille de touche (« INSERT ») utilisée par le panneau des raccourcis. */
public final class KeyChip {

	private KeyChip() {}

	public static int width(Font font, String key) {
		return Math.max(18, font.width(key) + 10);
	}

	/** Dessine la pastille dont le coin haut-gauche est (x, y) ; renvoie sa largeur. */
	public static int draw(GuiGraphicsExtractor g, Font font, String key, int x, int y, int fill, int border, int text) {
		int w = width(font, key);
		Draw.panel(g, x, y, w, 14, 4, fill, border);
		Draw.centered(g, font, key, x + w / 2, y + 3, text);
		return w;
	}
}
