package com.valafre.automod.targeting;

import com.valafre.automod.core.PlayerState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Informations géométriques instantanées sur une cible, calculées depuis les yeux du joueur. */
public record TargetInfo(Entity entity, Vec3 aimPoint, double distance, double horizontalDistance) {

	/**
	 * @param aimPoint centre de la hitbox (corps de la cible, pas ses pieds)
	 * @param distance distance de l'oeil au point le plus proche de la hitbox (mesure de portée d'attaque)
	 */
	public static TargetInfo of(PlayerState state, Entity entity) {
		Vec3 eye = state.eyePosition();
		AABB box = entity.getBoundingBox();
		double cx = Mth.clamp(eye.x, box.minX, box.maxX);
		double cy = Mth.clamp(eye.y, box.minY, box.maxY);
		double cz = Mth.clamp(eye.z, box.minZ, box.maxZ);
		double dx = cx - eye.x;
		double dy = cy - eye.y;
		double dz = cz - eye.z;
		Vec3 pos = state.position();
		double hx = entity.getX() - pos.x;
		double hz = entity.getZ() - pos.z;
		return new TargetInfo(entity, box.getCenter(),
			Math.sqrt(dx * dx + dy * dy + dz * dz), Math.sqrt(hx * hx + hz * hz));
	}
}
