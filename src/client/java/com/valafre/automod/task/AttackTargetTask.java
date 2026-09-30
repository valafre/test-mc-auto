package com.valafre.automod.task;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.Task;
import com.valafre.automod.core.TaskStatus;
import com.valafre.automod.targeting.TargetInfo;
import com.valafre.automod.targeting.TargetSelector;
import net.minecraft.world.entity.LivingEntity;

/**
 * Se tient en place, s'aligne progressivement sur la cible et laisse le CombatController attaquer quand portée,
 * orientation et cooldown le permettent. Peut se terminer quand la cible sort de portée (voir constructeur).
 */
public final class AttackTargetTask extends Task {

	private static final double RANGE_HYSTERESIS = 0.5;

	private final LivingEntity target;
	private final boolean endWhenOutOfRange;

	/**
	 * @param endWhenOutOfRange true : la tâche se termine quand la cible s'éloigne (le module reprend le suivi) ;
	 *                          false : le joueur garde sa position, continue de viser et attaque dès que la cible revient à portée
	 */
	public AttackTargetTask(int priority, LivingEntity target, boolean endWhenOutOfRange) {
		super(priority);
		this.target = target;
		this.endWhenOutOfRange = endWhenOutOfRange;
	}

	@Override
	public String name() {
		return "AttackTarget";
	}

	@Override
	public TaskStatus onTick(Framework f) {
		if (!TargetSelector.isValid(target)) {
			return TaskStatus.FAILED;
		}
		TargetInfo info = TargetInfo.of(f.player(), target);
		f.rotation().lookAt(f.humanizer().adjustAim(target, info.aimPoint()));
		if (endWhenOutOfRange && info.distance() > ModConfig.get().attackDistance + RANGE_HYSTERESIS) {
			return TaskStatus.SUCCEEDED;
		}
		f.combat().tryAttack(f.player(), target);
		return TaskStatus.RUNNING;
	}
}
