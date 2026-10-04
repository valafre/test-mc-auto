package com.valafre.automod.task;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.Task;
import com.valafre.automod.core.TaskStatus;
import com.valafre.automod.input.InputController.Key;
import com.valafre.automod.movement.CombatPositioner;
import com.valafre.automod.targeting.TargetInfo;
import com.valafre.automod.targeting.TargetSelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Tâche de combat CONTINUE : poursuit la cible en avançant et laisse le CombatController frapper dès que portée, ligne de
 * vue, viseur et cadence le permettent. Le joueur ne s'arrête jamais pour taper. Ne se termine que si la cible devient
 * invalide ; le module n'a donc pas à alterner suivi/attaque.
 *
 * <p>Regard : UNE seule source par tick. Avec ligne de vue stable on regarde l'ennemi ; sans ligne de vue, c'est le
 * mouvement (contournement) qui oriente la caméra vers le prochain point du chemin. La ligne de vue est stabilisée
 * (hystérésis) pour que la caméra ne bascule pas d'une source à l'autre près d'un coin de mur.
 */
public final class AttackTargetTask extends Task {

	/** Au-delà de approachDistance + cette marge, on se contente de poursuivre (sans logique de distance fine). */
	private static final double CLOSE_ZONE_MARGIN = 1.0;
	/** Reculer seulement si on est vraiment collé dans la cible. */
	private static final double CLOSE_BACK_DISTANCE = 1.1;

	private static final int WALL_TICKS_BEFORE_DETOUR = 3;
	private static final double LOOK_ENTER_DISTANCE = 8.0;
	private static final double LOOK_EXIT_DISTANCE = 10.0;
	private static final int KEEP_LOOK_TICKS = 6;
	private static final double KEEP_LOOK_MAX_DISTANCE = 12.0;
	private static final int STUCK_TICKS = 40;
	private static final int UNSTICK_DURATION = 30;
	private static final int DETOUR_TICKS = 25;

	private final LivingEntity target;
	private final boolean holdPosition;
	private final boolean sneak;
	private final Chase chase = new Chase();
	private final CombatPositioner positioner = new CombatPositioner();
	/** Ticks consécutifs collé à un obstacle sans avancer, et ticks restants de contournement par chemin. */
	private int wallTicks;
	private int detourTicks;
	private boolean followDown;
	private int sightLostTicks;
	private boolean lookEnemy;
	private boolean dbgDetour;
	private boolean dbgWaitCam;
	private double dbgLag;
	private Vec3 dbgCombatPos;
	private boolean dbgChoseCalled;
	private Vec3 stuckAnchor;
	private int stuckTicks;
	private int unstickTicks;

	/** @param sneak true : reste accroupi pendant toute la tâche (combat contre le boss)
	 *  @param holdPosition true : le joueur garde sa position (imposée par une mécanique), vise et frappe sans bouger */
	public AttackTargetTask(int priority, LivingEntity target, boolean holdPosition, boolean sneak) {
		super(priority);
		this.target = target;
		this.holdPosition = holdPosition;
		this.sneak = sneak;
	}

	@Override
	public String name() {
		return holdPosition ? "AttackTarget(position)" : "AttackTarget";
	}

