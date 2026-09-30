package com.valafre.automod.humanize;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.PlayerState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Point visé sur une cible, calculé de façon DÉTERMINISTE à partir de la situation (aucun hasard) :
 * <ul>
 *   <li>hauteur : toujours dans la bande haute de la hitbox ; plus la cible est proche, plus on vise bas dans la bande
 *       (un tangage trop raide pour peu de gain), lissé pour ne jamais sauter ;</li>
 *   <li>anticipation : position dans {@code aimLeadTicks} ticks d'après la vitesse LISSÉE de la cible (les à-coups de
 *       paquets serveur et les téléportations sont ignorés).</li>
 * </ul>
 */
public final class Humanizer {

	private static final double VELOCITY_SMOOTHING = 0.4;
	private static final double TELEPORT_JUMP = 1.5;
	private static final double BAND_SMOOTHING = 0.15;

	private int trackedId = Integer.MIN_VALUE;
	private double lastX;
	private double lastZ;
	private double velX;
	private double velZ;
	private double band = 0.5;

	/** Point à viser ce tick. À appeler UNE fois par tick et par cible (met à jour le suivi de vitesse et de hauteur). */
	public Vec3 aim(Entity entity, PlayerState state) {
		ModConfig cfg = ModConfig.get();
		AABB box = entity.getBoundingBox();
		Vec3 center = box.getCenter();

		if (entity.getId() != trackedId) {
			trackedId = entity.getId();
			lastX = entity.getX();
			lastZ = entity.getZ();
			velX = 0;
			velZ = 0;
			band = targetBand(state, entity);
		} else {
			double dx = entity.getX() - lastX;
			double dz = entity.getZ() - lastZ;
			lastX = entity.getX();
			lastZ = entity.getZ();
			if (dx * dx + dz * dz > TELEPORT_JUMP * TELEPORT_JUMP) { // téléportation : pas d'anticipation
				velX = 0;
				velZ = 0;
			} else {
				velX += (dx - velX) * VELOCITY_SMOOTHING;
				velZ += (dz - velZ) * VELOCITY_SMOOTHING;
			}
			band += (targetBand(state, entity) - band) * BAND_SMOOTHING;
		}

		// Bande de visée : de aimBandLowFraction (depuis le bas) à aimBandHighFraction (soit 30 % de marge depuis le haut).
		double low = cfg.aimBandLowFraction;
		double high = Math.max(low, cfg.aimBandHighFraction);
		double y = box.minY + box.getYsize() * (low + (high - low) * band);
		return new Vec3(center.x + velX * cfg.aimLeadTicks, y, center.z + velZ * cfg.aimLeadTicks);
	}

	/** Position dans la bande haute (0 = bas de la bande, 1 = sommet) : plus bas quand la cible est proche. */
	private static double targetBand(PlayerState state, Entity entity) {
		double distance = state.position().distanceTo(entity.position());
		return Mth.clamp(0.75 - 0.12 * (distance - 1.0), 0.3, 0.75);
	}
}
