package com.valafre.automod.gui.config;

import java.util.List;

/**
 * Descripteur GUI d'un module. Pour ajouter un module à l'interface : implémenter cette interface (id du module,
 * onglets) et l'enregistrer dans {@link GuiModuleRegistry}. L'interface ne contient aucune logique de jeu.
 */
public interface GuiModule {

	/** Identifiant du {@code ModModule} correspondant. */
	String moduleId();

	/** Nom de la texture d'icône (sans extension) dans {@code textures/gui/}. */
	String icon();

	List<ConfigTab> tabs();
}
