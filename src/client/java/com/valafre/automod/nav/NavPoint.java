package com.valafre.automod.nav;

/**
 * Point de navigation : une cellule de grille (cx, cy, cz, simple identifiant) ET la hauteur PHYSIQUE réelle des pieds
 * ({@code feetY}). Ne jamais déduire la hauteur de {@code cy}.
 *
 * @param requiresJump l'arrivée sur ce point demande un saut ; l'atterrissage a été vérifié libre
 */
public record NavPoint(int cx, int cy, int cz, double x, double feetY, double z, boolean requiresJump) {

	public static NavPoint of(int cx, int cy, int cz, double feetY, boolean requiresJump) {
		return new NavPoint(cx, cy, cz, cx + 0.5, feetY, cz + 0.5, requiresJump);
	}

	public long cellKey() {
		return NavGeometry.pack(cx, cy, cz);
	}
}
