package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.function.BooleanSupplier;

/** Bouton de la barre latérale : icône + libellé (icône seule si la barre est repliée). */
public final class NavigationButton extends UiComponent {

	public static final int HEIGHT = 32;

	private final String label;
	private final Identifier icon;
	private final BooleanSupplier selected;
	private final Runnable action;
	public boolean compact;
	private float hover;
	private float sel;

	public NavigationButton(String label, Identifier icon, BooleanSupplier selected, Runnable action) {
		this.label = label;
		this.icon = icon;
		this.selected = selected;
		this.action = action;
		this.h = HEIGHT;
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		boolean over = contains(mx, my);
		boolean isSel = selected.getAsBoolean();
		hover = Anim.approach(hover, over ? 1f : 0f, 16f);
		sel = Anim.approach(sel, isSel ? 1f : 0f, 16f);
		if (sel > 0.01f) {
			Draw.round(g, x, y, w, h, Theme.RADIUS, Draw.alpha(Theme.accent(), 0.16f * sel));
			Draw.round(g, x, y + 7, 3, h - 14, 1, Draw.alpha(Theme.accent(), sel));
		} else if (hover > 0.01f) {
			Draw.round(g, x, y, w, h, Theme.RADIUS, Draw.alpha(0xFFFFFFFF, 0.05f * hover));
		}
		int color = Draw.mix(Draw.mix(Theme.TEXT2, Theme.TEXT, hover), Theme.TEXT, sel);
		int iconColor = Draw.mix(color, Theme.accentHover(), sel);
		int ix = compact ? x + (w - 16) / 2 : x + 12;
		Draw.icon(g, icon, ix, y + (h - 16) / 2, 16, iconColor);
		if (!compact) {
			Draw.text(g, ui.font, Draw.fit(ui.font, label, w - 44), x + 36, y + (h - ui.font.lineHeight) / 2 + 1, color);
		} else if (over) {
			ui.tooltip(label);
		}
	}

	@Override
	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		if (button == 0 && contains(mx, my)) {
			action.run();
			return true;
		}
		return false;
	}
}
