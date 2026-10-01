package com.valafre.automod.gui.screens;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.gui.components.Button;
import com.valafre.automod.gui.components.ScrollContainer;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.config.ConfigPageBuilder;
import com.valafre.automod.gui.config.Keybinds;
import com.valafre.automod.gui.hud.HudRegistry;
import com.valafre.automod.gui.hud.HudRow;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Paramètres généraux : interface, HUD configurable, raccourcis. */
public final class SettingsPage extends Page {

	private final ScrollContainer scroll;

	public SettingsPage(Runnable openHudEditor) {
		ConfigPageBuilder b = new ConfigPageBuilder();
		b.card("Interface", "Apparence du menu")
			.dropdown("Couleur d'accent", "Couleur des éléments actifs", List.of(Theme.ACCENT_NAMES),
				() -> c().guiAccentPreset, i -> { c().guiAccentPreset = i; Theme.sync(); })
			.slider("Vitesse des animations", "0 = aucune animation", () -> c().guiAnimSpeed, v -> c().guiAnimSpeed = (float) v, 0, 2, 0.1, 1, "×");

		b.card("HUD en jeu", "Affichage de l'état pendant que vous jouez")
			.toggle("Afficher le HUD", null, () -> c().hudEnabled, v -> c().hudEnabled = v)
			.dropdown("Style", "Liste d'informations ou anneau de PV", List.of("Liste", "Anneau"), () -> c().hudStyle, i -> c().hudStyle = i)
			.slider("Position horizontale", null, () -> c().hudPosX, v -> c().hudPosX = (float) v, 0, 1, 0.01, 2, "")
			.slider("Position verticale", null, () -> c().hudPosY, v -> c().hudPosY = (float) v, 0, 1, 0.01, 2, "")
			.slider("Taille", null, () -> c().hudScale, v -> c().hudScale = (float) v, 0.5, 2, 0.05, 2, "×")
			.slider("Opacité", null, () -> c().hudOpacity, v -> c().hudOpacity = (float) v, 0.2, 1, 0.05, 2, "")
			.toggle("Panneau des raccourcis", "Affiche les touches sous le HUD", () -> c().hudShowShortcuts, v -> c().hudShowShortcuts = v)
			.button("Éditer la position", "Glissez le HUD directement à l'écran", "Ouvrir l'éditeur", Button.Kind.PRIMARY, openHudEditor);

		b.card("Lignes du HUD", "Cochez les lignes à afficher");
		for (HudRow row : HudRegistry.all()) {
			b.checkbox(row.settingsLabel(), null, () -> !hiddenKeys().contains(row.key()), v -> setHidden(row.key(), !v));
		}

		b.card("Raccourcis", "Cliquez sur une touche puis appuyez sur la nouvelle (Suppr = retirer)");
		for (Keybinds.Entry e : Keybinds.all()) {
			b.keybind(e.label(), null, e.mapping());
		}

		b.card("Avancé")
			.toggle("Mode debug", "Lignes de diagnostic supplémentaires dans le HUD", () -> c().debugMode, v -> c().debugMode = v);
		scroll = add(new ScrollContainer(b.build()));
	}

	private static ModConfig c() {
		return ModConfig.get();
	}

	private static Set<String> hiddenKeys() {
		Set<String> set = new LinkedHashSet<>();
		Arrays.stream(c().hudHiddenRows.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(set::add);
		return set;
	}

	private static void setHidden(String key, boolean hidden) {
		Set<String> set = hiddenKeys();
		if (hidden) {
			set.add(key);
		} else {
			set.remove(key);
		}
		c().hudHiddenRows = String.join(",", set);
	}

	@Override
	protected void arrange() {
		scroll.setBounds(x, y + HEADER_H + 8, w, h - HEADER_H - 8);
	}

	@Override
	protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		drawHeader(ui, g, "Paramètres", "Interface, HUD et raccourcis", x);
	}
}
