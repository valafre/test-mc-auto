package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.PlayerState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Rotation générique et progressive. Les appelants fournissent un point à regarder ({@link #lookAt}) à chaque tick ;
 * {@link #update} applique UN pas de rotation borné. Si personne ne redemande de point, la rotation automatique s'arrête.
 */
public final class RotationController {

	private Vec3 target;

	// ========================================
	// ROTATION
	// ========================================

	/** Demande de regarder {@code point} (monde). À rappeler chaque tick tant que la rotation doit durer. */
	public void lookAt(Vec3 point) {
		target = point;
	}

	public void cancel() {
		target = null;
	}

	public boolean isActive() {
		return target != null;
	}

	/** Applique un pas de rotation vers la cible demandée ce tick, puis oublie la demande. */
	public void update(PlayerState state) {
		if (target == null) {
			return;
		}
		LocalPlayer player = state.player();
		Vec3 eye = state.eyePosition();
		float yaw = player.getYRot();
		float pitch = player.getXRot();

		ModConfig cfg = ModConfig.get();
		float yawDelta = yawDelta(eye, target, yaw);
		float pitchDelta = pitchDelta(eye, target, pitch);

		float newYaw = yaw + step(yawDelta, cfg.minYawSpeed, cfg.maxYawSpeed, cfg.rotationEaseFactor);
		float newPitch = pitch + step(pitchDelta, cfg.minPitchSpeed, cfg.maxPitchSpeed, cfg.rotationEaseFactor);

		player.setYRot(newYaw);
		player.setXRot(Mth.clamp(newPitch, -90.0f, 90.0f));
		target = null;
	}

	/**
	 * Pas de rotation pour un écart donné : proportionnel à l'écart (grand écart = rapide, petit écart = lent),
	 * borné entre la vitesse min (pour converger) et max (pour rester fluide), sans jamais dépasser l'écart restant.
	 */
	private static float step(float delta, float minSpeed, float maxSpeed, float easeFactor) {
		float abs = Math.abs(delta);
		float speed = Mth.clamp(abs * easeFactor, minSpeed, maxSpeed);
		return Math.copySign(Math.min(abs, speed), delta);
	}

	// ========================================
	// CALCUL YAW / PITCH
	// ========================================

	/**
	 * Yaw Minecraft : 0° regarde vers +Z (sud), 90° vers -X (ouest). Le vecteur oeil->cible a pour composantes (dx, dz) ;
	 * atan2(-dx, dz) donne donc directement le yaw dans cette convention.
	 */
	public static float computeYaw(Vec3 eye, Vec3 to) {
		double dx = to.x - eye.x;
		double dz = to.z - eye.z;
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	/**
	 * Pitch Minecraft : positif = regarder vers le bas. Une cible plus haute (dy > 0) donne un pitch négatif,
	 * d'où le signe moins devant atan2(dy, distance horizontale).
	 */
	public static float computePitch(Vec3 eye, Vec3 to) {
		double dx = to.x - eye.x;
		double dz = to.z - eye.z;
		double dy = to.y - eye.y;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
	}

	/** Écart de yaw normalisé dans [-180, 180] (chemin le plus court). */
	public static float yawDelta(Vec3 eye, Vec3 to, float currentYaw) {
		return Mth.wrapDegrees(computeYaw(eye, to) - currentYaw);
	}

	public static float pitchDelta(Vec3 eye, Vec3 to, float currentPitch) {
		return computePitch(eye, to) - currentPitch;
	}

	/** Vrai si l'orientation actuelle pointe vers {@code point} dans les tolérances configurées. */
	public boolean isAligned(PlayerState state, Vec3 point) {
		ModConfig cfg = ModConfig.get();
		Vec3 eye = state.eyePosition();
		return Math.abs(yawDelta(eye, point, state.yaw())) <= cfg.alignToleranceYaw
			&& Math.abs(pitchDelta(eye, point, state.pitch())) <= cfg.alignTolerancePitch;
	}
}
