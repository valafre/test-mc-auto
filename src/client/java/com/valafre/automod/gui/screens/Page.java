package com.valafre.automod.gui.screens;

import com.valafre.automod.gui.components.Block;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Page affichée dans la zone de contenu. Elle occupe un rectangle imposé par l'écran ({@link #setBounds}) et se met en
 * page dans {@link #arrange()}. Un titre standard est fourni par {@link #drawHeader}.
 */
public abstract class Page extends Block {

	protected static final int HEADER_H = 46;

	/** Place les enfants dans le rectangle (x, y, w, h). */
	protected abstract void arrange();

	@Override
	public void setBounds(int x, int y, int w, int h) {
		super.setBounds(x, y, w, h);
		arrange();
	}

	@Override
	public int layout(int x, int y, int width) {
		return h;
	}

	/** Titre agrandi + sous-titre. */
	protected void drawHeader(Ui ui, GuiGraphicsExtractor g, String title, String subtitle, int left) {
		Draw.scaled(g, ui.font, title, left, y + 2, 1.5f, Theme.TEXT);
		if (subtitle != null && !subtitle.isEmpty()) {
			Draw.text(g, ui.font, Draw.fit(ui.font, subtitle, w - (left - x)), left, y + 26, Theme.TEXT2);
		}
	}
}
