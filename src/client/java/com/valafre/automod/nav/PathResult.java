package com.valafre.automod.nav;

import java.util.List;

/**
 * Résultat IMMUABLE d'un calcul de chemin global. Le thread Minecraft décide s'il l'utilise (génération, validité, comparaison
 * avec le chemin courant).
 */
public record PathResult(long id, long generation, Status status, List<NavPoint> points, boolean directLine,
						 int goalCx, int goalCy, int goalCz, int nodes, long checks, double computeMs, double smoothMs,
						 long createdAtMs) {

	public enum Status { COMPLETE, PARTIAL, FAILED }

	public boolean usable() {
		return status != Status.FAILED && !points.isEmpty();
	}
}
