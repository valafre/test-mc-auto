package com.valafre.automod.humanize;

import com.valafre.automod.config.ModConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Variations "naturelles" partagées par les contrôleurs : point visé qui dérive lentement dans la hitbox (au lieu de
 * fixer le centre exact), vitesse de rotation propre à chaque cible, délai de réaction et cooldown d'attaque irréguliers.
 * Tout est désactivable via {@code humanize}.
 */
public final class Humanizer {

	private static final double DRIFT_LERP = 0.08;

	private final Random rng = new Random();
	private int driftEntityId = Integer.MIN_VALUE;
	private double ox;
	private double band = 0.5;
	private double goalBand = 0.5;
	private double oz;
	private double gx;
	private double gz;
	private int rerollIn;
	private float speedFactor = 1.0f;

	// ========================================
	// VISÉE
	// ========================================

	/**
	 * Point à viser ce tick : toujours dans la bande HAUTE de la hitbox (les 45 % du haut), jamais dans le bas.
	 * Avec l'humanisation, le point glisse doucement dans cette bande et latéralement ; sinon il reste au milieu de la bande.
	 * À appeler UNE fois par tick.
	 */
	public Vec3 adjustAim(Entity entity, Vec3 center) {
		ModConfig cfg = ModConfig.get();
		if (!cfg.humanize) {
			return aim(entity, center, 0, 0, 0.5);
		}
		if (entity.getId() != driftEntityId) {
			driftEntityId = entity.getId();
			ox = 0;
			oz = 0;
			band = 0.5;
			rerollIn = 0;
			speedFactor = 1.0f + (rng.nextFloat() * 2 - 1) * cfg.rotationSpeedVariation;
		}
		if (--rerollIn <= 0) {
			AABB box = entity.getBoundingBox();
			gx = (rng.nextDouble() * 2 - 1) * box.getXsize() * cfg.aimOffsetFraction;
			gz = (rng.nextDouble() * 2 - 1) * box.getZsize() * cfg.aimOffsetFraction;
			goalBand = 0.25 + rng.nextDouble() * 0.5; // reste au coeur de la bande haute, loin des bords
			rerollIn = 20 + rng.nextInt(30);
		}
		ox += (gx - ox) * DRIFT_LERP;
		oz += (gz - oz) * DRIFT_LERP;
		band += (goalBand - band) * DRIFT_LERP;
		return aim(entity, center, ox, oz, band);
	}

	/** Même point que {@link #adjustAim} sans faire avancer la dérive. */
	public Vec3 currentAim(Entity entity, Vec3 center) {
		if (!ModConfig.get().humanize || entity.getId() != driftEntityId) {
			return aim(entity, center, 0, 0, 0.5);
		}
		return aim(entity, center, ox, oz, band);
	}

	/** @param bandPosition 0 = bas de la bande haute, 1 = sommet de la hitbox */
	private static Vec3 aim(Entity entity, Vec3 center, double dx, double dz, double bandPosition) {
		AABB box = entity.getBoundingBox();
		double min = ModConfig.get().aimBandMinFraction;
		double y = box.minY + box.getYsize() * (min + (1.0 - min) * bandPosition);
		// Anticipation : déplacement horizontal de la cible par tick, prolongé de aimLeadTicks (ignoré si téléportation).
		double vx = entity.getX() - entity.xo;
		double vz = entity.getZ() - entity.zo;
		double lead = ModConfig.get().aimLeadTicks;
		if (vx * vx + vz * vz > 2.25) {
			lead = 0;
		}
		return new Vec3(center.x + dx + vx * lead, y, center.z + dz + vz * lead);
	}

	// ========================================
	// TIMING
	// ========================================

	/** Facteur de vitesse de rotation propre à la cible courante. */
	public float rotationSpeedFactor() {
		return ModConfig.get().humanize ? speedFactor : 1.0f;
	}

	/** Ticks d'attente avant de réagir à une nouvelle cible. */
	public int nextReactionDelay() {
		ModConfig cfg = ModConfig.get();
		if (!cfg.humanize) {
			return 0;
		}
		int min = Math.max(0, cfg.reactionDelayMinTicks);
		int max = Math.max(min, cfg.reactionDelayMaxTicks);
		return min + rng.nextInt(max - min + 1);
	}
}
