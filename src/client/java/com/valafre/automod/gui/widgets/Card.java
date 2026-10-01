package com.valafre.automod.gui.widgets;

import com.valafre.automod.gui.components.Block;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Carte arrondie avec bordure fine, un titre optionnel et des lignes empilées. */
public final class Card extends Block {

	private static final int PAD = Theme.S12;

	private final String title;
	private final String subtitle;

	public Card(String title, String subtitle) {
		this.title = title;
		this.subtitle = subtitle;
	}

	public Card(String title) {
		this(title, null);
	}

	private boolean hasTitle() {
		return title != null && !title.isEmpty();
	}

	@Override
	public int layout(int x, int y, int width) {
		int cy = y + PAD;
		if (hasTitle()) {
			cy += subtitle == null || subtitle.isEmpty() ? 20 : 32;
		}
		boolean first = true;
		for (var c : children) {
			if (!c.visible) {
				continue;
			}
			if (c instanceof SettingRow r) {
				r.divider(!first);
			}
			first = false;
			cy += c instanceof Block b ? b.layout(x + PAD, cy, width - PAD * 2) : fixed(c, x + PAD, cy, width - PAD * 2);
		}
		int h = cy - y + PAD - (children.isEmpty() ? PAD : 0);
		setBounds(x, y, width, h);
		return h;
	}

	private static int fixed(com.valafre.automod.gui.components.UiComponent c, int x, int y, int width) {
		c.setBounds(x, y, width, c.h);
		return c.h + 4;
	}

	@Override
	protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		Draw.panel(g, x, y, w, h, Theme.RADIUS_L, Theme.PANEL, Theme.BORDER);
		if (hasTitle()) {
			Draw.round(g, x + PAD, y + PAD + 2, 3, 11, 1, Theme.accent());
			Draw.text(g, ui.font, title, x + PAD + 9, y + PAD + 3, Theme.TEXT);
			if (subtitle != null && !subtitle.isEmpty()) {
				Draw.text(g, ui.font, Draw.fit(ui.font, subtitle, w - PAD * 2 - 9), x + PAD + 9, y + PAD + 17, Theme.TEXT2);
			}
		}
	}
}
