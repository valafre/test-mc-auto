package com.valafre.automod.task;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.Task;
import com.valafre.automod.core.TaskStatus;
import com.valafre.automod.input.InputController.Key;
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
	private static final double CAMERA_LAG_STOP_DEG = 80.0;
	private static final double LOOK_ENTER_DISTANCE = 6.0;
	private static final double LOOK_EXIT_DISTANCE = 8.0;
	private static final int KEEP_LOOK_TICKS = 3;
	private static final double KEEP_LOOK_MAX_DISTANCE = 12.0;
	private static final int STUCK_TICKS = 40;
	private static final int UNSTICK_DURATION = 30;
	private static final int DETOUR_TICKS = 25;

	private final LivingEntity target;
	private final boolean holdPosition;
	private final boolean sneak;
	private final Chase chase = new Chase();
	/** Ticks consécutifs collé à un obstacle sans avancer, et ticks restants de contournement par chemin. */
	private int wallTicks;
	private int detourTicks;
	private boolean followDown;
	private int sightLostTicks;
	private boolean lastLagStop;
	private boolean lookEnemy;
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
		} else if (lookEnemy) {
			lookEnemy = seen && (info.distance() <= LOOK_EXIT_DISTANCE || straight);
		} else {
			lookEnemy = sight && (info.distance() <= LOOK_ENTER_DISTANCE || straight);
		}
		if (lookEnemy) {
			f.rotation().lookAt(aim, target.getBoundingBox(), "ENEMY");
		}

		boolean closeCombat = false;
		if (!holdPosition) {
			var player = f.player().player();
			boolean pushing = player.horizontalCollision && player.getDeltaMovement().horizontalDistanceSqr() < 0.0025;
			wallTicks = pushing ? wallTicks + 1 : 0;
			// Anticipation : un obstacle infranchissable juste devant dans la direction de la cible -> on contourne avant de le toucher.
			Vec3 toTarget = target.position().subtract(player.position());
			if (detourTicks == 0 && sight && player.onGround()
				&& com.valafre.automod.movement.Walkability.riseAhead(player.level(), player.position(), toTarget.x, toTarget.z) > 1.1) {
				detourTicks = DETOUR_TICKS;
			}
			if (wallTicks > WALL_TICKS_BEFORE_DETOUR) { // collé à un mur / une vitre / un rebord : on contourne par le chemin
				detourTicks = DETOUR_TICKS;
				wallTicks = 0;
			}
			boolean detour = detourTicks > 0;
			if (detour) {
				detourTicks--;
			}
			// Caméra en retard de plus de 55° sur la cible : on cesse d'avancer le temps de la rattraper, au lieu de courir
			// pendant que l'écran tourne en rond (la cible tourne autour de nous plus vite que la caméra ne la suit).
			double lag = Math.abs(com.valafre.automod.movement.RotationController.yawDelta(
				f.player().eyePosition(), target.position(), f.player().yaw()));
			boolean waitForCamera = lookEnemy && sight && !detour && lag > CAMERA_LAG_STOP_DEG && info.distance() > 2.5;
			boolean close = sight && !detour && info.distance() <= cfg.approachDistance + CLOSE_ZONE_MARGIN;
			closeCombat = close;
			if (waitForCamera) {
				lastLagStop = true; // aucune touche de déplacement ce tick : la caméra rattrape
			} else if (close) {
				// Au contact : on avance en continu vers la cible (pas d'arrêt pour frapper), sans balayer l'écran.
				f.movement().combatMove(f.player(), owner(), info.distance(), CLOSE_BACK_DISTANCE, cfg.combatMinDistance, 0);
			} else {
				chase.step(f, owner(), target, info, sight && !detour, !lookEnemy || detour); // trop loin ou sans ligne de vue : on rejoint / contourne
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
		f.items().equip(f.player(), cfg.weaponKeyword);
		if (!f.items().isBusy()) { // un objet utilitaire (Wand/Orb) est en main ce tick : on n'attaque pas avec
			f.combat().tryAttack(f.player(), target);
		}
		return TaskStatus.RUNNING;
	}
}
