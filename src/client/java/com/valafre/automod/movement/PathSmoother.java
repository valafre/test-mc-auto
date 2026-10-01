package com.valafre.automod.movement;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Simplifie un chemin A* : tant que le tronçon {@code point i -> point j} est réellement praticable (balayage de la boîte du
 * joueur, marge de sécurité comprise, hauteur de pieds suivant le vrai sol), les points intermédiaires sont supprimés. Les
 * points qui demandent un saut ou une chute sont toujours conservés (ils font partie du chemin, pas du « zigzag » de la grille).
 * La marge utilisée pour tester un raccourci garde le chemin lissé à distance des coins.
 */
public final class PathSmoother {

	private static final int MAX_SKIP = 14;
	private static final double MIN_MARGIN = 0.12;

	private PathSmoother() {}

	public static List<NavPoint> smooth(Level level, Vec3 start, List<NavPoint> path, double margin) {
		if (path.size() < 2) {
			return path;
		}
		double m = Math.max(margin, MIN_MARGIN);
		List<NavPoint> out = new ArrayList<>();
		Vec3 from = start;
		int i = 0; // index du premier point pas encore décidé
		while (i < path.size()) {
			int best = i;
			int limit = Math.min(path.size() - 1, i + MAX_SKIP);
			for (int j = limit; j > i; j--) {
				if (canSkipTo(level, from, path, i, j, m)) {
					best = j;
					break;
				}
			}
			out.add(path.get(best));
			from = path.get(best).vec();
			i = best + 1;
		}
		return out;
	}

	/** Les points i..j-1 sont-ils supprimables (aucun saut / chute) et le tronçon from -> j praticable à pied ? */
	private static boolean canSkipTo(Level level, Vec3 from, List<NavPoint> path, int i, int j, double margin) {
		if (j == i) {
			return true;
		}
		for (int k = i; k < j; k++) {
			if (path.get(k).requiresJump()) {
				return false;
			}
		}
		NavPoint target = path.get(j);
		return !target.requiresJump()
			&& Walkability.segmentWalkable(level, from, target.vec(), margin, true);
	}
}
