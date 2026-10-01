package com.valafre.automod.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Indications de regard fournies par les modules aux tâches : ici, la cible SUIVANTE vers laquelle la caméra peut
 * dériver légèrement quand la cible actuelle est presque morte.
 */
public final class GazeHints {

	/** Le décalage reste sous cette fraction de la demi-largeur de la cible actuelle : le viseur ne la quitte jamais. */
	private static final double MAX_SHIFT_FRACTION = 0.6;

	private Entity glanceTarget;

	public void setGlance(Entity next) {
		glanceTarget = next;
	}

	/** @return le point visé, décalé horizontalement vers la prochaine cible, sans sortir de la hitbox de {@code current}. */
	public Vec3 applyGlance(Vec3 aim, Entity current) {
		if (glanceTarget == null || glanceTarget == current || !glanceTarget.isAlive()) {
			return aim;
		}
		Vec3 next = glanceTarget.getBoundingBox().getCenter();
		double dx = next.x - aim.x;
		double dz = next.z - aim.z;
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1.0E-3) {
			return aim;
		}
		double limit = current.getBbWidth() * 0.5 * MAX_SHIFT_FRACTION;
		double k = Math.min(1.0, limit / length);
		return new Vec3(aim.x + dx * k, aim.y, aim.z + dz * k);
	}
}
