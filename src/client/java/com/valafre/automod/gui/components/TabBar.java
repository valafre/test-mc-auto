package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Barre d'onglets verticale ou horizontale, avec icône optionnelle et indicateur animé. */
public final class TabBar extends UiComponent {

	public record Tab(String label, Identifier icon) {}

	public static final int TAB_H = 28;

	private final List<Tab> tabs = new ArrayList<>();
	private final boolean vertical;
	private final IntConsumer onSelect;
	private int selected;
	private float indicator = -1f;

	public TabBar(boolean vertical, IntConsumer onSelect) {
		this.vertical = vertical;
		this.onSelect = onSelect;
	}

	public TabBar tab(String label, Identifier icon) {
		tabs.add(new Tab(label, icon));
		return this;
	}

	public int selected() {
		return selected;
	}

	public void select(int index) {
		selected = Math.max(0, Math.min(tabs.size() - 1, index));
	}

	public int preferredHeight() {
		return vertical ? tabs.size() * (TAB_H + 2) : TAB_H;
	}

	private int[] tabRect(int i) {
		if (vertical) {
			return new int[] {x, y + i * (TAB_H + 2), w, TAB_H};
		}
		int tw = w / Math.max(1, tabs.size());
		return new int[] {x + i * tw, y, tw, TAB_H};
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		indicator = indicator < 0 ? selected : Anim.approach(indicator, selected, 16f);
		if (!vertical) {
			Draw.rect(g, x, y + TAB_H - 1, w, 1, Theme.BORDER);
		}
		// fond animé du tab sélectionné
		int ix = vertical ? x : x + Math.round(indicator * (w / (float) Math.max(1, tabs.size())));
		int iy = vertical ? y + Math.round(indicator * (TAB_H + 2)) : y;
		int iw = vertical ? w : w / Math.max(1, tabs.size());
		if (vertical) {
			Draw.round(g, ix, iy, iw, TAB_H, Theme.RADIUS - 1, Theme.accentSoft());
			Draw.round(g, ix, iy + 6, 3, TAB_H - 12, 1, Theme.accent());
		} else {
			Draw.rect(g, ix + 6, iy + TAB_H - 2, iw - 12, 2, Theme.accent());
		}
		for (int i = 0; i < tabs.size(); i++) {
			int[] r = tabRect(i);
			boolean over = r[0] <= mx && mx < r[0] + r[2] && r[1] <= my && my < r[1] + r[3];
			boolean sel = i == selected;
			if (over && !sel) {
				Draw.round(g, r[0], r[1], r[2], r[3], Theme.RADIUS - 1, 0x0FFFFFFF);
			}
			int color = sel ? Theme.TEXT : over ? Theme.TEXT : Theme.TEXT2;
			Tab t = tabs.get(i);
			int tx = r[0] + (vertical ? 12 : 8);
			if (t.icon() != null) {
				Draw.icon(g, t.icon(), tx, r[1] + (r[3] - 14) / 2, 14, sel ? Theme.accentHover() : color);
				tx += 20;
			}
			Draw.text(g, ui.font, Draw.fit(ui.font, t.label(), r[0] + r[2] - tx - 6), tx, r[1] + (r[3] - ui.font.lineHeight) / 2 + 1, color);
		}
	}

	@Override
	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		if (button != 0 || !contains(mx, my)) {
			return false;
		}
		for (int i = 0; i < tabs.size(); i++) {
			int[] r = tabRect(i);
			if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
				if (i != selected) {
					selected = i;
					onSelect.accept(i);
				}
				return true;
			}
		}
		return false;
	}
}
