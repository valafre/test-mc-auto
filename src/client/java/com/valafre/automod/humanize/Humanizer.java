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
	private double oy;
	private double oz;
	private double gx;
	private double gy;
	private double gz;
	private int rerollIn;
	private float speedFactor = 1.0f;

	// ========================================
	// VISÉE
	// ========================================

	/** Point à viser ce tick : centre + décalage qui glisse doucement vers un nouvel objectif tiré toutes les 1 à 2,5 s. À appeler UNE fois par tick. */
	public Vec3 adjustAim(Entity entity, Vec3 center) {
		ModConfig cfg = ModConfig.get();
		if (!cfg.humanize) {
			return center;
		}
		if (entity.getId() != driftEntityId) {
			driftEntityId = entity.getId();
			ox = 0;
			oy = 0;
			oz = 0;
			rerollIn = 0;
			speedFactor = 1.0f + (rng.nextFloat() * 2 - 1) * cfg.rotationSpeedVariation;
		}
		if (--rerollIn <= 0) {
			AABB box = entity.getBoundingBox();
			gx = (rng.nextDouble() * 2 - 1) * box.getXsize() * cfg.aimOffsetFraction;
			gz = (rng.nextDouble() * 2 - 1) * box.getZsize() * cfg.aimOffsetFraction;
			gy = (rng.nextDouble() * 2 - 1) * box.getYsize() * cfg.aimOffsetFraction * 0.8;
			rerollIn = 20 + rng.nextInt(30);
		}
		ox += (gx - ox) * DRIFT_LERP;
		oy += (gy - oy) * DRIFT_LERP;
		oz += (gz - oz) * DRIFT_LERP;
		return center.add(ox, oy, oz);
	}

	/** Même point que {@link #adjustAim} sans faire avancer la dérive (pour tester l'alignement). */
	public Vec3 currentAim(Entity entity, Vec3 center) {
		if (!ModConfig.get().humanize || entity.getId() != driftEntityId) {
			return center;
		}
		return center.add(ox, oy, oz);
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

	/** Ticks ajoutés au cooldown de la prochaine attaque. */
	public int attackJitter() {
		ModConfig cfg = ModConfig.get();
		return cfg.humanize ? rng.nextInt(Math.max(0, cfg.attackJitterTicks) + 1) : 0;
	}
}
