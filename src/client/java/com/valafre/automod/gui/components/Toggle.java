package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Interrupteur en pilule animé. Lit et écrit directement l'état réel via des fournisseurs. */
public final class Toggle extends UiComponent {

	public static final int WIDTH = 34;
	public static final int HEIGHT = 18;

	private final BooleanSupplier get;
	private final Consumer<Boolean> set;
	private float anim = -1f;

	public Toggle(BooleanSupplier get, Consumer<Boolean> set) {
		this.get = get;
		this.set = set;
		this.w = WIDTH;
		this.h = HEIGHT;
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		boolean on = get.getAsBoolean();
		if (anim < 0f) {
			anim = on ? 1f : 0f;
		}
		anim = Anim.approach(anim, on ? 1f : 0f, 18f);
		boolean over = enabled && contains(mx, my);
		int off = over ? 0xFF3A3A52 : 0xFF2C2C40;
		int onColor = over ? Theme.accentHover() : Theme.accent();
		int track = enabled ? Draw.mix(off, onColor, anim) : Theme.PANEL2;
		Draw.round(g, x, y, w, h, h / 2, track);
		int knob = h - 6;
		int kx = x + 3 + Math.round((w - knob - 6) * anim);
		Draw.circle(g, kx + knob / 2, y + h / 2, knob / 2, enabled ? 0xFFFFFFFF : Theme.DISABLED);
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
