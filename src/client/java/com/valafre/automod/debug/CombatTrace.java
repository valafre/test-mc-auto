package com.valafre.automod.debug;

import com.valafre.automod.core.Debug;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.movement.RotationController;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Diagnostic (aucun effet sur le comportement) : relie, tick par tick, la cible choisie par le module, la cible utilisée
 * par la tâche de combat, la rotation réellement appliquée, qui a écrit sur la caméra / les touches, et le mouvement
 * obtenu. Actif seulement en mode debug ; les lignes sont émises à intervalle régulier et immédiatement dès qu'une
 * incohérence apparaît (caméra qui ne regarde pas la cible, écriture concurrente, cibles différentes).
 */
public final class CombatTrace {

	private static final int PERIOD_TICKS = 20;
	private static final double BAD_ANGLE_DEG = 60.0;

	// Cible vue par le module (celle du HUD) et par la tâche de combat, ce tick
	private static Entity moduleTarget;
	private static Entity taskTarget;
	private static boolean stableSight;
	private static String intent = "-";
	// Écritures de ce tick
	private static final StringBuilder rotationWrites = new StringBuilder();
	private static boolean rotationOverwritten;
	private static final StringBuilder inputRejected = new StringBuilder();
	private static String movementNote = "-";

	private static long lastEmitTick = Long.MIN_VALUE / 2;
	private static int lastTargetId = Integer.MIN_VALUE;

	private CombatTrace() {}

	public static void moduleTarget(Entity target) {
		moduleTarget = target;
	}

	public static void taskTarget(Entity target, boolean sight, String intentText) {
		taskTarget = target;
		stableSight = sight;
		intent = intentText;
	}

	public static void movementNote(String note) {
		movementNote = note;
	}

	/** Appelé par le RotationController à chaque demande de regard (seulement en debug). */
	public static void rotationWrite(String source, String caller, Vec3 point, boolean replaced) {
		if (replaced) {
			rotationOverwritten = true;
			rotationWrites.append(" -> REMPLACÉ PAR ");
		} else if (rotationWrites.length() > 0) {
			rotationWrites.append(" ; ");
		}
		rotationWrites.append(source).append('(').append(caller).append(')');
	}

	/** Une demande de touche venant d'un propriétaire qui n'a pas la main a été ignorée. */
	public static void inputRejected(String requester, String key) {
		if (inputRejected.length() < 120) {
			inputRejected.append(requester).append(':').append(key).append(' ');
		}
	}

	/** Fin de tick (après l'application des touches) : construit et émet la ligne si pertinent, puis remet à zéro. */
	public static void emit(Framework f) {
		try {
			if (Debug.enabled() && taskTarget != null) {
				emitLine(f);
			}
		} finally {
			taskTarget = null;
			moduleTarget = null;
			rotationWrites.setLength(0);
			inputRejected.setLength(0);
			rotationOverwritten = false;
			movementNote = "-";
		}
	}

	private static void emitLine(Framework f) {
		PlayerState ps = f.player();
		Entity t = taskTarget;
		long tick = ps.level().getGameTime();
		Vec3 eye = ps.eyePosition();
		Vec3 center = t.getBoundingBox().getCenter(); // position ACTUELLE de la cible, lue maintenant
		double dist = ps.position().distanceTo(t.position());
		float yawToTarget = RotationController.computeYaw(eye, center);
		float yaw = ps.yaw();
		float err = net.minecraft.util.Mth.wrapDegrees(yawToTarget - yaw);
		boolean rawVisible = f.combat().hasLineOfSight(ps, t); // sans état : n'influence pas la décision

		RotationController rot = f.rotation();
		String applied = rot.debugAppliedSource();
		Vec3 appliedPoint = rot.debugAppliedPoint();
		String gaze = "aucune";
		double gazeVsTarget = 0;
		if (appliedPoint != null) {
			float gazeYaw = RotationController.computeYaw(eye, appliedPoint);
			gazeVsTarget = net.minecraft.util.Mth.wrapDegrees(gazeYaw - yawToTarget);
			gaze = String.format(Locale.ROOT, "%s visant yaw %.0f (écart à la cible %.0f)", applied, gazeYaw, gazeVsTarget);
		}
		boolean mismatch = moduleTarget != null && moduleTarget != taskTarget;
		boolean changed = t.getId() != lastTargetId;
		boolean notable = mismatch || changed || rotationOverwritten || Math.abs(gazeVsTarget) > BAD_ANGLE_DEG
			|| Math.abs(err) > BAD_ANGLE_DEG || inputRejected.length() > 0;
		long period = notable ? 2 : PERIOD_TICKS;
		if (tick - lastEmitTick < period) {
			return;
		}
		lastEmitTick = tick;
		lastTargetId = t.getId();

		Vec3 motion = ps.player().getDeltaMovement();
		double moveYaw = Math.hypot(motion.x, motion.z) > 0.01 ? Math.toDegrees(Math.atan2(-motion.x, motion.z)) : Double.NaN;
		String type = String.valueOf(EntityType.getKey(t.getType()));
		String head = String.format(Locale.ROOT, "TARGET=#%d LOOK=%s CAMERA_HEADING=%.1f | ", t.getId(),
			appliedPoint == null ? "NONE" : applied, yaw);
		String line = head + String.format(Locale.ROOT,
			"TARGET %s #%d (module=%s task=%s%s) DISTANCE %.1f VISIBLE %s (stable=%s) | ANGLE→cible yaw %.1f | ROTATION PLAYER %.1f | ERREUR %.1f"
				+ " | MOVEMENT VECTOR x=%.3f z=%.3f (cap %s, écart à la cible %s) | CAMÉRA: %s | écritures: %s%s | INTENTION: %s | MOUVEMENT: %s | TOUCHES: %s%s",
			type, t.getId(), idOf(moduleTarget), idOf(taskTarget), mismatch ? " ≠ INCOHÉRENT" : " OK", dist,
			rawVisible ? "YES" : "NO", stableSight ? "yes" : "no", yawToTarget, yaw, err,
			motion.x, motion.z, Double.isNaN(moveYaw) ? "—" : String.format(Locale.ROOT, "%.0f", moveYaw),
			Double.isNaN(moveYaw) ? "—" : String.format(Locale.ROOT, "%.0f", net.minecraft.util.Mth.wrapDegrees((float) (moveYaw - yawToTarget))),
			gaze, rotationWrites.length() == 0 ? "aucune" : rotationWrites.toString(), rotationOverwritten ? " (ÉCRASEMENT)" : "",
			intent, movementNote, f.input().describeApplied(),
			inputRejected.length() > 0 ? " | REFUSÉ: " + inputRejected : "");
		Debug.log("Trace", () -> line);
	}

	private static String idOf(Entity e) {
		return e == null ? "-" : "#" + e.getId();
	}
}