	@Override
	public TaskStatus onTick(Framework f) {
		if (!TargetSelector.isValid(target)) {
			return TaskStatus.FAILED;
		}
		ModConfig cfg = ModConfig.get();
		TargetInfo info = TargetInfo.of(f.player(), target);
		boolean sight = f.combat().hasStableLineOfSight(f.player(), target);

		// Regard : l'ennemi si on le voit (ou si on garde la position), sinon le mouvement oriente vers le chemin.
		Vec3 aim = f.hints().applyGlance(f.humanizer().aim(target, f.player()), target);
		// Ligne de vue perdue depuis moins de 0,15 s : on garde les yeux sur la cible (elle reparaît souvent au coin) au lieu
		// de basculer vers le chemin, ce qui faisait tourner la caméra de ~100° dans un sens puis dans l'autre.
		sightLostTicks = sight ? 0 : sightLostTicks + 1;
		boolean recentlySaw = !sight && sightLostTicks <= KEEP_LOOK_TICKS && info.distance() < KEEP_LOOK_MAX_DISTANCE;
		// Qui regarde-t-on ? De loin et sans ligne droite, le chemin (comme un joueur qui court vers le mob) ; dès qu'on est
		// proche, ou que la cible est en ligne droite, la cible. Hystérésis : pas d'aller-retour caméra à chaque coup d'oeil.
		boolean straight = f.movement().hasClearLine(f.player(), target.position());
		boolean seen = sight || recentlySaw;
		if (holdPosition) {
			lookEnemy = true;
		} else if (sight) {
			// Une cible réellement visible reste la référence de caméra pendant le combat. Le chemin peut encore piloter les touches.
			lookEnemy = true;
		} else if (lookEnemy) {
			lookEnemy = recentlySaw && (info.distance() <= LOOK_EXIT_DISTANCE || straight);
		} else {
			lookEnemy = recentlySaw && info.distance() <= LOOK_ENTER_DISTANCE;
		}
		if (lookEnemy) {
			f.rotation().lookAt(aim, target.getBoundingBox(), "ENEMY");
		}

		boolean closeCombat = false;
		dbgCombatPos = null;
		dbgChoseCalled = false;
		dbgDetour = false;
		dbgWaitCam = false;
		if (!holdPosition) {
			var player = f.player().player();
			boolean pushing = player.horizontalCollision && player.getDeltaMovement().horizontalDistanceSqr() < 0.0025;
			wallTicks = pushing ? wallTicks + 1 : 0;
			if (wallTicks > WALL_TICKS_BEFORE_DETOUR) { // collé à un mur / une vitre / un rebord : on contourne par le chemin
				detourTicks = DETOUR_TICKS;
				wallTicks = 0;
			}
			boolean detour = detourTicks > 0;
			if (detour) {
				detourTicks--;
			}
			double lag = Math.abs(com.valafre.automod.movement.RotationController.yawDelta(
				f.player().eyePosition(), target.position(), f.player().yaw()));
			// Ne jamais immobiliser complètement le joueur simplement parce que la caméra rattrape une cible.
			// Un humain continuerait à se repositionner / contourner pendant qu'il ramène son regard.
			boolean waitForCamera = false;
			boolean close = sight && !detour && info.distance() <= cfg.approachDistance + CLOSE_ZONE_MARGIN;
			closeCombat = close;
			dbgDetour = detour;
			dbgWaitCam = false;
			dbgLag = lag;
			if (close) {
				// Au contact : on avance en continu vers la cible (pas d'arrêt pour frapper), sans balayer l'écran.
				boolean ok = f.movement().combatApproach(f.player(), owner(), target.position(), target.getBoundingBox().getCenter(),
					info.distance(), CLOSE_BACK_DISTANCE, cfg.combatMinDistance);
				if (!ok) { // aucune direction sûre vers la cible : on contourne par un chemin au lieu de pousser contre l'obstacle
					detourTicks = DETOUR_TICKS;
				}
			} else {
				dbgChoseCalled = true;
				dbgCombatPos = positioner.choose(f.player(), target);
				chase.step(f, owner(), target, info, sight && !detour, !lookEnemy, lookEnemy, dbgCombatPos); // trop loin ou sans ligne de vue : on rejoint / contourne
			}
		}
		// Le boss est tombé plus bas (rebord, plateforme) : on cesse de s'accroupir pour pouvoir le suivre dans le vide.
		double below = f.player().player().getY() - target.getY();
		followDown = below > 1.0 || (followDown && below > 0.3);
		// Garde-fou : immobile depuis 2 s alors qu'on devrait avancer (rebord pris en sneak, coin...) : on se débloque.
		var pl = f.player().player();
		if (stuckAnchor == null || pl.position().distanceToSqr(stuckAnchor) > 0.09) {
			stuckAnchor = pl.position();
			stuckTicks = 0;
		} else if (!holdPosition && !closeCombat) {
			stuckTicks++;
		}
		if (stuckTicks > STUCK_TICKS) {
			unstickTicks = UNSTICK_DURATION;
			stuckTicks = 0;
		}
		if (unstickTicks > 0) {
			unstickTicks--;
			if (unstickTicks % 10 == 5) {
				f.input().request(owner(), Key.JUMP, true);
			}
		}
		// Accroupi seulement quand on est engagé (au contact ou en position) : en chemin on doit pouvoir descendre d'un rebord.
		if (sneak && !followDown && unstickTicks == 0 && !f.movement().isUnsticking() && (holdPosition || closeCombat)) {
			f.input().request(owner(), Key.SNEAK, true);
		}
		if (com.valafre.automod.core.Debug.enabled() || f.recorder().isRecording()) { // diagnostic uniquement
			com.valafre.automod.debug.CombatTrace.combatPosition(positioner.debugChosen(), positioner.debugAgeTicks(),
				positioner.debugReevaluated(), positioner.debugTargetAtEval(), dbgChoseCalled);
			com.valafre.automod.debug.CombatTrace.taskTarget(target, sight, String.format(java.util.Locale.ROOT,
				"lookEnemy=%s straight=%s dist=%.1f lagCaméra=%.0f détour=%s attendCaméra=%s hold=%s positionCombat=%s",
				lookEnemy, straight, info.distance(), dbgLag, dbgDetour, dbgWaitCam, holdPosition,
				dbgCombatPos == null ? "aucune (cible directe)" : String.format(java.util.Locale.ROOT, "(%.1f,%.1f,%.1f)", dbgCombatPos.x, dbgCombatPos.y, dbgCombatPos.z)));
		}
		com.valafre.automod.debug.NavDebug.targetId = target.getId(); // HUD / trace [NAV]
		com.valafre.automod.debug.NavDebug.camera = lookEnemy ? "ENEMY" : "PATH";
		com.valafre.automod.debug.NavDebug.lastTick = f.player().level().getGameTime();
		f.items().equip(f.player(), cfg.weaponKeyword);
		if (!f.items().isBusy()) { // un objet utilitaire (Wand/Orb) est en main ce tick : on n'attaque pas avec
			f.combat().tryAttack(f.player(), target);
		}
		return TaskStatus.RUNNING;
	}
}
