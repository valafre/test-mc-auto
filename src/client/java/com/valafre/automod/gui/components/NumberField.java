package com.valafre.automod.gui.components;

import net.minecraft.util.Mth;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** Champ numérique borné : n'accepte que des chiffres, valide à Entrée, molette = un pas. */
public final class NumberField extends TextField {

	private final DoubleSupplier get;
	private final DoubleConsumer set;
	private final double min;
	private final double max;
	private final double step;

	public NumberField(DoubleSupplier get, DoubleConsumer set, double min, double max, double step, int decimals) {
		super(() -> format(get.getAsDouble(), decimals), text -> {
			try {
				double value = Double.parseDouble(text.trim().replace(',', '.'));
				set.accept(Mth.clamp(value, min, max));
			} catch (NumberFormatException ignored) {
				// saisie invalide : la valeur actuelle est conservée
			}
		}, "0");
		this.get = get;
		this.set = set;
		this.min = min;
		this.max = max;
		this.step = step;
		this.filter = cp -> (cp >= '0' && cp <= '9') || cp == '.' || cp == ',' || cp == '-';
		this.maxLength = 12;
	}

	private static String format(double value, int decimals) {
		return decimals <= 0 ? String.valueOf(Math.round(value)) : String.format("%." + decimals + "f", value).replace(',', '.');
	}

	@Override
	public boolean mouseScrolled(Ui ui, double mx, double my, double amount) {
		if (enabled && !isEditing() && contains(mx, my)) {
			set.accept(Mth.clamp(Math.round((get.getAsDouble() + Math.signum(amount) * step) * 10000.0) / 10000.0, min, max));
			return true;
		}
		return false;
	}
}
