package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/** Barre de progression 0..1 à remplissage animé. */
public final class ProgressBar extends UiComponent {

	private final DoubleSupplier value;
	private final IntSupplier color;
	private float shown = -1f;

	public ProgressBar(DoubleSupplier value, IntSupplier color) {
		this.value = value;
		this.color = color;
		this.h = 6;
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		float v = (float) Math.max(0, Math.min(1, value.getAsDouble()));
		shown = shown < 0 ? v : Anim.approach(shown, v, 10f);
		Draw.round(g, x, y, w, h, h / 2, 0xFF2C2C40);
		int fw = Math.round(w * shown);
		if (fw > 0) {
			Draw.round(g, x, y, Math.max(h, fw), h, h / 2, color == null ? Theme.accent() : color.getAsInt());
		}
	}
}
