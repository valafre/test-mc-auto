package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.input.InputController;
import com.valafre.automod.input.InputController.Key;
import net.minecraft.core.BlockPos;
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
	private static final int JUMP_COOLDOWN_TICKS = 8;
	private static final int STUCK_WINDOWS_BEFORE_BLOCKED = 3;

	private final InputController input;
	private final RotationController rotation;
	private final PathController paths;

	private boolean forwardOn;
	private boolean leftOn;
	private boolean rightOn;

	private List<BlockPos> path = List.of();
	private int pathIndex;
	private BlockPos pathGoal;
	private int ticksSincePath = Integer.MAX_VALUE / 2;
	private boolean lineClear;
	private int ticksSinceLineCheck = Integer.MAX_VALUE / 2;

	private int jumpCooldown;
	private int windowTicks;
	private Vec3 windowStart;
	private int stuckWindows;
	private int noPathTicks;

	public MovementController(InputController input, RotationController rotation, PathController paths) {
		this.input = input;
		this.rotation = rotation;
		this.paths = paths;
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
		ModConfig cfg = ModConfig.get();
		Vec3 pos = state.position();
		double dxd = dest.x - pos.x;
		double dzd = dest.z - pos.z;
		double horizontal = Math.sqrt(dxd * dxd + dzd * dzd);

		if (horizontal <= stopDistance && Math.abs(dest.y - pos.y) < 2.5) {
			resetMotion();
			return MoveStatus.ARRIVED;
		}

		Vec3 waypoint = resolveWaypoint(state, dest);
		if (waypoint == null) {
			noPathTicks++;
			if (noPathTicks > 20) {
				Debug.log("Movement", () -> "Aucun chemin vers " + dest);
				return MoveStatus.BLOCKED;
			}
			return MoveStatus.MOVING;
		}
		noPathTicks = 0;

		if (updateStuck(pos)) {
			Debug.log("Movement", () -> "Bloqué, abandon de la destination " + dest);
			resetMotion();
			return MoveStatus.BLOCKED;
		}

		if (controlLook) {
			// Regard à hauteur des yeux pour garder un pitch neutre pendant la marche.
			rotation.lookAt(new Vec3(waypoint.x, state.eyePosition().y, waypoint.z));
		}
		applyKeys(state, owner, waypoint, horizontal, stopDistance, cfg);
		return MoveStatus.MOVING;
	}

	/** Oublie destination, chemin et hystérésis. Les touches sont relâchées par l'InputController au prochain endTick. */
	public void reset() {
		resetMotion();
		path = List.of();
		pathGoal = null;
		ticksSincePath = Integer.MAX_VALUE / 2;
	}

	private void resetMotion() {
		forwardOn = false;
		leftOn = false;
		rightOn = false;
		windowStart = null;
		windowTicks = 0;
		stuckWindows = 0;
		noPathTicks = 0;
	}

	// ========================================
	// WAYPOINT / CHEMIN
	// ========================================

	/** Ligne droite si franchissable (test mis en cache), sinon prochain nœud du chemin A* (recalculé périodiquement). */
	private Vec3 resolveWaypoint(PlayerState state, Vec3 dest) {
		ModConfig cfg = ModConfig.get();
		Vec3 pos = state.position();

		if (++ticksSinceLineCheck >= 4) {
			lineClear = paths.isClearLine(state.level(), pos, dest);
			ticksSinceLineCheck = 0;
		}
		if (lineClear) {
			path = List.of();
			return dest;
		}

		BlockPos goal = BlockPos.containing(dest.x, dest.y + 0.05, dest.z);
		boolean goalMoved = pathGoal == null || pathGoal.distSqr(goal) > 4;
		if (goalMoved || ++ticksSincePath >= cfg.pathRecomputeIntervalTicks || pathIndex >= path.size()) {
			path = paths.findPath(state.level(), state.player().blockPosition(), goal, cfg.pathMaxNodes);
			pathIndex = 0;
			pathGoal = goal;
			ticksSincePath = 0;
		}
		if (path.isEmpty()) {
			return null;
		}
		while (pathIndex < path.size() - 1 && reached(pos, path.get(pathIndex))) {
			pathIndex++;
		}
		BlockPos node = path.get(pathIndex);
		return new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
	}

	private static boolean reached(Vec3 pos, BlockPos node) {
		double dx = node.getX() + 0.5 - pos.x;
		double dz = node.getZ() + 0.5 - pos.z;
		return dx * dx + dz * dz < WAYPOINT_REACHED * WAYPOINT_REACHED && Math.abs(node.getY() - pos.y) < 1.2;
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
		boolean braking = distToDest - state.horizontalSpeed() * BRAKE_LOOKAHEAD_TICKS <= stopDistance;

		forwardOn = hysteresis(forwardOn, fwd) && !braking;
		leftOn = hysteresis(leftOn, -side);
		rightOn = hysteresis(rightOn, side);
		if (leftOn && rightOn) { // ne peut arriver qu'à la limite exacte des seuils
			leftOn = false;
			rightOn = false;
		}

		input.request(owner, Key.FORWARD, forwardOn);
		input.request(owner, Key.LEFT, leftOn);
		input.request(owner, Key.RIGHT, rightOn);

		boolean sprint = cfg.useSprint && forwardOn && fwd > 0.9 && distToDest > cfg.slowDistance;
		input.request(owner, Key.SPRINT, sprint);

		if (jumpCooldown > 0) {
			jumpCooldown--;
		}
		boolean needsStep = waypoint.y > pos.y + 0.6 && Math.sqrt((waypoint.x - pos.x) * (waypoint.x - pos.x)
			+ (waypoint.z - pos.z) * (waypoint.z - pos.z)) < 1.6;
		boolean jump = state.onGround() && jumpCooldown == 0
			&& (needsStep || (state.player().horizontalCollision && forwardOn));
		if (jump) {
			jumpCooldown = JUMP_COOLDOWN_TICKS;
		}
		input.request(owner, Key.JUMP, jump);
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
