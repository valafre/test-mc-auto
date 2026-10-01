package com.valafre.automod.gui.widgets;

import com.valafre.automod.gui.components.Block;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.components.UiComponent;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Ligne de réglage : libellé + description à gauche, contrôle à droite. Quand la largeur manque, le contrôle passe
 * sous le texte (mise en page responsive).
 */
public final class SettingRow extends Block {

	private final String label;
	private final String description;
	private final UiComponent control;
	private final int controlWidth;
	private boolean stacked;
	private boolean divider;
	private int textW;

	public SettingRow(String label, String description, UiComponent control, int controlWidth) {
		this.label = label;
		this.description = description == null ? "" : description;
		this.control = control;
		this.controlWidth = controlWidth;
		if (control != null) {
			children.add(control);
		}
	}

	public SettingRow divider(boolean value) {
		this.divider = value;
		return this;
	}

	@Override
	public int layout(int x, int y, int width) {
		stacked = control != null && width < controlWidth + 170;
		int rowH = description.isEmpty() ? 30 : 38;
		int h = stacked ? rowH + control.h + 6 : Math.max(rowH, control == null ? 0 : control.h + 12);
		textW = stacked || control == null ? width : width - controlWidth - 14;
		if (control != null) {
			if (stacked) {
				control.setBounds(x, y + rowH, width, control.h);
			} else {
				control.setBounds(x + width - controlWidth, y + (h - control.h) / 2, controlWidth, control.h);
			}
		}
		setBounds(x, y, width, h);
		return h;
	}

	@Override
	protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		if (divider) {
			Draw.rect(g, x, y, w, 1, 0x0DFFFFFF);
		}
		int ty = description.isEmpty() ? y + (stacked ? 8 : (h - ui.font.lineHeight) / 2 + 1) : y + 7;
		boolean on = control == null || control.enabled;
		Draw.text(g, ui.font, Draw.fit(ui.font, label, textW), x, ty, on ? Theme.TEXT : Theme.DISABLED);
		if (!description.isEmpty()) {
			Draw.text(g, ui.font, Draw.fit(ui.font, description, textW), x, ty + 12, Theme.TEXT2);
		}
		if (contains(mx, my) && !description.isEmpty() && ui.font.width(description) > textW) {
			ui.tooltip(description);
		}
	}
}
