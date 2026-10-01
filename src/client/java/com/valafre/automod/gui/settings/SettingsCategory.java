package com.valafre.automod.gui.settings;

import com.valafre.automod.core.Framework;

/**
 * Une catégorie du menu de réglage (onglet de la colonne de gauche). Chaque module ou système en fournit une et
 * l'enregistre dans {@link SettingsRegistry} : le menu les affiche toutes, sans modification de l'écran lui-même.
 */
public interface SettingsCategory {

	/** Libellé affiché dans la colonne de gauche. */
	String title();

	/** Remplit la page (maximum 7 lignes de 2 réglages) avec les réglages de la catégorie. */
	void build(SettingsPage page, Framework framework);
}
