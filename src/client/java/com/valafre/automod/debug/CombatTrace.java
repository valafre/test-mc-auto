package com.valafre.automod.debug;

import com.valafre.automod.core.Debug;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.movement.MovementController;
import com.valafre.automod.movement.RotationController;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Diagnostic (aucun effet sur le comportement) : relie, tick par tick, la cible choisie par le module, la cible utilisée
 * par la tâche de combat, la rotation réellement appliquée, qui a écrit sur la caméra / les touches, le mouvement obtenu,
 * la position de combat en cache et le point de navigation. Actif seulement en mode debug ; les lignes sont émises à
 * intervalle régulier et immédiatement dès qu'une incohérence apparaît. Une ligne {@code [TARGET MOVE]} est émise quand la
 * cible change brutalement de position (téléportation), suivie 5 ticks plus tard d'un bilan {@code [TARGET MOVE +5]}.
 */
public final class CombatTrace {

	private static final int PERIOD_TICKS = 20;
	private static final double BAD_ANGLE_DEG = 60.0;
	/** Déplacement de la cible en un tick au-delà duquel on parle de téléportation (même seuil que l'anticipation de visée). */
	private static final double TELEPORT_DELTA = 1.5;
	private static final int FOLLOW_UP_TICKS = 5;

	// Cible vue par le module (celle du HUD) et par la tâche de combat, ce tick
	private static Entity moduleTarget;
	private static Entity taskTarget;
	private static boolean stableSight;
	private static String intent = "-";
	// Position de combat (cache) vue par la tâche
	private static Vec3 combatPos;
	private static int combatPosAge;
	private static boolean combatPosReeval;
	private static boolean combatPosCalled;
	private static Vec3 combatPosTargetAtEval;
	// Écritures de ce tick
	private static final StringBuilder rotationWrites = new StringBuilder();
	private static boolean rotationOverwritten;
	private static final StringBuilder inputRejected = new StringBuilder();
	private static String movementNote = "-";

	private static long lastEmitTick = Long.MIN_VALUE / 2;
	private static int lastTargetId = Integer.MIN_VALUE;

	// Suivi des déplacements brutaux de la cible
	private static Vec3 prevTargetPos;
	private static int prevTargetPosId = Integer.MIN_VALUE;
	private static int lastPathGeneration = -1;
	private static long followUpTick = -1;
	private static Vec3 moveOld;
	private static Vec3 moveNew;
	private static int moveGeneration;

	private CombatTrace() {}

	public static void moduleTarget(Entity target) {
		moduleTarget = target;
	}

	public static void taskTarget(Entity target, boolean sight, String intentText) {
		taskTarget = target;
		stableSight = sight;
		intent = intentText;
	}

