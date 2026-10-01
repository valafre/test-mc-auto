package com.valafre.automod.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Point de navigation : une cellule de grille ({@code cell}, simple identifiant de position) ET la hauteur PHYSIQUE réelle
 * des pieds ({@code feetY}, dessus de la collision où le joueur se tient). Ne jamais déduire la hauteur de {@code cell.getY()}.
 *
 * @param requiresJump l'arrivée sur ce point demande un saut (dénivelé > hauteur de marche) ; l'atterrissage a été vérifié libre
 */
public record NavPoint(BlockPos cell, double x, double feetY, double z, boolean requiresJump) {

	/** Centre de la cellule {@code cell} à la hauteur réelle {@code feetY}. */
	public static NavPoint of(BlockPos cell, double feetY, boolean requiresJump) {
		return new NavPoint(cell, cell.getX() + 0.5, feetY, cell.getZ() + 0.5, requiresJump);
	}

	public Vec3 vec() {
		return new Vec3(x, feetY, z);
	}
}
