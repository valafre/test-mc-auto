package com.valafre.automod.nav;

/**
 * Résultat IMMUABLE de la planification locale. {@code heading} = cap (degrés Minecraft, 0 = +Z) ; si {@code noSafe} aucun cap
 * n'est sûr et il ne faut RIEN exécuter vers l'obstacle ({@code nudgeHeading} = petite correction sûre éventuelle, NaN sinon).
 * {@code reason} : WALL / CLEARANCE / HEIGHT / VOID quand {@code noSafe}.
 */
public record LocalResult(long id, long generation, boolean noSafe, double heading, int clearSteps, double clearDistance,
						  boolean jump, boolean sprintOk, long blockedCell, String reason, double nudgeHeading, double endFeetY,
						  double computeMs, long checks, int rollouts, long createdAtMs) {}
