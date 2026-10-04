package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.debug.NavDebug;
import com.valafre.automod.nav.NavPoint;
import com.valafre.automod.nav.PathResult;
import com.valafre.automod.input.InputController;
import com.valafre.automod.input.InputController.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Déplacement générique vers une destination, appelé CHAQUE tick par une tâche ({@link #moveTo}).
 * Gère : choix du waypoint (ligne droite ou chemin A*), orientation, touches avec hystérésis (pas de clignotement),
 * freinage anticipé, saut, détection de blocage. Ne connaît aucun module.
 */
public final class MovementController {

	public enum MoveStatus { MOVING, ARRIVED, BLOCKED }

	// Seuils d'hystérésis : une touche s'active au-dessus de ON et ne se relâche qu'en dessous de OFF.
	private static final double KEY_ON = 0.35;
	private static final double KEY_OFF = 0.15;
	private static final double WAYPOINT_REACHED = 0.7;
	private static final int BRAKE_LOOKAHEAD_TICKS = 3;
	private static final int JUMP_COOLDOWN_TICKS = 12;
	private static final double JUMP_REACH = 1.15;
	private static final int STUCK_WINDOWS_BEFORE_BLOCKED = 3;

	private final InputController input;
	private final RotationController rotation;
	private final PathController paths;

	private boolean forwardOn;
	private boolean backOn;
	private boolean leftOn;
	private boolean rightOn;

	private List<NavPoint> path = List.of();
	private int pathIndex;
	private BlockPos pathGoal;
	private Vec3 lastRequestedDestination;
	private int ticksSincePath = Integer.MAX_VALUE / 2;
	private boolean lineClear;
	private int ticksSinceRequest = Integer.MAX_VALUE / 2;
	private boolean adoptForced = true;
	private boolean lightRetry;
	private String dbgLastResult = "-";
	private boolean pathComplete = true;
	private int ticksSinceLookahead;
	private int collisionTicks;   // ticks consécutifs collé à un obstacle : déclenche un nouveau calcul de chemin
	private int ticksSinceLineCheck = Integer.MAX_VALUE / 2;
	private long waypointValidationTick = Long.MIN_VALUE / 2;
	private Vec3 lastValidatedWaypoint;
	private boolean lastWaypointValid = true;

	private int jumpCooldown;
	private int windowTicks;
	private Vec3 windowStart;
	private int stuckWindows;
	private String lastStatus = "-";

	// Diagnostic (lecture seule) : dernier état de navigation, pour le journal [Trace] / [TARGET MOVE].
	/** Instantané de la navigation : point global, point exécuté, validité, sûreté, génération du chemin... */
	public record DebugState(Vec3 globalWaypoint, Vec3 executedPoint, boolean waypointValid, boolean safe, boolean noSafe,
							 String refusal, int pathGeneration, boolean lineClearCache, boolean pathComplete,
							 int pathSize, int pathIndex, boolean reachableEstimate, long lastCallTick, String status,
							 double localHeading, double committedHeading, double candidateHeading, double candidateScore,
							 double straightScore, double leftScore, double rightScore, double bestScore, double secondScore,
							 int candidateCount, long headingHoldRemaining, int clearSteps, double clearDistance, String reason,
							 boolean jump, boolean sprintOk, String movementMode, String cameraMode, String job, double groundY, int targetId) {}

	private Vec3 dbgGlobalWaypoint;
	private Vec3 dbgExecutedPoint;
	private boolean dbgWaypointValid = true;
	private boolean dbgSafe = true;
	private boolean dbgNoSafe;
	private String dbgRefusal = "-";
	private int pathGeneration;

	public DebugState debugState() {
		boolean reachable = lineClear || (pathComplete && !path.isEmpty());
		long tick = lastCallTime == Long.MIN_VALUE / 2 ? 0 : lastCallTime;
		LocalNavigator.DebugState localState = local.debugState(tick);
		return new DebugState(dbgGlobalWaypoint, dbgExecutedPoint, dbgWaypointValid, dbgSafe, dbgNoSafe, dbgRefusal,
			pathGeneration, lineClear, pathComplete, path.size(), pathIndex, reachable, lastCallTime, lastStatus,
			localState.latestHeading(), localState.committedHeading(), localState.candidateHeading(), localState.candidateScore(),
			localState.straightScore(), localState.leftScore(), localState.rightScore(), localState.bestScore(), localState.secondScore(),
			localState.candidateCount(), localState.headingHoldRemaining(), localState.clearSteps(), localState.clearDistance(), localState.reason(),
				localState.jump(), localState.sprintOk(),
			lastMovementMode, lastCameraMode, dbgLastResult, NavDebug.ground, NavDebug.targetId);
	}

	// Déblocage rapide : fenêtre courte de progrès, escalade de manoeuvres, cases à éviter.
	/** Au-delà de cet écart (degrés) entre le regard et le point visé, on tourne la caméra plutôt que de marcher de biais. */
	private static final float MAX_STRAFE_YAW = 60.0f;
	private static final int VALIDATE_INTERVAL_TICKS = 6;
	private static final int VALIDATE_NODES = 2;
	private int validateTicks;
	private static final double FORWARD_ANGLE_ON = 70.0;
	private static final double FORWARD_ANGLE_OFF = 90.0;
	private boolean forwardOnly;
	private final LocalNavigator local = new LocalNavigator();
	private boolean steerJump;
	private boolean steerSprintOk;
	private static final double PATH_END_RADIUS = 0.6;
	private static final double LOOK_NEAR = 1.2;
	private static final double LOOK_MIN = 0.5;
	private static final int SAFE_REPLAN_COOLDOWN_TICKS = 10;
	private int safeReplanCooldown;
	private static final double SAFE_CHECK_LENGTH = 3.0;
	private static final int FAST_WINDOW_TICKS = 8;
	private static final double FAST_MIN_PROGRESS = 0.2;
	private static final int MANEUVER_TICKS = 10;
	private Vec3 fastStart;
	private int fastTicks;
	private int unstuckLevel;
	private int maneuverTicks;
	private int maneuverSide = 1;
	private int forbidLineTicks;
	private boolean intent;
	private long lastCallTime = Long.MIN_VALUE / 2;
	private String lastMovementMode = "-";
	private String lastCameraMode = "-";
	/** Dernière trajectoire locale réellement exécutée et encore récente : permet de continuer pendant un calcul worker. */
	private Vec3 lastSafeWaypoint;
	private long lastSafeWaypointTick = Long.MIN_VALUE / 2;
	private String lastSafeOwner;
	private double lastSafeHeading = Double.NaN;
	private long lastSafeHeadingTick = Long.MIN_VALUE / 2;
	private static final int PENDING_KEEP_TICKS = 10;

	public MovementController(InputController input, RotationController rotation, PathController paths) {
		this.input = input;
		this.rotation = rotation;
		this.paths = paths;
		// Même mémoire de navigation pour l'approche générale et le combat : une transition de mode ne doit pas faire
		// oublier instantanément le cap engagé puis choisir l'autre côté.
		this.combatLocal = this.local;
	}

	// ========================================
	// MOVEMENT
	// ========================================

	/**
	 * Un pas de déplacement vers {@code dest}.
	 *
	 * @param owner       propriétaire des inputs (les demandes d'un autre propriétaire sont ignorées)
	 * @param controlLook true : le contrôleur oriente le joueur vers le waypoint ; false : l'appelant gère la rotation
	 *                    (ex. suivre une cible en la regardant) et le déplacement se fait par strafe relatif au regard
	 */
	public MoveStatus moveTo(PlayerState state, String owner, Vec3 dest, double stopDistance, boolean controlLook) {
		return moveTo(state, owner, dest, stopDistance, controlLook, false);
	}

	/**
	 * @param cameraLocked true : la tâche de combat verrouille la caméra sur la cible (ENEMY). Le déplacement ne la reprend
	 *                     JAMAIS (même si le point visé est à plus de 60° du regard) : il se déplace par avant / strafe / recul
	 *                     relatifs à cette caméra. Direction du mouvement et direction de la caméra sont indépendantes.
	 */
	public MoveStatus moveTo(PlayerState state, String owner, Vec3 dest, double stopDistance, boolean controlLook,
							 boolean cameraLocked) {
		long t0 = System.nanoTime();
		try {
			return moveToImpl(state, owner, dest, stopDistance, controlLook, cameraLocked);
		} finally {
			NavService.get().addMainNanos(System.nanoTime() - t0); // profilage [NAV PERF] : coût réel sur le thread Minecraft
			NavService.get().report(state.level());
		}
	}

	private MoveStatus moveToImpl(PlayerState state, String owner, Vec3 dest, double stopDistance, boolean controlLook,
								  boolean cameraLocked) {
		ModConfig cfg = ModConfig.get();
		Vec3 pos = state.position();
		long now = state.level().getGameTime();
		if (now - lastCallTime > 2) { // reprise après une pause : l'historique de progrès n'a plus de sens
			fastStart = null;
			intent = false;
			unstuckLevel = 0;
			maneuverTicks = 0;
		}
		lastCallTime = now;
		if (safeReplanCooldown > 0) {
			safeReplanCooldown--;
		}
		double dxd = dest.x - pos.x;
		double dzd = dest.z - pos.z;
		double horizontal = Math.sqrt(dxd * dxd + dzd * dzd);

		if (horizontal <= stopDistance && Math.abs(dest.y - pos.y) < 2.5) {
			resetMotion();
			lastStatus = "ARRIVED";
			return MoveStatus.ARRIVED;
		}

		Vec3 waypoint = resolveWaypoint(state, dest);
		boolean pathless = waypoint == null;
		if (pathless) {
			// Aucun chemin A* : PAS de ligne droite aveugle. La destination ne sert que de guide à la navigation locale, qui
			// ne choisit que des trajectoires sûres ; un nouveau chemin est redemandé à chaque réévaluation.
			waypoint = dest;
			lastStatus = "MOVING (pas de chemin : navigation locale)";
		} else {
			lastStatus = path.isEmpty() ? "MOVING (ligne droite)" : "MOVING (chemin " + (pathComplete ? "complet" : "partiel") + ", " + (path.size() - pathIndex) + " cases)";
		}

		// Fin de chemin atteinte alors que la destination reste plus loin (cible inaccessible, bout de plateforme) : inutile de
		// tourner sur place autour du dernier point ; on s'arrête et c'est au module de choisir autre chose.
		if (!path.isEmpty() && pathIndex >= path.size() - 1 && waypoint != dest) {
			NavPoint last = path.get(path.size() - 1);
			double ex = last.x() - pos.x;
			double ez = last.z() - pos.z;
			if (ex * ex + ez * ez < PATH_END_RADIUS * PATH_END_RADIUS && Math.abs(last.feetY() - pos.y) < 1.2) {
				resetMotion();
				lastStatus = "FIN DE CHEMIN (destination inaccessible)";
				return MoveStatus.ARRIVED;
			}
		}

		if (updateStuck(pos)) {
			Debug.log("Movement", () -> "Bloqué, abandon de la destination " + dest);
			lastStatus = "BLOCKED (pas de progrès)";
			resetMotion();
			return MoveStatus.BLOCKED;
		}

		final Vec3 globalWaypoint = waypoint;
		validatePathAhead(state);
		// Le tronçon vers le point du chemin est-il réellement praticable ? (si non : nouveau chemin demandé ici-même)
		boolean waypointValid = checkWaypointSegment(state, waypoint);
		// Navigation locale : trajectoires candidates simulées ; son cap (sûr) remplace le point visé. Un cap REFUSÉ n'est
		// jamais exécuté.
		steerJump = false;
		steerSprintOk = false;
		LocalNavigator.Steering steer = local.steer(state, waypoint);
		String refusal = null;
		Vec3 nudge = null;
		if (steer != null) {
			if (steer.pending()) {
				refusal = "LOCAL_PENDING"; // le planificateur local n'a pas encore répondu : attente brève, pas de cap hasardeux
			} else if (steer.noSafeTrajectory()) {
				refusal = "NO_SAFE_TRAJECTORY";
				nudge = steer.nudgePoint();
			} else {
				waypoint = steer.point();
				steerJump = steer.jump();
				steerSprintOk = steer.sprintOk();
			}
		} else if (pathless && state.onGround()) {
			refusal = "NO_PATH"; // ni chemin ni cap local applicable : on ne fonce pas en ligne droite
			requestReplan(null);
		} else if (!waypointValid) {
			refusal = "WAYPOINT_INVALID";
			BlockPos cell = Walkability.cellOf(pos);
			Vec3 centre = new Vec3(cell.getX() + 0.5, pos.y, cell.getZ() + 0.5);
			double cx = centre.x - pos.x;
			double cz = centre.z - pos.z;
			if (cx * cx + cz * cz > 0.0144 && Walkability.segmentWalkable(state.level(), pos, centre, 0.0, false)) {
				nudge = centre; // petite correction sûre : se recentrer sur la case, d'où le nouveau chemin part dégagé
			}
		}
		dbgGlobalWaypoint = globalWaypoint;
		dbgWaypointValid = waypointValid;
		dbgSafe = refusal == null;
		dbgNoSafe = steer != null && steer.noSafeTrajectory();
		publishNav(state, steer, refusal, waypoint, globalWaypoint, controlLook, cameraLocked);
		dbgRefusal = refusal == null ? "-" : refusal;
		dbgExecutedPoint = refusal == null ? waypoint : nudge;
		if ("LOCAL_PENDING".equals(refusal)) {
			// Un calcul local en arrière-plan ne doit pas arrêter un joueur qui dispose encore d'une trajectoire
			// récemment validée. D'abord on réutilise le waypoint exact, puis on réutilise son cap avec un
			// contrôle local plus court et moins strict : le worker aura normalement répondu avant que cette
			// fenêtre de grâce expire. Cette voie évite les pauses artificielles de quelques ticks.
			Vec3 cached = lastSafeWaypoint;
			boolean cacheFresh = cached != null && now - lastSafeWaypointTick <= PENDING_KEEP_TICKS
				&& (lastSafeOwner == null || lastSafeOwner.equals(owner));
			if (cacheFresh && Walkability.segmentWalkable(state.level(), pos, cached, 0.0, false)) {
				waypoint = cached;
				refusal = null;
				lastStatus = "MOVING (calcul local en cours, trajectoire conservée)";
			} else if (!Double.isNaN(lastSafeHeading) && now - lastSafeHeadingTick <= PENDING_KEEP_TICKS) {
				var pendingParams = NavService.params(false);
				var pendingWorld = NavService.get().live(state.level());
				int pendingClear = com.valafre.automod.nav.LocalPlanner.clearSteps(pendingWorld, pos.x, pos.y, pos.z, lastSafeHeading, 1, pendingParams);
				Vec3 headingPoint = pointAlong(pos, lastSafeHeading, Math.max(1.5, LOOK_NEAR + 0.2));
				if (pendingClear >= 1 || Walkability.segmentWalkable(state.level(), pos, headingPoint, 0.0, false)) {
					waypoint = headingPoint;
					refusal = null;
					lastStatus = "MOVING (calcul local en cours, cap précédent conservé)";
				}
			}
		}
		dbgRefusal = refusal == null ? "-" : refusal;
		if (refusal != null) {
			return refuseMove(state, owner, dest, waypoint, steer, refusal, nudge, waypointValid, cameraLocked);
		}
		lastSafeWaypoint = waypoint;
		lastSafeWaypointTick = now;
		lastSafeOwner = owner;
		lastSafeHeading = RotationController.computeYaw(pos, waypoint);
		lastSafeHeadingTick = now;
		boolean maneuvering = updateUnstuck(state, owner, waypoint, horizontal);

		// Jamais de marche arrière / de côté prolongée : si le point à rejoindre est trop loin de l'axe du regard, on se tourne
		// vers lui (le joueur marche en avant) au lieu de reculer en gardant les yeux sur la cible.
		boolean requestedLook = controlLook;
		if (!controlLook && !cameraLocked
			&& Math.abs(RotationController.yawDelta(state.eyePosition(), waypoint, state.yaw())) > MAX_STRAFE_YAW) {
			controlLook = true; // seulement si personne ne verrouille la caméra sur la cible
		}
		if (Debug.enabled()) { // diagnostic : qui décide de la direction et de la caméra ?
			final boolean forced = !requestedLook && controlLook;
			final Vec3 wp = waypoint;
			final LocalNavigator.Steering st = steer;
			final boolean valid = waypointValid;
			final boolean locked = cameraLocked;
			com.valafre.automod.debug.CombatTrace.movementNote(String.format(java.util.Locale.ROOT,
				"moveTo dest=(%.1f,%.1f,%.1f) %s MOVEMENT_HEADING=%.0f SAFE=%s NO_SAFE_TRAJECTORY=false WAYPOINT_VALID=%s CAMERA_LOCKED=%s caméra-par-mouvement=%s%s chemin=%d/%d",
				dest.x, dest.y, dest.z, path.isEmpty() ? "ligne-droite" : "chemin",
				RotationController.computeYaw(state.eyePosition(), wp), st == null ? "n/a" : "true", valid, locked,
				controlLook, forced ? " (IMPOSÉ: waypoint à >60° du regard)" : "", pathIndex, path.size()));
		}
		if (controlLook && !cameraLocked) {
			// Un lock de combat valide reste propriétaire de la caméra ; le déplacement ne remplace jamais ENEMY par PATH.
			// Regard à hauteur des yeux pour garder un pitch neutre pendant la marche.
			Vec3 lookTarget = lookAheadPoint(state.level(), pos, waypoint);
			if (lookTarget != null) {
				rotation.lookAt(new Vec3(lookTarget.x, state.eyePosition().y, lookTarget.z), null, "PATH");
			}
		}
		forwardOnly = controlLook;
		if (!maneuvering) {
			applyKeys(state, owner, waypoint, horizontal, stopDistance, cfg);
		}
		intent = true;
		return MoveStatus.MOVING;
	}

	/** Instantané pour le HUD / la trace [NAV] (lecture seule, aucune décision). */
	private void publishNav(PlayerState state, LocalNavigator.Steering steer, String refusal, Vec3 executed, Vec3 global,
							boolean controlLook, boolean cameraLocked) {
		lastCameraMode = cameraLocked ? "ENEMY" : controlLook ? "PATH" : "ENEMY";
		lastMovementMode = refusal != null ? "REFUS" : controlLook ? "FORWARD" : "STRAFE";
		NavDebug.pathNodes = path.size() - pathIndex;
		NavDebug.replanTicks = ModConfig.get().navReplanTicks;
		NavDebug.job = dbgLastResult;
		NavDebug.camera = cameraLocked ? "ENEMY" : controlLook ? "PATH" : "ENEMY";
		NavDebug.ground = state.position().y;
		if (steer != null) {
			NavDebug.local = steer.noSafeTrajectory() ? "NO SAFE TRAJECTORY" : "SAFE";
			NavDebug.heading = steer.headingDeg();
			NavDebug.clear = steer.clearDistance();
			NavDebug.jump = steer.jump();
			NavDebug.reason = steer.reason();
			if (steer.noSafeTrajectory()) {
				NavDebug.refuse(steer.reason(), steer.blockedCell(), steer.headingDeg(), steer.clearDistance());
			}
		} else {
			NavDebug.local = refusal == null ? "SAFE" : "REFUS " + refusal;
			NavDebug.heading = RotationController.computeYaw(state.position(), executed);
			NavDebug.clear = Double.NaN;
			NavDebug.jump = false;
			NavDebug.reason = refusal == null ? "-" : refusal;
			if (refusal != null) {
				NavDebug.refuse(refusal, "—", NavDebug.heading, 0);
			}
		}
		NavDebug.movement = refusal != null ? "REFUS" : controlLook ? "FORWARD" : "STRAFE";
		NavDebug.trace(state.level().getGameTime(), global == null ? "—"
			: String.format(java.util.Locale.ROOT, "(%.1f,%.2f,%.1f)", global.x, global.y, global.z));
	}

	/** Oublie destination, chemin et hystérésis. Les touches sont relâchées par l'InputController au prochain endTick. */
	public void reset() {
		resetMotion();
		paths.invalidate();
		path = List.of();
		lastValidatedWaypoint = null;
		waypointValidationTick = Long.MIN_VALUE / 2;
		pathGoal = null;
		lastRequestedDestination = null;
		lastSafeWaypoint = null;
		lastSafeWaypointTick = Long.MIN_VALUE / 2;
		lastSafeOwner = null;
		lastSafeHeading = Double.NaN;
		lastSafeHeadingTick = Long.MIN_VALUE / 2;
		ticksSincePath = Integer.MAX_VALUE / 2;
	}

	/**
	 * Vérifie, AVANT de s'y engager, que le tronçon jusqu'au point du chemin est praticable avec la vraie boîte du joueur
	 * (coins, passages étroits, plafond). S'il ne l'est pas : un nouveau chemin est demandé tout de suite (en évitant la
	 * case fautive) et la fonction renvoie false. L'appelant ne doit alors PAS marcher vers ce point.
	 */
	private boolean checkWaypointSegment(PlayerState state, Vec3 wp) {
		Vec3 pos = state.position();
		long tick = state.level().getGameTime();
		if (lastValidatedWaypoint != null && tick - waypointValidationTick < 2
			&& lastValidatedWaypoint.distanceToSqr(wp) < 0.36) {
			return lastWaypointValid;
		}
		waypointValidationTick = tick;
		lastValidatedWaypoint = wp;
		if (Math.abs(wp.y - pos.y) > 0.6 || !state.onGround()) {
			lastWaypointValid = true; // marches et chutes : gérées par stepAhead / le chemin
			return true;
		}
		Vec3 end = wp;
		double dx = wp.x - pos.x;
		double dz = wp.z - pos.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len > SAFE_CHECK_LENGTH) {
			end = new Vec3(pos.x + dx / len * SAFE_CHECK_LENGTH, wp.y, pos.z + dz / len * SAFE_CHECK_LENGTH);
		}
		BlockPos blocked = Walkability.segmentBlockedAt(state.level(), pos, end, 0.0);
		lastWaypointValid = blocked == null;
		if (lastWaypointValid) {
			return true;
		}
		requestReplan(blocked);
		return false;
	}

	/** Demande un nouveau chemin global (sans ligne droite), en évitant {@code avoidCell} ; limité à un recalcul / 5 ticks. */
	private void requestReplan(BlockPos avoidCell) {
		if (safeReplanCooldown > 0) {
			return;
		}
		if (avoidCell != null) {
			paths.avoid(avoidCell);
		}
		ticksSincePath = Integer.MAX_VALUE / 2;
		ticksSinceLineCheck = Integer.MAX_VALUE / 2;
		forbidLineTicks = 20;
		lineClear = false;
		safeReplanCooldown = SAFE_REPLAN_COOLDOWN_TICKS;
		Debug.log("Movement", () -> "Trajectoire refusée, nouveau chemin demandé");
	}

	/**
	 * Une trajectoire est REFUSÉE (aucun cap sûr, ou point du chemin non praticable) : on ne marche pas dessus. La cible et
	 * la caméra restent comme la tâche de combat les a posées ; un nouveau chemin est demandé ; seule une petite correction
	 * de position SÛRE (déjà validée, jamais vers l'obstacle) est exécutée, sinon le joueur reste sur place.
	 */
	private MoveStatus refuseMove(PlayerState state, String owner, Vec3 dest, Vec3 waypoint,
								  LocalNavigator.Steering steer, String reason, Vec3 nudge, boolean waypointValid,
								  boolean cameraLocked) {
		if (!"LOCAL_PENDING".equals(reason)) { // simple attente du planificateur : pas de nouveau chemin pour autant
			requestReplan(steer != null ? steer.blockedCell() : null);
		}
		intent = false; // on n'essaie pas d'avancer : le détecteur de blocage rapide ne doit pas réagir à cet arrêt voulu
		forwardOnly = false;
		boolean nudging = false;
		if (nudge != null) {
			Vec3 pos = state.position();
			double yawRad = Math.toRadians(state.yaw());
			double dx = nudge.x - pos.x;
			double dz = nudge.z - pos.z;
			double len = Math.sqrt(dx * dx + dz * dz);
			if (len > 1.0E-4) {
				dx /= len;
				dz /= len;
				double fwd = dx * -Math.sin(yawRad) + dz * Math.cos(yawRad);
				double side = dx * -Math.cos(yawRad) + dz * -Math.sin(yawRad);
				input.request(owner, Key.FORWARD, fwd > KEY_ON);
				input.request(owner, Key.BACK, fwd < -KEY_ON);
				input.request(owner, Key.RIGHT, side > KEY_ON);
				input.request(owner, Key.LEFT, side < -KEY_ON);
				nudging = true;
			}
		}
		lastStatus = "REFUSÉ (" + reason + ")" + (nudging ? " : petite correction sûre" : " : arrêt");
		if (Debug.enabled()) {
			final boolean noSafe = steer != null && steer.noSafeTrajectory();
			final boolean nud = nudging;
			com.valafre.automod.debug.CombatTrace.movementNote(String.format(java.util.Locale.ROOT,
				"moveTo dest=(%.1f,%.1f,%.1f) MOVEMENT_HEADING=%s SAFE=false NO_SAFE_TRAJECTORY=%s WAYPOINT_VALID=%s CAMERA_LOCKED=%s REFUS=%s correction=%s chemin=%d/%d",
				dest.x, dest.y, dest.z, nudge == null ? "aucun" : String.format(java.util.Locale.ROOT, "%.0f", RotationController.computeYaw(state.position(), nudge)),
				noSafe, waypointValid, cameraLocked, reason, nud, pathIndex, path.size()));
		}
		return MoveStatus.MOVING;
	}

	/**
	 * Point que la caméra regarde pendant la marche : le point de chemin, mais dès qu'on en est tout près on regarde plus
	 * loin (prochains nœuds), comme un joueur qui anticipe. Sans rien plus loin, la caméra ne bouge pas : viser un point
	 * à 0,3 bloc faisait tourner l'écran à chaque pas.
	 */
	private Vec3 lookAheadPoint(Level level, Vec3 pos, Vec3 waypoint) {
		double dx = waypoint.x - pos.x;
		double dz = waypoint.z - pos.z;
		if (dx * dx + dz * dz >= LOOK_NEAR * LOOK_NEAR) {
			return waypoint;
		}
		for (int j = Math.min(path.size() - 1, pathIndex + 4); j > pathIndex; j--) {
			NavPoint n = path.get(j);
			double nx = n.x() - pos.x;
			double nz = n.z() - pos.z;
			if (nx * nx + nz * nz >= LOOK_NEAR * LOOK_NEAR) {
				return NavPoints.vec(n);
			}
		}
		// Rien de plus loin : on vise quand même le point s'il est assez loin pour que la direction soit stable.
		return dx * dx + dz * dz >= LOOK_MIN * LOOK_MIN ? waypoint : null;
	}

	/** Revalide les prochains nœuds du chemin (monde modifié, dérive du joueur) ; un tronçon devenu mauvais force un recalcul. */
	private void validatePathAhead(PlayerState state) {
		if (path.isEmpty() || ++validateTicks < VALIDATE_INTERVAL_TICKS) {
			return;
		}
		validateTicks = 0;
		Vec3 prev = state.position();
		for (int i = pathIndex; i < Math.min(path.size(), pathIndex + VALIDATE_NODES); i++) {
			NavPoint n = path.get(i);
			Vec3 c = NavPoints.vec(n);
			if (!Walkability.segmentWalkable(state.level(), prev, c, 0.0, false)) {
				paths.avoid(NavPoints.cell(n));
				ticksSincePath = Integer.MAX_VALUE / 2;
				forbidLineTicks = 10;
				Debug.log("Movement", () -> "Chemin devenu mauvais en avant, recalcul");
				return;
			}
			prev = c;
		}
	}

	/** Ligne droite franchissable (à plat, avec marge) entre le joueur et {@code dest} ? */
	public boolean hasClearLine(PlayerState state, Vec3 dest) {
		return paths.isClearLine(state.level(), state.position(), dest);
	}

	/** Le joueur est-il en train de se débloquer ? (les appelants relâchent alors le sneak, etc.) */
	public boolean isUnsticking() {
		return unstuckLevel > 0 || maneuverTicks > 0;
	}

	/**
	 * Déblocage rapide, en escalade : mesure le progrès sur 8 ticks ; sans progrès alors qu'on veut avancer on tente
	 * 1) saut + nouveau chemin sans ligne droite, 2) pas de côté + saut du côté libre, 3) recul + case à éviter,
	 * 4+) saut sprint en avant. Renvoie true tant qu'une manoeuvre pilote les touches.
	 */
	private boolean updateUnstuck(PlayerState state, String owner, Vec3 waypoint, double distToDest) {
		Vec3 pos = state.position();
		if (fastStart == null) {
			fastStart = pos;
			fastTicks = 0;
		}
		if (forbidLineTicks > 0) {
			forbidLineTicks--;
			lineClear = false;
		}
		if (++fastTicks >= FAST_WINDOW_TICKS && maneuverTicks == 0) {
			double moved = pos.distanceTo(fastStart);
			fastStart = pos;
			fastTicks = 0;
			if (moved >= 0.4) {
				unstuckLevel = 0;
				paths.clearAvoid();
			} else if (moved < FAST_MIN_PROGRESS && intent && distToDest > 1.0) {
				unstuckLevel++;
				maneuverTicks = MANEUVER_TICKS;
				ticksSincePath = Integer.MAX_VALUE / 2;
				if (unstuckLevel >= 1) {
					forbidLineTicks = 25;
					lineClear = false;
				}
				if (unstuckLevel >= 2) {
					paths.avoid(BlockPos.containing(waypoint.x, waypoint.y + 0.05, waypoint.z));
					paths.avoid(Walkability.cellOf(pos));
					maneuverSide = freeSide(state);
				}
				Debug.log("Movement", () -> "Déblocage niveau " + unstuckLevel);
			}
		}
		if (maneuverTicks <= 0) {
			return false;
		}
		maneuverTicks--;
		boolean ground = state.onGround();
		switch (Math.min(unstuckLevel, 4)) {
			case 1 -> {
				input.request(owner, Key.FORWARD, true);
				input.request(owner, Key.JUMP, ground);
			}
			case 2 -> {
				input.request(owner, maneuverSide > 0 ? Key.RIGHT : Key.LEFT, true);
				input.request(owner, Key.FORWARD, maneuverTicks < 5);
				input.request(owner, Key.JUMP, ground && maneuverTicks % 5 == 0);
			}
			case 3 -> {
				input.request(owner, Key.BACK, maneuverTicks > 4);
				input.request(owner, maneuverSide > 0 ? Key.RIGHT : Key.LEFT, maneuverTicks > 4);
				input.request(owner, Key.FORWARD, maneuverTicks <= 4);
				input.request(owner, Key.JUMP, ground && maneuverTicks <= 4);
			}
			default -> {
				maneuverSide = -maneuverSide;
				input.request(owner, Key.FORWARD, true);
				input.request(owner, Key.SPRINT, true);
				input.request(owner, maneuverSide > 0 ? Key.RIGHT : Key.LEFT, maneuverTicks % 4 < 2);
				input.request(owner, Key.JUMP, ground);
			}
		}
		lastStatus = "DÉBLOCAGE (niveau " + unstuckLevel + ")";
		if (maneuverTicks == 0) {
			fastStart = pos;
			fastTicks = 0;
		}
		return true;
	}

	/** Côté (1 = droite, -1 = gauche) où il y a de la place pour se décaler. */
	private int freeSide(PlayerState state) {
		double yawRad = Math.toRadians(state.yaw());
		double rx = -Math.cos(yawRad);
		double rz = -Math.sin(yawRad);
		Vec3 pos = state.position();
		for (int dir : new int[] {maneuverSide, -maneuverSide}) {
			if (!Double.isNaN(Walkability.feetHeightAt(state.level(), pos.x + rx * dir * 0.9, pos.z + rz * dir * 0.9, pos.y,
				Walkability.JUMP_HEIGHT, 1.1, 0.0))) {
				return dir;
			}
		}
		return -maneuverSide;
	}

	private void resetMotion() {
		local.reset();
		fastStart = null;
		unstuckLevel = 0;
		maneuverTicks = 0;
		intent = false;
		paths.clearAvoid();
		forwardOn = false;
		backOn = false;
		leftOn = false;
		rightOn = false;
		committedSide = 0;
		committedSideUntil = Long.MIN_VALUE / 2;
		combatOrbitSide = 0;
		combatOrbitUntil = Long.MIN_VALUE / 2;
		combatOrbitTargetHash = 0;
		windowStart = null;
		windowTicks = 0;
		stuckWindows = 0;
	}

	/** Dernier résultat de moveTo, pour l'affichage de diagnostic. */
	public String lastStatus() {
		return lastStatus;
	}

	// ========================================
	// COLLER À UNE FACE
	// ========================================

	/**
	 * Avance tout droit vers {@code point} (en le regardant) : sert à se coller contre la face d'un bloc. Pas de chemin,
	 * pas de freinage : le joueur pousse contre le bloc jusqu'à ce que l'appelant constate le contact.
	 */
	public void pushToward(PlayerState state, String owner, Vec3 point) {
		Vec3 eye = state.eyePosition();
		rotation.lookAt(new Vec3(point.x, eye.y, point.z), null, "PATH");
		float error = Math.abs(RotationController.yawDelta(eye, point, state.yaw()));
		input.request(owner, Key.FORWARD, error < 35.0f); // on tourne d'abord, on pousse ensuite
		lastStatus = "COLLÉ à la face";
	}

	// ========================================
	// COMBAT
	// ========================================

	private boolean combatForward;
	private boolean combatBack;

	private final LocalNavigator combatLocal;
	private boolean combatFwdKey;
	private boolean combatLeftKey;
	private boolean combatRightKey;
	private double combatLastHeading = Double.NaN;
	private long combatLastSafeTick = Long.MIN_VALUE / 2;
	private int combatOrbitSide;
	private long combatOrbitUntil = Long.MIN_VALUE / 2;
	private int combatOrbitTargetHash;
	private static final int COMBAT_ORBIT_HOLD_TICKS = 24;
	private static final double COMBAT_ORBIT_ANGLE = 55.0;

	// Engagement latéral : évite qu'un cap proche de l'axe 0 fasse alterner gauche/droite à chaque replan.
	private int committedSide; // -1 gauche, +1 droite, 0 neutre
	private long committedSideUntil = Long.MIN_VALUE / 2;

	/**
	 * Mouvement de combat NAVIGUÉ : on garde la distance de combat (entre {@code keepMin} et {@code keepMax}) en avançant
	 * vers la cible sans jamais quitter des yeux. Le cap n'est pas la ligne droite vers la cible mais celui que choisit le
	 * navigateur local (trajectoires simulées avec la vraie boîte du joueur, marge, cul-de-sac, cible gardée en vue) ;
	 * avant / gauche / droite / sprint / saut sont combinés pour contourner un obstacle EN CONTINUANT d'avancer.
	 * Le regard est géré par l'appelant (il reste sur la cible).
	 *
	 * @return false si aucune direction sûre vers la cible : l'appelant doit contourner par le chemin
	 */
	public boolean combatApproach(PlayerState state, String owner, Vec3 targetPos, Vec3 aim, double distance,
								  double keepMin, double keepMax) {
		ModConfig cfg = ModConfig.get();
		combatForward = distance > keepMax + (combatForward ? -0.3 : 0.0);
		combatBack = distance < keepMin + (combatBack ? 0.3 : 0.0);
		if (combatForward && combatBack) {
			combatBack = false;
		}
		boolean safe = true;
		boolean hold = false;
		boolean jump = false;
		boolean sprint = false;
		boolean fwdKey = false;
		boolean leftKey = false;
		boolean rightKey = false;
		if (combatForward) {
			LocalNavigator.Steering st = combatLocal.steer(state, targetPos, new LocalNavigator.Combat(aim, keepMax));
			double heading = st != null ? st.headingDeg() : RotationController.computeYaw(state.position(), targetPos);
			if (st != null) {
				safe = st.safe();
				jump = st.jump();
				hold = st.pending();
				if (hold && !Double.isNaN(combatLastHeading) && state.level().getGameTime() - combatLastSafeTick <= PENDING_KEEP_TICKS) {
					Vec3 cached = pointAlong(state.position(), combatLastHeading, 1.4);
					if (Walkability.segmentWalkable(state.level(), state.position(), cached, 0.0, false)) {
						heading = combatLastHeading;
						hold = false;
						safe = true;
					}
				}
				if (!hold && safe) {
					combatLastHeading = heading;
					combatLastSafeTick = state.level().getGameTime();
				}
			}
			double delta = Math.toRadians(net.minecraft.util.Mth.wrapDegrees((float) (heading - state.yaw())));
			double fwd = Math.cos(delta);
			double side = Math.sin(delta);
			if (safe && !hold) {
				fwdKey = hysteresis(combatFwdKey, fwd);
				rightKey = hysteresis(combatRightKey, side);
				leftKey = hysteresis(combatLeftKey, -side);
				if (leftKey && rightKey) {
					leftKey = false;
					rightKey = false;
				}
				sprint = st != null && st.sprintOk() && cfg.useSprint && fwdKey && fwd > 0.55 && !combatBack;
			}
		} else if (!combatBack) {
			// Zone de combat : un humain continue généralement à se déplacer latéralement autour d'un mob au lieu de
			// rester immobile entre deux coups. La direction d'orbite reste engagée et ne change que si elle devient dangereuse.
			long now = state.level().getGameTime();
			int targetHash = (int) Math.round((targetPos.x * 31.0 + targetPos.z * 17.0) * 10.0);
			if (combatOrbitSide == 0 || targetHash != combatOrbitTargetHash) {
				combatOrbitTargetHash = targetHash;
				combatOrbitSide = ((targetHash & 1) == 0) ? 1 : -1;
				combatOrbitUntil = now + COMBAT_ORBIT_HOLD_TICKS;
			}
			double targetYaw = RotationController.computeYaw(state.position(), targetPos);
			double orbitHeading = targetYaw + combatOrbitSide * COMBAT_ORBIT_ANGLE;
			Vec3 orbitPoint = pointAlong(state.position(), orbitHeading, 1.3);
			if (!Walkability.segmentWalkable(state.level(), state.position(), orbitPoint, 0.0, false)) {
				combatOrbitSide = -combatOrbitSide;
				orbitHeading = targetYaw + combatOrbitSide * COMBAT_ORBIT_ANGLE;
				orbitPoint = pointAlong(state.position(), orbitHeading, 1.3);
			}
			if (Walkability.segmentWalkable(state.level(), state.position(), orbitPoint, 0.0, false)) {
				double delta = Math.toRadians(net.minecraft.util.Mth.wrapDegrees((float) (orbitHeading - state.yaw())));
				double fwd = Math.cos(delta);
				double side = Math.sin(delta);
				fwdKey = hysteresis(combatFwdKey, fwd);
				rightKey = hysteresis(combatRightKey, side);
				leftKey = hysteresis(combatLeftKey, -side);
				if (leftKey && rightKey) {
					leftKey = false;
					rightKey = false;
				}
				combatLastHeading = orbitHeading;
				combatLastSafeTick = now;
				safe = true;
				lastStatus = "COMBAT (orbite" + (combatOrbitSide < 0 ? ", gauche)" : ", droite)");
			} else {
				safe = false;
			}
		} else {
			// En dessous de la distance minimale, on recule si nécessaire mais on conserve une petite composante latérale.
			long now = state.level().getGameTime();
			if (combatOrbitSide == 0) {
				combatOrbitSide = 1;
			}
			double targetYaw = RotationController.computeYaw(state.position(), targetPos);
			double orbitHeading = targetYaw + combatOrbitSide * 90.0;
			double delta = Math.toRadians(net.minecraft.util.Mth.wrapDegrees((float) (orbitHeading - state.yaw())));
			double side = Math.sin(delta);
			rightKey = hysteresis(combatRightKey, side);
			leftKey = hysteresis(combatLeftKey, -side);
			if (leftKey && rightKey) {
				leftKey = false;
				rightKey = false;
			}
			combatLastSafeTick = now;
		}

		combatFwdKey = fwdKey;
		combatLeftKey = leftKey;
		combatRightKey = rightKey;

		boolean backKey = false;
		if (combatBack) {
			double rad = Math.toRadians(state.yaw());
			Vec3 pos = state.position();
			Vec3 behind = new Vec3(pos.x + Math.sin(rad) * 0.9, pos.y, pos.z - Math.cos(rad) * 0.9);
			backKey = Walkability.segmentWalkable(state.level(), pos, behind, 0.0, true); // pas de recul dans un mur / le vide
		}
		input.request(owner, Key.FORWARD, fwdKey);
		input.request(owner, Key.BACK, backKey);
		input.request(owner, Key.LEFT, leftKey);
		input.request(owner, Key.RIGHT, rightKey);
		input.request(owner, Key.SPRINT, sprint);

		if (jumpCooldown > 0) {
			jumpCooldown--;
		}
		double yawFwd = Math.toRadians(state.yaw());
		boolean doJump = state.onGround() && jumpCooldown == 0
			&& (jump || (state.player().horizontalCollision && stepAhead(state, -Math.sin(yawFwd), Math.cos(yawFwd))));
		if (doJump) {
			jumpCooldown = JUMP_COOLDOWN_TICKS;
		}
		input.request(owner, Key.JUMP, doJump);
		if (Debug.enabled()) {
			final boolean ok = safe;
			com.valafre.automod.debug.CombatTrace.movementNote(String.format(java.util.Locale.ROOT,
				"combatApproach dist=%.1f avant=%s gauche=%s droite=%s recul=%s sprint=%s saut=%s SAFE=%s NO_SAFE_TRAJECTORY=%s WAYPOINT_VALID=n/a",
				distance, fwdKey, leftKey, rightKey, backKey, sprint, doJump, ok, !ok));
		}
		lastStatus = !safe ? "COMBAT (aucune direction sûre)" : "COMBAT (navigation" + (leftKey ? ", gauche" : rightKey ? ", droite" : "") + ")";
		return safe;
	}

	// ========================================
	// WAYPOINT / CHEMIN
	// ========================================

	/**
	 * Ligne droite si franchissable (test mis en cache), sinon chemin A* recalculé périodiquement. Le chemin est "lissé" :
	 * parmi les prochains nœuds on vise le plus lointain en ligne droite dégagée, ce qui donne peu de points de passage
	 * (trajectoire naturelle) tout en contournant murs et obstacles là où c'est nécessaire.
	 */
	private Vec3 resolveWaypoint(PlayerState state, Vec3 dest) {
		ModConfig cfg = ModConfig.get();
		Vec3 pos = state.position();
		int replan = Math.max(1, cfg.navReplanTicks);

		// 1. Résultat du worker (lecture sans attente) : validation, comparaison, transition douce.
		PathResult res = paths.poll();
		if (res != null) {
			adoptResult(state, res, dest);
		}

		// 2. La destination a fortement bougé (téléportation de la cible...) : le chemin et les résultats en vol sont obsolètes.
		//    On invalide le chemin, pas la cible ; la navigation locale guide en attendant le nouveau calcul.
		BlockPos goal = Walkability.cellOf(dest);
		boolean destinationMoved = lastRequestedDestination != null && lastRequestedDestination.distanceToSqr(dest) > 0.5625;
		if (pathGoal != null && pathGoal.distSqr(goal) > 36) {
			paths.invalidate();
			path = List.of();
			pathIndex = 0;
			lineClear = false;
			pathGoal = null;
		}
		boolean goalMoved = pathGoal == null || pathGoal.distSqr(goal) > 4 || destinationMoved;

		// 3. Contrôle léger toutes les navReplanTicks : faut-il LANCER un calcul ? (jamais un A* complet à chaque contrôle)
		ticksSinceRequest++;
		ticksSincePath++;
		int interval = pathComplete ? cfg.pathRecomputeIntervalTicks : cfg.pathRecomputeIntervalTicks * 2;
		boolean urgent = ticksSincePath > Integer.MAX_VALUE / 4 || collisionTicks > 12;
		boolean needy = !lineClear && (path.isEmpty() || pathIndex >= path.size());
		boolean periodic = ticksSincePath >= interval;
		if (ticksSinceRequest >= replan && (goalMoved || urgent || (needy || periodic) && !paths.busy())) {
			if (urgent || goalMoved || needy) {
				adoptForced = true;
			}
			paths.request(state.level(), pos, dest, lightRetry);
			pathGoal = goal;
			lastRequestedDestination = dest;
			ticksSinceRequest = 0;
			ticksSincePath = 0;
			collisionTicks = 0;
		}

		// 4. Ligne directe validée par le worker.
		if (lineClear && forbidLineTicks <= 0 && !goalMoved) {
			return dest;
		}
		if (path.isEmpty()) {
			return null; // pas encore de chemin : la navigation locale guide vers la destination (jamais de ligne droite aveugle)
		}
		while (pathIndex < path.size() - 1 && (reached(pos, path.get(pathIndex), WAYPOINT_REACHED + 0.6 * turnFactor(pos))
			|| passedPoint(pos, pathIndex))) {
			pathIndex++;
		}
		// Look-ahead : on vise le point le plus lointain (parmi les prochains) directement franchissable, pas le prochain nœud.
		// Contrôle borné (4 candidats max, <= 14 blocs, géométrie en cache).
		if (++ticksSinceLookahead >= Math.max(4, replan * 2)) {
			ticksSinceLookahead = 0;
			int far = Math.min(path.size() - 1, pathIndex + Math.max(1, Math.min(cfg.navLookAheadNodes, 6)));
			int tested = 0;
			for (int j = far; j > pathIndex && tested < 2; j--) {
				tested++;
				if (paths.isClearLine(state.level(), pos, NavPoints.vec(path.get(j)))) {
					pathIndex = j;
					break;
				}
			}
		}
		return NavPoints.vec(path.get(pathIndex));
	}

	/** Le joueur a-t-il déjà dépassé le point i (plus près du point suivant que ce point ne l'est lui-même) ? Transition douce entre chemins. */
	private boolean passedPoint(Vec3 pos, int i) {
		if (i + 1 >= path.size()) {
			return false;
		}
		NavPoint a = path.get(i);
		NavPoint b = path.get(i + 1);
		double ab = Math.hypot(b.x() - a.x(), b.z() - a.z());
		double pb = Math.hypot(b.x() - pos.x, b.z() - pos.z);
		double pa = Math.hypot(a.x() - pos.x, a.z() - pos.z);
		return pb < ab && pb < pa && Math.abs(a.feetY() - pos.y) < 1.2;
	}

	/** Longueur restante du chemin depuis la position (comparaison de qualité entre deux chemins). */
	private static double remainingLength(Vec3 pos, List<NavPoint> pts, int from) {
		double len = 0;
		double x = pos.x;
		double z = pos.z;
		for (int i = from; i < pts.size(); i++) {
			len += Math.hypot(pts.get(i).x() - x, pts.get(i).z() - z) + Math.abs(pts.get(i).feetY() - pos.y) * 0.3;
			x = pts.get(i).x();
			z = pts.get(i).z();
		}
		return len;
	}

	/**
	 * Un chemin terminé arrive du worker. Échec : on garde l'ancien chemin (et on retente en plus léger). Succès : on ne remplace
	 * un chemin encore valide que si le nouveau est nettement meilleur (ou si l'ancien est invalide / le but a changé) ; la
	 * transition est douce (les points déjà dépassés sont sautés).
	 */
	private void adoptResult(PlayerState state, PathResult res, Vec3 dest) {
		Vec3 pos = state.position();
		if (!res.usable()) {
			lightRetry = true; // prochain calcul plus léger (moins de nœuds, zone et but plus proches)
			ticksSincePath = Integer.MAX_VALUE / 2;
			dbgLastResult = "FAILED";
			return;
		}
		lightRetry = false;
		BlockPos goal = Walkability.cellOf(dest);
		BlockPos resGoal = new BlockPos(res.goalCx(), res.goalCy(), res.goalCz());
		if (!paths.lastClipped() && resGoal.distSqr(goal) > 100) {
			dbgLastResult = "OBSOLETE (but déplacé)";
			return; // la cible a bougé pendant le calcul : un autre calcul est déjà demandé
		}
		boolean complete = res.status() == PathResult.Status.COMPLETE && !paths.lastClipped();
		boolean oldValid = !path.isEmpty() && pathIndex < path.size();
		List<NavPoint> fresh = res.points();
		double oldCost = oldValid ? remainingLength(pos, path, pathIndex) : Double.MAX_VALUE;
		double freshCost = remainingLength(pos, fresh, 0);
		boolean better = !oldValid || adoptForced || freshCost < oldCost * 0.85 || (complete && !pathComplete);
		dbgLastResult = res.status() + (res.directLine() ? "/ligne" : "") + (better ? " ADOPTÉ" : " conservé l'ancien");
		if (!better) {
			return;
		}
		adoptForced = false;
		pathGeneration++;
		pathComplete = complete;
		if (res.directLine()) {
			lineClear = true;
			path = List.of();
			pathIndex = 0;
		} else {
			lineClear = false;
			path = fresh;
			pathIndex = 0;
		}
		collisionTicks = 0;
	}

	/**
	 * Importance (0..1) du virage au prochain point du chemin : 0 = tout droit, 1 = angle droit ou plus. Sert à anticiper
	 * les virages (on commence à tourner avant le coin, trajectoire courbe) et à lever le pied avant un virage serré.
	 */
	private double turnFactor(Vec3 pos) {
		if (path.isEmpty() || pathIndex + 1 >= path.size()) {
			return 0;
		}
		NavPoint n = path.get(pathIndex);
		NavPoint m = path.get(pathIndex + 1);
		double toNode = Math.atan2(n.z() - pos.z, n.x() - pos.x);
		double nextLeg = Math.atan2(m.z() - n.z(), m.x() - n.x());
		double diff = Math.abs(Math.atan2(Math.sin(toNode - nextLeg), Math.cos(toNode - nextLeg)));
		return Math.min(1.0, diff / (Math.PI / 2));
	}

	private static boolean reached(Vec3 pos, NavPoint node, double radius) {
		double dx = node.x() - pos.x;
		double dz = node.z() - pos.z;
		return dx * dx + dz * dz < radius * radius && Math.abs(node.feetY() - pos.y) < 1.2;
	}

	// ========================================
	// TOUCHES
	// ========================================

	private void applyKeys(PlayerState state, String owner, Vec3 waypoint, double distToDest,
						   double stopDistance, ModConfig cfg) {
		Vec3 pos = state.position();
		double dx = waypoint.x - pos.x;
		double dz = waypoint.z - pos.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1.0E-4) {
			return;
		}
		dx /= len;
		dz /= len;

		// Base locale du joueur (yaw 0 = +Z) : avant = (-sin, cos), droite = (-cos, -sin).
		double yawRad = Math.toRadians(state.yaw());
		double fwd = dx * -Math.sin(yawRad) + dz * Math.cos(yawRad);
		double side = dx * -Math.cos(yawRad) + dz * -Math.sin(yawRad);

		// Freinage : on anticipe l'inertie en relâchant "avant" quelques ticks avant l'arrivée.
		// Si le joueur est (presque) à l'arrêt, on ne freine pas : sinon il cale à 0,4-0,5 bloc de la case sans jamais l'atteindre.
		double speed = state.horizontalSpeed();
		boolean braking = speed > 0.04 && distToDest - speed * BRAKE_LOOKAHEAD_TICKS <= stopDistance;

		if (forwardOnly) {
			// La caméra suit le chemin : on marche comme un joueur, uniquement vers l'avant. Si le point est trop de côté on
			// tourne d'abord (sur place s'il est proche), sans jamais courir en crabe ni à reculons pendant que l'écran tourne.
			double angle = Math.toDegrees(Math.atan2(Math.abs(side), fwd));
			boolean walk = forwardOn ? angle < FORWARD_ANGLE_OFF || (angle < 100 && len > 3.0)
				: angle < FORWARD_ANGLE_ON;
			forwardOn = walk && !braking;
			backOn = false;
			leftOn = false;
			rightOn = false;
		} else {
		forwardOn = hysteresis(forwardOn, fwd) && !braking;
		// Reculer : indispensable quand on regarde la cible mais que le chemin part dans l'autre sens (contournement d'un mur).
		backOn = hysteresis(backOn, -fwd) && !braking;

		long now = state.level().getGameTime();
		int desiredSide = Math.abs(side) >= KEY_ON ? (side > 0 ? 1 : -1) : 0;
		boolean currentSideStillUseful = committedSide != 0 && side * committedSide > KEY_OFF;
		if (desiredSide != 0) {
			if (committedSide == 0) {
				committedSide = desiredSide;
				committedSideUntil = now + 6;
			} else if (desiredSide != committedSide && now >= committedSideUntil && !currentSideStillUseful) {
				committedSide = desiredSide;
				committedSideUntil = now + 6;
			}
		} else if (committedSide != 0 && now >= committedSideUntil && Math.abs(side) < KEY_OFF) {
			committedSide = 0;
		}

		leftOn = committedSide < 0 && hysteresis(leftOn, Math.max(0.0, -side));
		rightOn = committedSide > 0 && hysteresis(rightOn, Math.max(0.0, side));
		if (leftOn && rightOn) {
			leftOn = false;
			rightOn = false;
		}
		}

		input.request(owner, Key.FORWARD, forwardOn);
		input.request(owner, Key.BACK, backOn);
		input.request(owner, Key.LEFT, leftOn);
		input.request(owner, Key.RIGHT, rightOn);

		// On lève le pied (pas de sprint) juste avant un virage serré du chemin, comme un joueur qui anticipe.
		boolean sharpTurn = cfg.turnSlowdown && len < 2.5 && turnFactor(pos) > 0.55;
		boolean sprint = cfg.useSprint && forwardOn && fwd > 0.75 && distToDest > cfg.slowDistance && !sharpTurn
			&& steerSprintOk;
		input.request(owner, Key.SPRINT, sprint);

		if (jumpCooldown > 0) {
			jumpCooldown--;
		}
		collisionTicks = state.player().horizontalCollision && forwardOn ? collisionTicks + 1 : 0;
		// Saut uniquement s'il y a une marche d'un bloc franchissable DEVANT (vers le point visé) ET qu'on est réellement
		// bloqué par elle ou que le point est plus haut. Frotter un mur ou longer une paroi ne déclenche plus de saut.
		boolean higher = waypoint.y > pos.y + 0.6;
		// Préventif : une vraie marche devant se saute AVANT de la heurter (pas besoin de collision).
		boolean jump = state.onGround() && jumpCooldown == 0 && forwardOn
			&& (((state.player().horizontalCollision || higher || fwd > 0.5)
			&& stepAhead(state, waypoint.x - pos.x, waypoint.z - pos.z)) || steerJump);
		if (jump) {
			jumpCooldown = JUMP_COOLDOWN_TICKS;
		}
		input.request(owner, Key.JUMP, jump);
	}

	/**
	 * Sauter est-il utile ET possible devant (direction dx, dz) ? Calcule le dénivelé réel à gravir (demi-dalles comprises) :
	 * jusqu'à 0,6 on monte en marchant (pas de saut), entre 0,6 et 1,15 le saut passe, au-delà (ex. 1,5 depuis une demi-dalle)
	 * le saut serait inutile donc on ne saute pas.
	 */
	private static boolean stepAhead(PlayerState state, double dx, double dz) {
		double rise = Walkability.riseAhead(state.level(), state.position(), dx, dz);
		return rise > 0.6 && rise <= JUMP_REACH;
	}

	private static Vec3 pointAlong(Vec3 pos, double headingDeg, double distance) {
		double rad = Math.toRadians(headingDeg);
		return new Vec3(pos.x - Math.sin(rad) * distance, pos.y, pos.z + Math.cos(rad) * distance);
	}

	private static boolean hysteresis(boolean current, double value) {
		return current ? value > KEY_OFF : value > KEY_ON;
	}

	// ========================================
	// DÉTECTION DE BLOCAGE
	// ========================================

	/** Compare la position au début de chaque fenêtre ; 3 fenêtres sans progrès = bloqué (1ère : on force un nouveau chemin). */
	private boolean updateStuck(Vec3 pos) {
		int window = ModConfig.get().stuckWindowTicks;
		if (windowStart == null) {
			windowStart = pos;
			windowTicks = 0;
			return false;
		}
		if (++windowTicks < window) {
			return false;
		}
		boolean progressed = pos.distanceTo(windowStart) >= 0.3;
		windowStart = pos;
		windowTicks = 0;
		if (progressed) {
			stuckWindows = 0;
			return false;
		}
		stuckWindows++;
		ticksSincePath = Integer.MAX_VALUE / 2; // force un recalcul de chemin
		ticksSinceLineCheck = Integer.MAX_VALUE / 2;
		return stuckWindows >= STUCK_WINDOWS_BEFORE_BLOCKED;
	}
}
