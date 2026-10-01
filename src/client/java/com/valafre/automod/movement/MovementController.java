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

	private List<BlockPos> path = List.of();
	private int pathIndex;
	private BlockPos pathGoal;
	private int ticksSincePath = Integer.MAX_VALUE / 2;
	private boolean lineClear;
	private boolean pathComplete = true;
	private int ticksSinceLookahead;
	private int collisionTicks;   // ticks consécutifs collé à un obstacle : déclenche un nouveau calcul de chemin
	private int ticksSinceLineCheck = Integer.MAX_VALUE / 2;

	private int jumpCooldown;
	private int windowTicks;
	private Vec3 windowStart;
	private int stuckWindows;
	private String lastStatus = "-";

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
			lastStatus = "ARRIVED";
			return MoveStatus.ARRIVED;
		}

		Vec3 waypoint = resolveWaypoint(state, dest);
		if (waypoint == null) {
			// Aucun chemin A* (sol irrégulier, but non "standable"...) : on marche quand même droit vers la destination.
			// Saut automatique sur collision et détection de blocage prennent le relais.
			waypoint = dest;
			lastStatus = "MOVING (marche directe, pas de chemin)";
		} else {
			lastStatus = path.isEmpty() ? "MOVING (ligne droite)" : "MOVING (chemin " + (pathComplete ? "complet" : "partiel") + ", " + (path.size() - pathIndex) + " cases)";
		}

		if (updateStuck(pos)) {
			Debug.log("Movement", () -> "Bloqué, abandon de la destination " + dest);
			lastStatus = "BLOCKED (pas de progrès)";
			resetMotion();
			return MoveStatus.BLOCKED;
		}

		if (controlLook) {
			// Regard à hauteur des yeux pour garder un pitch neutre pendant la marche.
			rotation.lookAt(new Vec3(waypoint.x, state.eyePosition().y, waypoint.z), null, "PATH");
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
		backOn = false;
		leftOn = false;
		rightOn = false;
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

	/**
	 * Mouvement continu pendant le combat : strafe (gauche/droite) autour de la cible + avancer/reculer pour garder la
	 * distance entre {@code keepMin} et {@code keepMax}. Le regard est géré par l'appelant (il reste sur la cible).
	 *
	 * @param strafeDir +1 = droite, -1 = gauche, 0 = aucun mouvement latéral (avance/recul seulement)
	 * @return false si le côté choisi est impraticable (mur, vide) : l'appelant doit inverser le sens
	 */
	public boolean combatMove(PlayerState state, String owner, double distance, double keepMin, double keepMax, int strafeDir) {
		// Vecteur "droite" du joueur ; on vérifie qu'on ne va pas strafer dans un mur ou dans le vide.
		double yawRad = Math.toRadians(state.yaw());
		double rx = -Math.cos(yawRad);
		double rz = -Math.sin(yawRad);
		Vec3 pos = state.position();
		BlockPos probe = BlockPos.containing(pos.x + rx * strafeDir * 0.9, pos.y + 0.05, pos.z + rz * strafeDir * 0.9);
		boolean sideOk = Walkability.canStandAt(state.level(), probe) || Walkability.canStandAt(state.level(), probe.below());

		combatForward = distance > keepMax + (combatForward ? -0.3 : 0.0);
		combatBack = distance < keepMin + (combatBack ? 0.3 : 0.0);
		if (combatForward && combatBack) {
			combatBack = false;
		}
		if (strafeDir != 0 && sideOk) {
			input.request(owner, strafeDir > 0 ? Key.RIGHT : Key.LEFT, true);
		}
		input.request(owner, Key.FORWARD, combatForward);
		input.request(owner, Key.BACK, combatBack);

		if (jumpCooldown > 0) {
			jumpCooldown--;
		}
		// Saut seulement devant une vraie marche d'un bloc dans la direction du regard (pas quand on frotte un mur).
		double yawFwd = Math.toRadians(state.yaw());
		boolean jump = state.onGround() && jumpCooldown == 0 && state.player().horizontalCollision
			&& stepAhead(state, -Math.sin(yawFwd), Math.cos(yawFwd));
		if (jump) {
			jumpCooldown = JUMP_COOLDOWN_TICKS;
		}
		input.request(owner, Key.JUMP, jump);
		lastStatus = strafeDir == 0 ? "COMBAT (avance)" : "COMBAT (strafe " + (strafeDir > 0 ? "droite" : "gauche") + ")";
		return sideOk;
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
		// Chemin partiel : recherche plus large mais moins souvent (limite le coût CPU).
		int interval = pathComplete ? cfg.pathRecomputeIntervalTicks : cfg.pathRecomputeIntervalTicks * 2;
		int budget = pathComplete ? cfg.pathMaxNodes : cfg.pathMaxNodes * 2;
		boolean needsPath = goalMoved || ++ticksSincePath >= interval
			|| pathIndex >= path.size() || (collisionTicks > 12);
		if (needsPath) {
			PathController.PathResult result =
				paths.findPathBestEffort(state.level(), state.player().blockPosition(), goal, budget);
			path = result.path();
			pathComplete = result.complete();
			pathIndex = 0;
			pathGoal = goal;
			ticksSincePath = 0;
			collisionTicks = 0;
		}
		if (path.isEmpty()) {
			return null;
		}
		while (pathIndex < path.size() - 1 && reached(pos, path.get(pathIndex), WAYPOINT_REACHED + 0.6 * turnFactor(pos))) {
			pathIndex++;
		}
		// Lissage : on saute directement au nœud le plus lointain (8 max) atteignable en ligne droite dégagée.
		if (++ticksSinceLookahead >= 3) {
			ticksSinceLookahead = 0;
			for (int j = Math.min(path.size() - 1, pathIndex + 8); j > pathIndex; j--) {
				BlockPos n = path.get(j);
				if (paths.isClearLine(state.level(), pos, new Vec3(n.getX() + 0.5, n.getY(), n.getZ() + 0.5))) {
					pathIndex = j;
					break;
				}
			}
		}
		BlockPos node = path.get(pathIndex);
		return new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
	}

	/**
	 * Importance (0..1) du virage au prochain point du chemin : 0 = tout droit, 1 = angle droit ou plus. Sert à anticiper
	 * les virages (on commence à tourner avant le coin, trajectoire courbe) et à lever le pied avant un virage serré.
	 */
	private double turnFactor(Vec3 pos) {
		if (path.isEmpty() || pathIndex + 1 >= path.size()) {
			return 0;
		}
		BlockPos n = path.get(pathIndex);
		BlockPos m = path.get(pathIndex + 1);
		double toNode = Math.atan2(n.getZ() + 0.5 - pos.z, n.getX() + 0.5 - pos.x);
		double nextLeg = Math.atan2(m.getZ() - n.getZ(), m.getX() - n.getX());
		double diff = Math.abs(Math.atan2(Math.sin(toNode - nextLeg), Math.cos(toNode - nextLeg)));
		return Math.min(1.0, diff / (Math.PI / 2));
	}

	private static boolean reached(Vec3 pos, BlockPos node, double radius) {
		double dx = node.getX() + 0.5 - pos.x;
		double dz = node.getZ() + 0.5 - pos.z;
		return dx * dx + dz * dz < radius * radius && Math.abs(node.getY() - pos.y) < 1.2;
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

		forwardOn = hysteresis(forwardOn, fwd) && !braking;
		// Reculer : indispensable quand on regarde la cible mais que le chemin part dans l'autre sens (contournement d'un mur).
		backOn = hysteresis(backOn, -fwd) && !braking;
		leftOn = hysteresis(leftOn, -side);
		rightOn = hysteresis(rightOn, side);
		if (leftOn && rightOn) { // ne peut arriver qu'à la limite exacte des seuils
			leftOn = false;
			rightOn = false;
		}

		input.request(owner, Key.FORWARD, forwardOn);
		input.request(owner, Key.BACK, backOn);
		input.request(owner, Key.LEFT, leftOn);
		input.request(owner, Key.RIGHT, rightOn);

		// On lève le pied (pas de sprint) juste avant un virage serré du chemin, comme un joueur qui anticipe.
		boolean sharpTurn = cfg.turnSlowdown && len < 2.5 && turnFactor(pos) > 0.55;
		boolean sprint = cfg.useSprint && forwardOn && fwd > 0.9 && distToDest > cfg.slowDistance && !sharpTurn;
		input.request(owner, Key.SPRINT, sprint);

		if (jumpCooldown > 0) {
			jumpCooldown--;
		}
		collisionTicks = state.player().horizontalCollision && forwardOn ? collisionTicks + 1 : 0;
		// Saut uniquement s'il y a une marche d'un bloc franchissable DEVANT (vers le point visé) ET qu'on est réellement
		// bloqué par elle ou que le point est plus haut. Frotter un mur ou longer une paroi ne déclenche plus de saut.
		boolean higher = waypoint.y > pos.y + 0.6;
		boolean jump = state.onGround() && jumpCooldown == 0 && forwardOn
			&& (state.player().horizontalCollision || higher)
			&& stepAhead(state, waypoint.x - pos.x, waypoint.z - pos.z);
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
