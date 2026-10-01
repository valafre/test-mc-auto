package com.valafre.automod.gui.hud;

import com.valafre.automod.core.Framework;
import com.valafre.automod.core.HudInfo;

import java.util.function.Function;

/**
 * Ligne du HUD : une clé (stockée dans la config pour la masquer), un nom affiché dans les réglages et une fonction qui
 * calcule la cellule à afficher (ou {@code null} pour la sauter).
 */
public record HudRow(String key, String settingsLabel, boolean debugOnly, Function<Data, Cell> cell) {

	/** Données disponibles pour calculer une ligne. {@code preview} : exemple affiché dans l'éditeur. */
	public record Data(Framework framework, HudInfo info, boolean moduleEnabled, boolean preview) {}

	public record Cell(String label, String text, int color) {}
}
