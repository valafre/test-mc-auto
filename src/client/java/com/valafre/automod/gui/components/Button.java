package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Bouton : principal (accent), secondaire (sombre) ou danger. Peut porter une icône. */
public class Button extends UiComponent {

	public enum Kind { PRIMARY, SECONDARY, DANGER }

	public String label;
	public Identifier icon;
	private final Kind kind;
	private final Runnable action;
	private float hover;
	private float press;

	public Button(String label, Kind kind, Runnable action) {
		this.label = label;
		this.kind = kind;
		this.action = action;
		this.h = Theme.CONTROL_H;
	}

	public Button icon(Identifier icon) {
		this.icon = icon;
		return this;
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		boolean over = enabled && contains(mx, my);
		hover = Anim.approach(hover, over ? 1f : 0f, 16f);
		press = Anim.approach(press, over && ui.focus() == this ? 1f : 0f, 24f);

		int base;
		int hot;
		int textColor = Theme.TEXT;
		switch (kind) {
			case PRIMARY -> { base = Theme.accent(); hot = Theme.accentHover(); }
			case DANGER -> { base = Draw.alpha(Theme.ERROR, 0.16f); hot = Draw.alpha(Theme.ERROR, 0.34f); textColor = 0xFFFCA5A5; }
			default -> { base = Theme.PANEL2; hot = 0xFF26263A; }
		}
		int fill = Draw.mix(base, hot, hover);
		if (kind == Kind.PRIMARY) {
			fill = Draw.mix(fill, Theme.accentActive(), press);
		}
		if (!enabled) {
			fill = Theme.PANEL;
			textColor = Theme.DISABLED;
		}
		Draw.round(g, x, y, w, h, Theme.RADIUS - 1, fill);
		if (kind != Kind.PRIMARY) {
			Draw.roundBorder(g, x, y, w, h, Theme.RADIUS - 1, kind == Kind.DANGER ? Draw.alpha(Theme.ERROR, 0.5f) : Theme.BORDER);
		}
		int iconSize = 12;
		int textW = label == null ? 0 : ui.font.width(label);
		int total = textW + (icon != null ? iconSize + (textW > 0 ? 5 : 0) : 0);
		int cx = x + (w - total) / 2;
		int ty = y + (h - ui.font.lineHeight) / 2 + 1;
		if (icon != null) {
			Draw.icon(g, icon, cx, y + (h - iconSize) / 2, iconSize, textColor);
			cx += iconSize + (textW > 0 ? 5 : 0);
		}
		if (label != null) {
			Draw.text(g, ui.font, label, cx, ty, textColor);
		}
		if (over && tooltip != null) {
			ui.tooltip(tooltip);
		}
	}

	@Override
	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		if (button == 0 && enabled && contains(mx, my)) {
			ui.setFocus(this);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(Ui ui, double mx, double my, int button) {
		if (button == 0 && ui.focus() == this) {
			ui.setFocus(null);
			if (enabled && contains(mx, my)) {
				action.run();
			}
			return true;
		}
		return false;
	}
}
