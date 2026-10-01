package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Case à cocher avec libellé optionnel. */
public final class Checkbox extends UiComponent {

	private final String label;
	private final BooleanSupplier get;
	private final Consumer<Boolean> set;
	private float anim = -1f;

	public Checkbox(String label, BooleanSupplier get, Consumer<Boolean> set) {
		this.label = label;
		this.get = get;
		this.set = set;
		this.h = 16;
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		boolean on = get.getAsBoolean();
		if (anim < 0f) {
			anim = on ? 1f : 0f;
		}
		anim = Anim.approach(anim, on ? 1f : 0f, 20f);
		boolean over = enabled && contains(mx, my);
		int size = 14;
		int by = y + (h - size) / 2;
		Draw.round(g, x, by, size, size, 4, Draw.mix(over ? 0xFF2A2A3E : Theme.PANEL2, Theme.accent(), anim));
		Draw.roundBorder(g, x, by, size, size, 4, Draw.mix(Theme.BORDER_STRONG, Theme.accent(), anim));
		if (anim > 0.4f) { // coche dessinée en pixels
			int c = Draw.alpha(0xFFFFFFFF, anim);
			g.fill(x + 3, by + 7, x + 5, by + 9, c);
			g.fill(x + 4, by + 8, x + 6, by + 10, c);
			g.fill(x + 5, by + 7, x + 7, by + 9, c);
			g.fill(x + 6, by + 6, x + 8, by + 8, c);
			g.fill(x + 7, by + 5, x + 9, by + 7, c);
			g.fill(x + 8, by + 4, x + 11, by + 6, c);
		}
		if (label != null) {
			Draw.text(g, ui.font, label, x + size + 6, y + (h - ui.font.lineHeight) / 2 + 1, enabled ? Theme.TEXT : Theme.DISABLED);
		}
	}

	@Override
	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		if (button == 0 && enabled && contains(mx, my)) {
			set.accept(!get.getAsBoolean());
			return true;
		}
		return false;
	}
}
