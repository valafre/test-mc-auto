package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Pastille de couleur (pulsation douce si {@code pulse}) suivie d'un texte d'état. */
public final class StatusIndicator extends UiComponent {

	private final Supplier<String> text;
	private final IntSupplier color;
	private final boolean pulse;

	public StatusIndicator(Supplier<String> text, IntSupplier color, boolean pulse) {
		this.text = text;
		this.color = color;
		this.pulse = pulse;
		this.h = 14;
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		int c = color.getAsInt();
		int cy = y + h / 2;
		if (pulse) {
			float p = (float) (0.5 + 0.5 * Math.sin(Anim.time() * 3.0));
			Draw.circle(g, x + 4, cy, 4, Draw.alpha(c, 0.12f + 0.2f * p));
		}
		Draw.circle(g, x + 4, cy, 2, c);
		Draw.text(g, ui.font, Draw.fit(ui.font, text.get(), w - 14), x + 12, y + (h - ui.font.lineHeight) / 2 + 1, c);
	}
}
