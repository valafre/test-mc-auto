package com.valafre.automod.nav;

/**
 * Résultat IMMUABLE de la planification locale. Les champs de score servent aussi au diagnostic : ils permettent de savoir
 * si le plan hésite entre les côtés, sans modifier la décision elle-même.
 */
public record LocalResult(long id, long generation, boolean noSafe, double heading, int clearSteps, double clearDistance,
                          boolean jump, boolean sprintOk, long blockedCell, String reason, double nudgeHeading, double endFeetY,
                          double computeMs, long checks, int rollouts, long createdAtMs,
                          double bestScore, double secondScore, double straightScore, double leftScore, double rightScore,
                          int candidateCount, int testedTiers) {

    /** Compatibilité avec les anciens appels : les métriques de score restent inconnues. */
    public LocalResult(long id, long generation, boolean noSafe, double heading, int clearSteps, double clearDistance,
                       boolean jump, boolean sprintOk, long blockedCell, String reason, double nudgeHeading, double endFeetY,
                       double computeMs, long checks, int rollouts, long createdAtMs) {
        this(id, generation, noSafe, heading, clearSteps, clearDistance, jump, sprintOk, blockedCell, reason,
            nudgeHeading, endFeetY, computeMs, checks, rollouts, createdAtMs,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0);
    }
}
