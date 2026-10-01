package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Liste déroulante : la liste s'ouvre en popup au-dessus du reste de l'interface. */
public final class Dropdown extends UiComponent {

	private static final int ITEM_H = 20;

	private final List<String> options;
	private final IntSupplier get;
	private final IntConsumer set;
	private boolean open;
	private float hover;
	private float arrow;

	public Dropdown(List<String> options, IntSupplier get, IntConsumer set) {
		this.options = options;
		this.get = get;
		this.set = set;
		this.h = Theme.CONTROL_H;
	}

	private int listY(Ui ui) {
		int listH = options.size() * ITEM_H + 6;
		int screenH = ui.mc.getWindow().getGuiScaledHeight();
		return y + h + 2 + listH > screenH - 4 ? y - listH - 2 : y + h + 2;
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		boolean over = enabled && contains(mx, my);
		hover = Anim.approach(hover, over || open ? 1f : 0f, 16f);
		arrow = Anim.approach(arrow, open ? 1f : 0f, 18f);
		Draw.round(g, x, y, w, h, Theme.RADIUS - 1, Draw.mix(Theme.PANEL2, 0xFF26263A, hover));
		Draw.roundBorder(g, x, y, w, h, Theme.RADIUS - 1, open ? Theme.accent() : Theme.BORDER);
		int sel = Math.max(0, Math.min(options.size() - 1, get.getAsInt()));
		String text = Draw.fit(ui.font, options.isEmpty() ? "" : options.get(sel), w - 30);
		Draw.text(g, ui.font, text, x + 8, y + (h - ui.font.lineHeight) / 2 + 1, enabled ? Theme.TEXT : Theme.DISABLED);
		Draw.icon(g, Ui.tex("icon_chevron_down"), x + w - 18, y + (h - 10) / 2, 10, open ? Theme.accent() : Theme.TEXT2);
	}

	@Override
	public void renderPopup(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		int ly = listY(ui);
		int listH = options.size() * ITEM_H + 6;
		Draw.panel(g, x, ly, w, listH, Theme.RADIUS - 1, 0xFF181826, Theme.BORDER_STRONG);
		int sel = get.getAsInt();
		for (int i = 0; i < options.size(); i++) {
			int iy = ly + 3 + i * ITEM_H;
			boolean over = mx >= x && mx < x + w && my >= iy && my < iy + ITEM_H;
			if (over || i == sel) {
				Draw.round(g, x + 3, iy, w - 6, ITEM_H, 5, over ? Theme.accentSoft() : Draw.alpha(Theme.accent(), 0.10f));
			}
			Draw.text(g, ui.font, Draw.fit(ui.font, options.get(i), w - 22), x + 9, iy + (ITEM_H - ui.font.lineHeight) / 2 + 1,
				i == sel ? Theme.accentHover() : Theme.TEXT);
		}
	}

	@Override
	public boolean popupContains(double mx, double my) {
		return open;
	}

	@Override
	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		if (open) {
			int ly = listY(ui);
			if (button == 0 && mx >= x && mx < x + w && my >= ly + 3 && my < ly + 3 + options.size() * ITEM_H) {
				set.accept((int) ((my - ly - 3) / ITEM_H));
			}
			close(ui);
			return true; // le clic est consommé, ouvert ou non sur la liste
		}
		if (button == 0 && enabled && contains(mx, my)) {
			open = true;
			ui.openPopup(this);
			return true;
		}
		return false;
	}

	private void close(Ui ui) {
		open = false;
		ui.closePopup(this);
	}

	@Override
	public void commit() {
		open = false;
	}
}
