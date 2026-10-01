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

	private static final int WALL_TICKS_BEFORE_DETOUR = 8;
	private static final int DETOUR_TICKS = 40;

	private final LivingEntity target;
	private final boolean holdPosition;
	private final boolean sneak;
	private final Chase chase = new Chase();
	/** Ticks consécutifs collé à un obstacle sans avancer, et ticks restants de contournement par chemin. */
	private int wallTicks;
	private int detourTicks;

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
		if (sight || holdPosition) {
			f.rotation().lookAt(aim, target.getBoundingBox(), "ENEMY");
		}

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
			boolean close = sight && !detour && info.distance() <= cfg.approachDistance + CLOSE_ZONE_MARGIN;
			if (close) {
				// Au contact : on avance en continu vers la cible (pas d'arrêt pour frapper), sans balayer l'écran.
				f.movement().combatMove(f.player(), owner(), info.distance(), CLOSE_BACK_DISTANCE, cfg.combatMinDistance, 0);
			} else {
				chase.step(f, owner(), target, info, sight && !detour); // trop loin ou sans ligne de vue : on rejoint / contourne
			}
		}
		if (sneak) {
			f.input().request(owner(), Key.SNEAK, true);
		}
		f.items().equip(f.player(), cfg.weaponKeyword);
		if (!f.items().isBusy()) { // un objet utilitaire (Wand/Orb) est en main ce tick : on n'attaque pas avec
			f.combat().tryAttack(f.player(), target);
		}
		return TaskStatus.RUNNING;
	}
}
