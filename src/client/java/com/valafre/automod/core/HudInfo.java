package com.valafre.automod.core;

/**
 * Informations d'un module destinées à l'affichage (HUD, accueil). Fournies par le module, lues par le GUI :
 * le GUI ne connaît aucune logique de module.
 *
 * @param state    état courant lisible (ex. "Attaque")
 * @param target   nom de la cible, "" si aucune
 * @param tier     palier de la cible (ex. "T4"), "" si inconnu
 * @param hpRatio  PV de la cible entre 0 et 1, -1 si inconnus
 * @param hp       PV actuels de la cible, -1 si inconnus
 * @param maxHp    PV max de la cible, -1 si inconnus
 * @param kills    cibles tuées depuis l'activation
 * @param uptimeMs durée depuis l'activation du module
 */
public record HudInfo(String state, String target, String tier, double hpRatio, double hp, double maxHp, int kills, long uptimeMs) {

	public static final HudInfo EMPTY = new HudInfo("", "", "", -1, -1, -1, 0, 0);
}
