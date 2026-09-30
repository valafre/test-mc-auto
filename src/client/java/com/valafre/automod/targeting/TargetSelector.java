package com.valafre.automod.targeting;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/** Choix d'une cible parmi des candidats : garde la cible courante tant qu'elle est valide, sinon prend la plus proche. */
public final class TargetSelector {

	/**
	 * @param current  cible actuelle (nullable)
	 * @param keepRange la cible actuelle est conservée si vivante et à moins de cette distance (évite de changer à chaque tick)
	 * @return la cible retenue, ou null s'il n'y en a aucune
	 */
	public <T extends Entity> T select(Collection<T> candidates, Vec3 from, T current, double keepRange) {
		if (current != null && isValid(current) && current.position().distanceToSqr(from) <= keepRange * keepRange
			&& candidates.contains(current)) {
			return current;
		}
		T best = null;
		double bestDist = Double.MAX_VALUE;
		for (T candidate : candidates) {
			if (!isValid(candidate)) {
				continue;
			}
			double d = candidate.position().distanceToSqr(from); // comparaison sur distances au carré : pas de sqrt
			if (d < bestDist) {
				bestDist = d;
				best = candidate;
			}
		}
		return best;
	}

	public static boolean isValid(Entity entity) {
		return entity != null && entity.isAlive() && !entity.isRemoved();
	}
}
