package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** Curseur avec valeur affichée à droite ; pas et décimales configurables. */
public final class Slider extends UiComponent {

	private static final int VALUE_W = 44;

	private final DoubleSupplier get;
	private final DoubleConsumer set;
	private final double min;
	private final double max;
	private final double step;
	private final int decimals;
	private final String suffix;
	private boolean dragging;
	private float hover;

	public Slider(DoubleSupplier get, DoubleConsumer set, double min, double max, double step, int decimals, String suffix) {
		this.get = get;
		this.set = set;
		this.min = min;
		this.max = max;
		this.step = step;
		this.decimals = decimals;
		this.suffix = suffix == null ? "" : suffix;
		this.h = Theme.CONTROL_H;
	}

	private int trackX() {
		return x;
	}

	private int trackW() {
		return w - VALUE_W - 6;
	}

	private void applyMouse(double mx) {
		double t = Mth.clamp((mx - trackX()) / Math.max(1, trackW()), 0, 1);
		double v = min + t * (max - min);
		if (step > 0) {
			v = Math.round((v - min) / step) * step + min;
		}
		set.accept(Mth.clamp(Math.round(v * 10000.0) / 10000.0, min, max));
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		double v = get.getAsDouble();
		float t = (float) Mth.clamp((v - min) / (max - min), 0, 1);
		boolean over = enabled && (contains(mx, my) || dragging);
		hover = Anim.approach(hover, over ? 1f : 0f, 16f);
		int ty = y + h / 2 - 2;
		int tw = trackW();
		Draw.round(g, trackX(), ty, tw, 4, 2, 0xFF2C2C40);
		int fw = Math.round(tw * t);
		if (fw > 0) {
			Draw.round(g, trackX(), ty, Math.max(4, fw), 4, 2, enabled ? Theme.accent() : Theme.DISABLED);
		}
		int kr = 5 + Math.round(hover);
		Draw.circle(g, trackX() + fw, y + h / 2, kr, enabled ? Draw.mix(0xFFE9E3FF, 0xFFFFFFFF, hover) : Theme.DISABLED);
		Draw.right(g, ui.font, format(v) + suffix, x + w, y + (h - ui.font.lineHeight) / 2 + 1, Theme.TEXT);
	}

	private String format(double v) {
		return decimals <= 0 ? String.valueOf(Math.round(v)) : String.format("%." + decimals + "f", v);
	}

	@Override
	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		if (button == 0 && enabled && contains(mx, my) && mx <= trackX() + trackW() + 6) {
			dragging = true;
			applyMouse(mx);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(Ui ui, double mx, double my, int button, double dx, double dy) {
		if (dragging) {
			applyMouse(mx);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(Ui ui, double mx, double my, int button) {
		boolean was = dragging;
		dragging = false;
		return was;
	}

	@Override
	public boolean mouseScrolled(Ui ui, double mx, double my, double amount) {
		if (enabled && contains(mx, my)) {
			double s = step > 0 ? step : (max - min) / 50;
			set.accept(Mth.clamp(Math.round((get.getAsDouble() + Math.signum(amount) * s) * 10000.0) / 10000.0, min, max));
			return true;
		}
		return false;
	}
}
