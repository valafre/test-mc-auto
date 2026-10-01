package com.valafre.automod.gui.config;

import com.valafre.automod.gui.components.Button;
import com.valafre.automod.gui.components.Checkbox;
import com.valafre.automod.gui.components.Column;
import com.valafre.automod.gui.components.Dropdown;
import com.valafre.automod.gui.components.KeybindButton;
import com.valafre.automod.gui.components.NumberField;
import com.valafre.automod.gui.components.Slider;
import com.valafre.automod.gui.components.TextField;
import com.valafre.automod.gui.components.Toggle;
import com.valafre.automod.gui.components.UiComponent;
import com.valafre.automod.gui.widgets.Card;
import com.valafre.automod.gui.widgets.SettingRow;
import net.minecraft.client.KeyMapping;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Construit une page de réglages : des cartes contenant des lignes liées à de vraies valeurs (fournisseur + modificateur).
 * C'est l'unique API dont un module a besoin pour décrire son interface.
 */
public final class ConfigPageBuilder {

	private final Column root = new Column(12);
	private Card current;

	public Column build() {
		return root;
	}

	public ConfigPageBuilder card(String title) {
		return card(title, null);
	}

	public ConfigPageBuilder card(String title, String subtitle) {
		current = new Card(title, subtitle);
		root.add(current);
		return this;
	}

	private ConfigPageBuilder row(String label, String desc, UiComponent control, int width) {
		if (current == null) {
			card(null);
		}
		current.add(new SettingRow(label, desc, control, width));
		return this;
	}

	public ConfigPageBuilder toggle(String label, String desc, BooleanSupplier get, Consumer<Boolean> set) {
		return row(label, desc, new Toggle(get, set), Toggle.WIDTH);
	}

	public ConfigPageBuilder checkbox(String label, String desc, BooleanSupplier get, Consumer<Boolean> set) {
		return row(label, desc, new Checkbox(null, get, set), 16);
	}

	public ConfigPageBuilder slider(String label, String desc, DoubleSupplier get, DoubleConsumer set,
									double min, double max, double step, int decimals, String suffix) {
		return row(label, desc, new Slider(get, set, min, max, step, decimals, suffix), 170);
	}

	public ConfigPageBuilder number(String label, String desc, DoubleSupplier get, DoubleConsumer set,
									double min, double max, double step, int decimals) {
		return row(label, desc, new NumberField(get, set, min, max, step, decimals), 76);
	}

	public ConfigPageBuilder dropdown(String label, String desc, List<String> options, IntSupplier get, IntConsumer set) {
		return row(label, desc, new Dropdown(options, get, set), 140);
	}

	public ConfigPageBuilder text(String label, String desc, Supplier<String> get, Consumer<String> set, String placeholder) {
		return row(label, desc, new TextField(get, set, placeholder), 160);
	}

	public ConfigPageBuilder keybind(String label, String desc, KeyMapping mapping) {
		return row(label, desc, new KeybindButton(mapping), 130);
	}

	public ConfigPageBuilder button(String label, String desc, String buttonText, Button.Kind kind, Runnable action) {
		return row(label, desc, new Button(buttonText, kind, action), 110);
	}

	/** Ligne d'information sans contrôle. */
	public ConfigPageBuilder info(String label, String desc) {
		return row(label, desc, null, 0);
	}
}
