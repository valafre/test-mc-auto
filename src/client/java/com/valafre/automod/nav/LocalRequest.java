package com.valafre.automod.nav;

/** Demande de planification locale : position réelle, guide, contexte de combat optionnel, cap précédent, snapshot. */
public record LocalRequest(long id, long generation, NavWorld world, double x, double y, double z, double guideX, double guideY,
						   double guideZ, double speed, double prevHeading, boolean combat, double aimX, double aimY, double aimZ,
						   double range, NavParams params) {}
