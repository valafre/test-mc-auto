package com.valafre.automod.task;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.Task;
import com.valafre.automod.core.TaskStatus;
import com.valafre.automod.targeting.TargetInfo;
import com.valafre.automod.targeting.TargetSelector;
import net.minecraft.world.entity.LivingEntity;

import java.util.Random;

/**
 * Tâche de combat CONTINUE : poursuit la cible, la contourne en strafe et laisse le CombatController frapper dès que
 * portée, ligne de vue, viseur et cadence le permettent. Le joueur ne s'arrête jamais pour taper. Ne se termine que si
 * la cible devient invalide ; le module n'a donc pas à alterner suivi/attaque (ce qui relâchait les touches à chaque coup).
 */
public final class AttackTargetTask extends Task {

	/** Au-delà de approachDistance + cette marge, on se contente de poursuivre (pas de strafe). */
	private static final double STRAFE_ZONE_MARGIN = 1.0;

	private final LivingEntity target;
	private final boolean holdPosition;
	private final Chase chase = new Chase();
	private final Random rng = new Random();
	private int strafeDir = 1;
	private int switchIn;

	/** @param holdPosition true : le joueur garde sa position (imposée par une mécanique), vise et frappe sans bouger */
	public AttackTargetTask(int priority, LivingEntity target, boolean holdPosition) {
		super(priority);
		this.target = target;
		this.holdPosition = holdPosition;
		this.strafeDir = rng.nextBoolean() ? 1 : -1;
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
		f.rotation().lookAt(f.humanizer().adjustAim(target, info.aimPoint()));

		if (!holdPosition) {
			boolean inStrafeZone = info.distance() <= cfg.approachDistance + STRAFE_ZONE_MARGIN
				&& f.combat().hasLineOfSight(f.player(), target);
			if (cfg.strafeInCombat && inStrafeZone) {
				strafe(f, cfg, info);
			} else {
				chase.step(f, owner(), target, info); // trop loin ou sans ligne de vue : on rejoint / contourne
			}
		}
		f.combat().tryAttack(f.player(), target);
		return TaskStatus.RUNNING;
	}

	/** Mouvement continu autour de la cible ; le sens change de temps en temps et s'inverse devant un mur ou un vide. */
	private void strafe(Framework f, ModConfig cfg, TargetInfo info) {
		if (--switchIn <= 0) {
			strafeDir = -strafeDir;
			switchIn = 15 + rng.nextInt(30);
		}
		boolean sideOk = f.movement().combatMove(f.player(), owner(), info.distance(),
			cfg.combatMinDistance, cfg.approachDistance, strafeDir);
		if (!sideOk) {
			strafeDir = -strafeDir;
			switchIn = 15 + rng.nextInt(30);
		}
	}
}
