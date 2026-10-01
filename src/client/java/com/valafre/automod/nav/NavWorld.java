package com.valafre.automod.nav;

/**
 * Vue en lecture seule de la géométrie de navigation (aucune dépendance Minecraft). Implémentée par {@link NavGrid}
 * (snapshot immuable utilisé par le worker, ou vue « live » en cache sur le thread Minecraft).
 *
 * <p>Une instance n'est utilisée que par UN thread à la fois (elle porte des caches / compteurs sans synchronisation).
 */
public interface NavWorld {

	/** Bits de {@link #flags}. */
	int KNOWN = 1;    // la cellule est connue (chunk chargé, hauteur valide)
	int HAZARD = 2;   // cactus, magma, feu, baies, feu de camp...
	int FLUID = 4;    // liquide dans la cellule

	float[] NONE = new float[0];
	float[] FULL = {0, 0, 0, 1, 1, 1};

	/** Combinaison de KNOWN / HAZARD / FLUID. Une cellule inconnue vaut 0. */
	int flags(int x, int y, int z);

	/** Boîtes de collision de la cellule, à plat : 6 flottants par boîte (minX, minY, minZ, maxX, maxY, maxZ) locaux à la cellule. */
	float[] boxes(int x, int y, int z);

	/** Compte un test de collision (profilage). */
	default void countCheck() {}
}
