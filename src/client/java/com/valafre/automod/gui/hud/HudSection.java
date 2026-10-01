package com.valafre.automod.gui.hud;

import com.valafre.automod.core.Framework;

import java.util.List;

/**
 * Une section du HUD en jeu (ex. Voidgloom, Survie). Chaque module peut en fournir une et l'enregistrer dans
 * {@link HudRegistry} : le panneau les empile sans modification.
 */
public interface HudSection {

	String title();

	/** Couleur ARGB du titre. */
	int color();

	/** La section est-elle affichée en ce moment ? */
	boolean isVisible(Framework framework);

	/** Lignes à afficher ; {@code detailed} vrai en mode debug. */
	List<String> lines(Framework framework, boolean detailed);
}
