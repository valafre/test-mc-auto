package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Infobulle sombre à coins arrondis, repliée sur plusieurs lignes. */
public final class Tooltip {

	private Tooltip() {}

	public static void draw(Ui ui, GuiGraphicsExtractor g, String text, int mx, int my, int sw, int sh) {
		int maxW = Math.min(220, sw - 16);
		var lines = ui.font.split(net.minecraft.network.chat.Component.literal(text), maxW);
		int tw = 0;
		for (var l : lines) {
			tw = Math.max(tw, ui.font.width(l));
		}
		int w = tw + 12;
		int h = lines.size() * (ui.font.lineHeight + 1) + 9;
		int x = Math.max(4, Math.min(mx + 10, sw - w - 4));
		int y = my + 14 + h > sh ? my - h - 6 : my + 14;
		Draw.panel(g, x, y, w, h, 6, 0xF0161622, Theme.BORDER_STRONG);
		int ty = y + 5;
		for (var l : lines) {
			g.text(ui.font, l, x + 6, ty, 0xFFF5F5F7, false);
			ty += ui.font.lineHeight + 1;
		}
	}
}
