package com.valafre.automod.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** Vue légère et réutilisée du joueur local, rafraîchie une fois par tick par le {@link TickManager}. */
public final class PlayerState {

	private LocalPlayer player;
	private ClientLevel level;

	/** @return true si joueur et monde sont disponibles. */
	public boolean update(Minecraft mc) {
		player = mc.player;
		level = mc.level;
		return player != null && level != null;
	}

	public LocalPlayer player() {
		return player;
	}

	public ClientLevel level() {
		return level;
	}

	public Vec3 position() {
		return player.position();
	}

	public Vec3 eyePosition() {
		return player.getEyePosition();
	}

	public float yaw() {
		return player.getYRot();
	}

	public float pitch() {
		return player.getXRot();
	}

	public boolean onGround() {
		return player.onGround();
	}

	/** Vitesse horizontale actuelle (blocs/tick), utilisée pour anticiper le freinage. */
	public double horizontalSpeed() {
		return player.getDeltaMovement().horizontalDistance();
	}
}
