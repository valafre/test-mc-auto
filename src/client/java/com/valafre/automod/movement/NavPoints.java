package com.valafre.automod.movement;

import com.valafre.automod.nav.NavPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Conversions {@link NavPoint} (moteur, sans Minecraft) vers les types Minecraft. */
public final class NavPoints {

	private NavPoints() {}

	public static Vec3 vec(NavPoint p) {
		return new Vec3(p.x(), p.feetY(), p.z());
	}

	public static BlockPos cell(NavPoint p) {
		return new BlockPos(p.cx(), p.cy(), p.cz());
	}
}
