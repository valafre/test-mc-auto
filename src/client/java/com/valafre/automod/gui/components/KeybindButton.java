package com.valafre.automod.gui.components;

import com.mojang.blaze3d.platform.InputConstants;
import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;

/**
 * Bouton de raccourci : un clic puis une touche l'assigne à la vraie {@link KeyMapping} (donc visible aussi dans les
 * contrôles de Minecraft). Échap annule, Suppr/Retour arrière retire la touche.
 */
public final class KeybindButton extends UiComponent {

	private final KeyMapping mapping;
	private boolean listening;
	private float hover;

	public KeybindButton(KeyMapping mapping) {
		this.mapping = mapping;
		this.h = Theme.CONTROL_H;
	}

	private boolean conflicts(Ui ui) {
		if (mapping.isUnbound()) {
			return false;
		}
		for (KeyMapping other : ui.mc.options.keyMappings) {
			if (other != mapping && mapping.same(other)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		boolean over = enabled && contains(mx, my);
		hover = Anim.approach(hover, over || listening ? 1f : 0f, 16f);
		boolean conflict = conflicts(ui);
		int border = listening ? Theme.accent() : conflict ? Theme.WARNING : Theme.BORDER;
		Draw.round(g, x, y, w, h, Theme.RADIUS - 1, listening ? Theme.accentSoft() : Draw.mix(Theme.PANEL2, 0xFF26263A, hover));
		Draw.roundBorder(g, x, y, w, h, Theme.RADIUS - 1, border);
		String text;
		int color = Theme.TEXT;
		if (listening) {
			text = "Appuyez sur une touche…";
			color = Theme.accentHover();
		} else if (mapping.isUnbound()) {
			text = "Non assignée";
			color = Theme.DISABLED;
		} else {
			text = mapping.getTranslatedKeyMessage().getString();
			if (conflict) {
				color = Theme.WARNING;
			}
		}
		Draw.centered(g, ui.font, Draw.fit(ui.font, text, w - 8), x + w / 2, y + (h - ui.font.lineHeight) / 2 + 1, color);
		if (over && conflict) {
			ui.tooltip("Cette touche est déjà utilisée par une autre action.");
		} else if (over && tooltip != null) {
			ui.tooltip(tooltip);
		}
	}

	@Override
	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		if (listening) {
			// un clic n'importe où annule l'écoute, sauf sur le bouton lui-même (réinitialise)
			ui.setFocus(null);
			return contains(mx, my);
		}
		if (button == 0 && enabled && contains(mx, my)) {
			ui.setFocus(this);
			return true;
		}
		return false;
	}

	@Override
	public void onFocusChanged(boolean focused) {
		listening = focused;
	}

	@Override
	public boolean keyPressed(Ui ui, KeyEvent event) {
		if (!listening) {
			return false;
		}
		int key = event.key();
		if (key == 256) { // Échap : annuler
			ui.setFocus(null);
			return true;
		}
		if (key == 261 || key == 259) { // Suppr / Retour arrière : retirer
			mapping.setKey(InputConstants.UNKNOWN);
		} else {
			mapping.setKey(InputConstants.getKey(event));
		}
		KeyMapping.resetMapping();
		ui.mc.options.save();
		ui.setFocus(null);
		return true;
	}

	@Override
	public void commit() {
		listening = false;
	}
}
