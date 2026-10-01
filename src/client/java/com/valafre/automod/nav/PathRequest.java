package com.valafre.automod.nav;

/** Demande de chemin : départ réel des pieds, but (cellule + pieds), snapshot, paramètres, cellules en échec récent (surcoût). */
public record PathRequest(long id, long generation, NavWorld world, double startX, double startY, double startZ,
						  int goalCx, int goalCy, int goalCz, double goalX, double goalY, double goalZ, NavParams params,
						  long[] failedCells) {}
