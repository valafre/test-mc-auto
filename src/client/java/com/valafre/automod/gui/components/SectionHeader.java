package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Titre de section avec petit trait d'accent et sous-titre optionnel. */
public final class SectionHeader extends UiComponent {

	private final String title;
	private final String subtitle;

	public SectionHeader(String title, String subtitle) {
		this.title = title;
		this.subtitle = subtitle;
		this.h = subtitle == null || subtitle.isEmpty() ? 18 : 30;
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		Draw.round(g, x, y + 2, 3, 11, 1, Theme.accent());
		Draw.text(g, ui.font, title, x + 9, y + 3, Theme.TEXT);
		if (subtitle != null && !subtitle.isEmpty()) {
			Draw.text(g, ui.font, Draw.fit(ui.font, subtitle, w - 9), x + 9, y + 17, Theme.TEXT2);
		}
	}
}