	public static void combatPosition(Vec3 pos, int ageTicks, boolean reevaluated, Vec3 targetAtEval, boolean calledThisTick) {
		combatPos = pos;
		combatPosAge = ageTicks;
		combatPosReeval = reevaluated;
		combatPosTargetAtEval = targetAtEval;
		combatPosCalled = calledThisTick;
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

	/** Fin de tick (après l'application des touches) : construit et émet les lignes si pertinent, puis remet à zéro. */
	public static void emit(Framework f) {
		try {
			if (Debug.enabled() && taskTarget != null) {
				emitLines(f);
			}
		} finally {
			taskTarget = null;
			moduleTarget = null;
			rotationWrites.setLength(0);
			inputRejected.setLength(0);
			rotationOverwritten = false;
			movementNote = "-";
			combatPos = null;
			combatPosCalled = false;
		}
	}

	private static void emitLines(Framework f) {
		PlayerState ps = f.player();
		Entity t = taskTarget;
		long tick = ps.level().getGameTime();
		Vec3 tpos = t.position(); // position ACTUELLE de la cible, lue maintenant
		MovementController.DebugState nav = f.movement().debugState();
		boolean rawVisible = f.combat().hasLineOfSight(ps, t); // sans état : n'influence pas la décision

		// --- déplacement brutal de la cible (téléportation) ---
		double step = prevTargetPos != null && prevTargetPosId == t.getId() ? tpos.distanceTo(prevTargetPos) : 0.0;
		boolean teleport = step > TELEPORT_DELTA;
		if (teleport) {
			emitTargetMove(f, ps, t, prevTargetPos, tpos, rawVisible, nav, tick);
			followUpTick = tick + FOLLOW_UP_TICKS;
			moveOld = prevTargetPos;
			moveNew = tpos;
			moveGeneration = nav.pathGeneration();
		}
		if (followUpTick >= 0 && tick >= followUpTick) {
			emitFollowUp(f, ps, t, rawVisible, nav, tick);
			followUpTick = -1;
		}
		prevTargetPos = tpos;
		prevTargetPosId = t.getId();
		lastPathGeneration = nav.pathGeneration();

		// --- ligne régulière ---
		Vec3 eye = ps.eyePosition();
		Vec3 center = t.getBoundingBox().getCenter();
		double dist = ps.position().distanceTo(tpos);
		float yawToTarget = RotationController.computeYaw(eye, center);
		float yaw = ps.yaw();
		float err = net.minecraft.util.Mth.wrapDegrees(yawToTarget - yaw);

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
		boolean notable = teleport || mismatch || changed || rotationOverwritten || Math.abs(gazeVsTarget) > BAD_ANGLE_DEG
			|| Math.abs(err) > BAD_ANGLE_DEG || inputRejected.length() > 0 || !nav.safe() || !nav.waypointValid();
		long period = notable ? 2 : PERIOD_TICKS;
		if (tick - lastEmitTick < period) {
			return;
		}
		lastEmitTick = tick;
		lastTargetId = t.getId();

		Vec3 motion = ps.player().getDeltaMovement();
		double moveYaw = Math.hypot(motion.x, motion.z) > 0.01 ? Math.toDegrees(Math.atan2(-motion.x, motion.z)) : Double.NaN;
		String type = String.valueOf(EntityType.getKey(t.getType()));
		String head = String.format(Locale.ROOT,
			"TARGET_ID=#%d TARGET_POSITION=%s PLAYER_POSITION=%s TARGET_VISIBLE=%s(stable=%s) TARGET_REACHABLE=%s(estimé: ligne droite=%s, chemin complet=%s)"
				+ " TARGET_MOVEMENT_DELTA=%.2f POSITION_COMBAT=%s WAYPOINT=%s WAYPOINT_VALID=%s SAFE=%s LOOK_SOURCE=%s CAMERA_HEADING=%.1f | ",
			t.getId(), fmt(tpos), fmt(ps.position()), rawVisible, stableSight, nav.reachableEstimate(), nav.lineClearCache(),
			nav.pathComplete() && nav.pathSize() > 0, step, combatPosText(tpos), nav.executedPoint() == null ? "—" : fmt(nav.executedPoint()),
			nav.waypointValid(), nav.safe(), appliedPoint == null ? "NONE" : applied, yaw)
			+ String.format(Locale.ROOT, "NAV_AGE=%d ticks | ", tick - nav.lastCallTick());
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

	/** Une ligne au moment exact où la cible change brutalement de position. */
	private static void emitTargetMove(Framework f, PlayerState ps, Entity t, Vec3 old, Vec3 now, boolean rawVisible,
									   MovementController.DebugState nav, long tick) {
		Vec3 delta = now.subtract(old);
		boolean invalidated = nav.pathGeneration() != lastPathGeneration;
		Vec3 wp = nav.executedPoint();
		Vec3 gwp = nav.globalWaypoint();
		String line = String.format(Locale.ROOT,
			"[TARGET MOVE] id=#%d old=%s new=%s delta=(%.1f, %.1f, %.1f) |%.1f| visible=%s(stable=%s) player=%s"
				+ " | positionCombat=%s | waypoint global=%s exécuté=%s (distance à l'ancienne cible=%s, à la nouvelle=%s)"
				+ " waypointValid=%s safe=%s lineClear(cache)=%s | waypoint invalidated=%s (chemin recalculé ce tick=%s, génération %d)",
			t.getId(), fmt(old), fmt(now), delta.x, delta.y, delta.z, delta.length(), rawVisible, stableSight, fmt(ps.position()),
			combatPosText(now), gwp == null ? "—" : fmt(gwp), wp == null ? "—" : fmt(wp),
			gwp == null ? "—" : String.format(Locale.ROOT, "%.1f", hdist(gwp, old)),
			gwp == null ? "—" : String.format(Locale.ROOT, "%.1f", hdist(gwp, now)),
			nav.waypointValid(), nav.safe(), nav.lineClearCache(), invalidated ? "OUI" : "NON", invalidated, nav.pathGeneration());
		Debug.log("Trace", () -> line);
	}

	/** Bilan quelques ticks après le saut : a-t-on recalculé depuis la position actuelle, ou suit-on encore l'ancien point ? */
	private static void emitFollowUp(Framework f, PlayerState ps, Entity t, boolean rawVisible,
									 MovementController.DebugState nav, long tick) {
		Vec3 gwp = nav.globalWaypoint();
		Vec3 now = t.position();
		boolean regenerated = nav.pathGeneration() != moveGeneration;
		String line = String.format(Locale.ROOT,
			"[TARGET MOVE +%d] id=#%d cible=%s visible=%s(stable=%s) | chemin recalculé depuis le saut=%s (+%d)"
				+ " | positionCombat=%s | waypoint global=%s (à l'ancienne cible=%s, à la nouvelle=%s) waypointValid=%s safe=%s refus=%s",
			FOLLOW_UP_TICKS, t.getId(), fmt(now), rawVisible, stableSight, regenerated, nav.pathGeneration() - moveGeneration,
			combatPosText(now), gwp == null ? "—" : fmt(gwp),
			gwp == null || moveOld == null ? "—" : String.format(Locale.ROOT, "%.1f", hdist(gwp, moveOld)),
			gwp == null ? "—" : String.format(Locale.ROOT, "%.1f", hdist(gwp, now)),
			nav.waypointValid(), nav.safe(), nav.refusal());
		Debug.log("Trace", () -> line);
	}

	private static String combatPosText(Vec3 targetNow) {
		if (combatPos == null) {
			return combatPosCalled ? "aucune position viable (cible directe)" : "non utilisée ce tick";
		}
		String s = String.format(Locale.ROOT, "%s (âge %d ticks, ré-évaluée ce tick=%s", fmt(combatPos), combatPosAge,
			combatPosCalled && combatPosReeval);
		if (combatPosTargetAtEval != null) {
			s += String.format(Locale.ROOT, ", cible lors de l'évaluation à %.1f de sa position actuelle",
				hdist(combatPosTargetAtEval, targetNow));
		}
		return s + ")";
	}

	private static double hdist(Vec3 a, Vec3 b) {
		return Math.hypot(a.x - b.x, a.z - b.z);
	}

	private static String fmt(Vec3 v) {
		return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
	}

	private static String idOf(Entity e) {
		return e == null ? "-" : "#" + e.getId();
	}
}
