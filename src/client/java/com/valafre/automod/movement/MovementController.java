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

	// Déblocage rapide : fenêtre courte de progrès, escalade de manoeuvres, cases à éviter.
	/** Au-delà de cet écart (degrés) entre le regard et le point visé, on tourne la caméra plutôt que de marcher de biais. */
	private static final float MAX_STRAFE_YAW = 60.0f;
	private static final int VALIDATE_INTERVAL_TICKS = 4;
	private static final int VALIDATE_NODES = 6;
	private int validateTicks;
	private static final double FORWARD_ANGLE_ON = 70.0;
	private static final double FORWARD_ANGLE_OFF = 90.0;
	private boolean forwardOnly;
	private final LocalNavigator local = new LocalNavigator();
	private boolean steerJump;
	private boolean steerSprintOk;
	private boolean steerUnsafe;
	private int unsafeTicks;
	private static final int UNSAFE_TICKS_BEFORE_REPLAN = 3;
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
		long now = state.level().getGameTime();
		if (now - lastCallTime > 2) { // reprise après une pause : l'historique de progrès n'a plus de sens
			fastStart = null;
			intent = false;
			unstuckLevel = 0;
			maneuverTicks = 0;
		}
		lastCallTime = now;
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

		// Fin de chemin atteinte alors que la destination reste plus loin (cible inaccessible, bout de plateforme) : inutile de
		// tourner sur place autour du dernier point ; on s'arrête et c'est au module de choisir autre chose.
		if (!path.isEmpty() && pathIndex >= path.size() - 1 && waypoint != dest) {
			BlockPos last = path.get(path.size() - 1);
			double ex = last.getX() + 0.5 - pos.x;
			double ez = last.getZ() + 0.5 - pos.z;
			if (ex * ex + ez * ez < PATH_END_RADIUS * PATH_END_RADIUS && Math.abs(last.getY() - pos.y) < 1.2) {
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

		validatePathAhead(state);
		waypoint = safeWaypoint(state, waypoint);
		// Navigation locale : trajectoires candidates simulées, la meilleure remplace le point visé (cap stable à 3,5 blocs).
		steerJump = false;
		steerSprintOk = false;
		steerUnsafe = false;
		LocalNavigator.Steering steer = local.steer(state, waypoint);
		if (steer != null) {
			waypoint = steer.point();
			steerJump = steer.jump();
			steerSprintOk = steer.sprintOk();
			steerUnsafe = !steer.safe();
			if (steerUnsafe) {
				if (++unsafeTicks >= UNSAFE_TICKS_BEFORE_REPLAN && steer.blockedCell() != null) {
					paths.avoid(steer.blockedCell());
					ticksSincePath = Integer.MAX_VALUE / 2;
					ticksSinceLineCheck = Integer.MAX_VALUE / 2;
					forbidLineTicks = 20;
					lineClear = false;
					unsafeTicks = 0;
					Debug.log("Movement", () -> "Aucune trajectoire sûre vers le point, nouveau chemin");
				}
			} else {
				unsafeTicks = 0;
			}
		}
		boolean maneuvering = updateUnstuck(state, owner, waypoint, horizontal);

		// Jamais de marche arrière / de côté prolongée : si le point à rejoindre est trop loin de l'axe du regard, on se tourne
		// vers lui (le joueur marche en avant) au lieu de reculer en gardant les yeux sur la cible.
		boolean requestedLook = controlLook;
		if (!controlLook && Math.abs(RotationController.yawDelta(state.eyePosition(), waypoint, state.yaw())) > MAX_STRAFE_YAW) {
			controlLook = true;
		}
		if (Debug.enabled()) { // diagnostic : qui décide de la direction et de la caméra ?
			final boolean forced = !requestedLook && controlLook;
			final boolean asked = requestedLook;
			final Vec3 wp = waypoint;
			final LocalNavigator.Steering st = steer;
			com.valafre.automod.debug.CombatTrace.movementNote(String.format(java.util.Locale.ROOT,
				"moveTo dest=(%.1f,%.1f,%.1f) %s waypoint=(%.1f,%.1f) yaw→waypoint=%.0f steerCap=%s sûr=%s caméra-par-mouvement=%s%s chemin=%d/%d",
				dest.x, dest.y, dest.z, path.isEmpty() ? "ligne-droite" : "chemin", wp.x, wp.z,
				RotationController.computeYaw(state.eyePosition(), wp),
				st == null ? "—" : String.format(java.util.Locale.ROOT, "%.0f", st.headingDeg()),
				st == null ? "—" : String.valueOf(st.safe()), controlLook, forced ? " (IMPOSÉ: waypoint à >60° du regard)" : asked ? " (demandé)" : "",
				pathIndex, path.size()));
		}
		if (controlLook) {
			// Regard à hauteur des yeux pour garder un pitch neutre pendant la marche.
			Vec3 lookTarget = lookAheadPoint(pos, waypoint);
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

	/** Oublie destination, chemin et hystérésis. Les touches sont relâchées par l'InputController au prochain endTick. */
	public void reset() {
		resetMotion();
		path = List.of();
		pathGoal = null;
		ticksSincePath = Integer.MAX_VALUE / 2;
	}

	/**
	 * Vérifie, AVANT de s'y engager, que le tronçon jusqu'au point visé est praticable avec la vraie boîte du joueur
	 * (coins, passages étroits, plafond). Sinon : nouveau chemin immédiat sans ligne droite, et en attendant on se
	 * recentre sur la case où l'on est, d'où le chemin calculé part sans frôler d'obstacle.
	 */
	private Vec3 safeWaypoint(PlayerState state, Vec3 wp) {
		if (safeReplanCooldown > 0) {
			safeReplanCooldown--;
		}
		Vec3 pos = state.position();
		if (Math.abs(wp.y - pos.y) > 0.6 || !state.onGround()) {
			return wp; // marches et chutes : gérées par stepAhead / le chemin
		}
		Vec3 end = wp;
		double dx = wp.x - pos.x;
		double dz = wp.z - pos.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len > SAFE_CHECK_LENGTH) { // inutile de valider plus loin que ce qu'on parcourra avant le prochain contrôle
			end = new Vec3(pos.x + dx / len * SAFE_CHECK_LENGTH, wp.y, pos.z + dz / len * SAFE_CHECK_LENGTH);
		}
		if (Walkability.segmentWalkable(state.level(), pos, end, 0.0, false)) {
			return wp;
		}
		// Tronçon non praticable : on recalcule le chemin (sans ligne droite) mais on garde le point visé, sans recentrage :
		// viser le centre de la case faisait osciller le joueur autour de ce point (marche et caméra saccadées).
		if (safeReplanCooldown <= 0) {
			ticksSincePath = Integer.MAX_VALUE / 2;
			ticksSinceLineCheck = Integer.MAX_VALUE / 2;
			forbidLineTicks = 15;
			lineClear = false;
			safeReplanCooldown = SAFE_REPLAN_COOLDOWN_TICKS;
			Debug.log("Movement", () -> "Tronçon non praticable, nouveau chemin");
		}
		return wp;
	}

	/**
	 * Point que la caméra regarde pendant la marche : le point de chemin, mais dès qu'on en est tout près on regarde plus
	 * loin (prochains nœuds), comme un joueur qui anticipe. Sans rien plus loin, la caméra ne bouge pas : viser un point
	 * à 0,3 bloc faisait tourner l'écran à chaque pas.
	 */
	private Vec3 lookAheadPoint(Vec3 pos, Vec3 waypoint) {
		double dx = waypoint.x - pos.x;
		double dz = waypoint.z - pos.z;
		if (dx * dx + dz * dz >= LOOK_NEAR * LOOK_NEAR) {
			return waypoint;
		}
		for (int j = Math.min(path.size() - 1, pathIndex + 4); j > pathIndex; j--) {
			BlockPos n = path.get(j);
			double nx = n.getX() + 0.5 - pos.x;
			double nz = n.getZ() + 0.5 - pos.z;
			if (nx * nx + nz * nz >= LOOK_NEAR * LOOK_NEAR) {
				return new Vec3(n.getX() + 0.5, n.getY(), n.getZ() + 0.5);
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
			BlockPos n = path.get(i);
			Vec3 c = new Vec3(n.getX() + 0.5, Walkability.standHeight(state.level(), n), n.getZ() + 0.5);
			if (!Walkability.segmentWalkable(state.level(), prev, c, 0.0, false)) {
				paths.avoid(n);
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
					paths.avoid(state.player().blockPosition());
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
			BlockPos probe = BlockPos.containing(pos.x + rx * dir * 0.9, pos.y + 0.05, pos.z + rz * dir * 0.9);
			if (Walkability.canStandAt(state.level(), probe) || Walkability.canStandAt(state.level(), probe.below())) {
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

	private final LocalNavigator combatLocal = new LocalNavigator();
	private boolean combatFwdKey;
	private boolean combatLeftKey;
	private boolean combatRightKey;

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
			}
			double delta = Math.toRadians(net.minecraft.util.Mth.wrapDegrees((float) (heading - state.yaw())));
			double fwd = Math.cos(delta);
			double side = Math.sin(delta);
			if (safe) {
				fwdKey = hysteresis(combatFwdKey, fwd);
				rightKey = hysteresis(combatRightKey, side);
				leftKey = hysteresis(combatLeftKey, -side);
				if (leftKey && rightKey) {
					leftKey = false;
					rightKey = false;
				}
				sprint = st != null && st.sprintOk() && cfg.useSprint && fwdKey && fwd > 0.9;
			}
		} else {
			combatLocal.reset();
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
				"combatApproach dist=%.1f avant=%s gauche=%s droite=%s recul=%s sprint=%s saut=%s sûr=%s",
				distance, fwdKey, leftKey, rightKey, backKey, sprint, doJump, ok));
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

		if (++ticksSinceLineCheck >= 4) {
			lineClear = paths.isClearLine(state.level(), pos, dest);
			ticksSinceLineCheck = 0;
		}
		if (forbidLineTicks > 0) {
			lineClear = false; // on vient de se bloquer sur cette "ligne droite" : on prend le chemin
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
		boolean forced = ticksSincePath > Integer.MAX_VALUE / 4 || collisionTicks > 12 || pathIndex >= path.size();
		boolean needsPath = goalMoved || ++ticksSincePath >= interval || forced;
		if (needsPath) {
			PathController.PathResult result =
				paths.findPathBestEffort(state.level(), state.player().blockPosition(), goal, budget);
			// Engagement dans un chemin : un recalcul périodique ne remplace pas le chemin en cours par un autre de longueur
			// comparable (deux routes presque équivalentes autour d'un obstacle faisaient faire demi-tour au joueur et
			// tourner la caméra de ~100° à chaque recalcul). On change seulement si c'est nettement plus court ou imposé.
			int remaining = path.size() - pathIndex;
			boolean keepOld = !forced && !goalMoved && remaining > 2 && !result.path().isEmpty()
				&& result.path().size() > remaining * 0.8 && result.complete() == pathComplete;
			if (keepOld) {
				ticksSincePath = 0;
			} else {
				path = result.path();
				pathComplete = result.complete();
				pathIndex = 0;
				pathGoal = goal;
				ticksSincePath = 0;
				collisionTicks = 0;
			}
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

		if (forwardOnly) {
			// La caméra suit le chemin : on marche comme un joueur, uniquement vers l'avant. Si le point est trop de côté on
			// tourne d'abord (sur place s'il est proche), sans jamais courir en crabe ni à reculons pendant que l'écran tourne.
			double angle = Math.toDegrees(Math.atan2(Math.abs(side), fwd));
			boolean walk = forwardOn ? angle < FORWARD_ANGLE_OFF || (angle < 100 && len > 3.0)
				: angle < FORWARD_ANGLE_ON;
			forwardOn = walk && !braking && !steerUnsafe;
			backOn = false;
			leftOn = false;
			rightOn = false;
		} else {
		forwardOn = hysteresis(forwardOn, fwd) && !braking;
		// Reculer : indispensable quand on regarde la cible mais que le chemin part dans l'autre sens (contournement d'un mur).
		backOn = hysteresis(backOn, -fwd) && !braking;
		leftOn = hysteresis(leftOn, -side);
		rightOn = hysteresis(rightOn, side);
		if (leftOn && rightOn) { // ne peut arriver qu'à la limite exacte des seuils
			leftOn = false;
			rightOn = false;
		}
		}

		if (steerUnsafe) { // aucune direction sûre : on ne s'engage pas, le chemin est recalculé
			forwardOn = false;
			backOn = false;
			leftOn = false;
			rightOn = false;
		}
		input.request(owner, Key.FORWARD, forwardOn);
		input.request(owner, Key.BACK, backOn);
		input.request(owner, Key.LEFT, leftOn);
		input.request(owner, Key.RIGHT, rightOn);

		// On lève le pied (pas de sprint) juste avant un virage serré du chemin, comme un joueur qui anticipe.
		boolean sharpTurn = cfg.turnSlowdown && len < 2.5 && turnFactor(pos) > 0.55;
		boolean sprint = cfg.useSprint && forwardOn && fwd > 0.9 && distToDest > cfg.slowDistance && !sharpTurn
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
