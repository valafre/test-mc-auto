package com.valafre.automod.nav;

/** Paramètres de navigation, COPIÉS de la configuration au moment de la demande (immuable, partageable avec le worker). */
public record NavParams(
	double maxClimb, int maxDrop, double clearanceWeight, double deadEndPenalty, int maxNodes, double timeBudgetMs,
	double safetyMargin, boolean smoothing, double sampleDistance, double horizon, double jumpHeight, double minSafeDistance,
	double progressWeight, double turnPenalty, double alignWeight, double combatVisibilityWeight, double localBudgetMs) {}
