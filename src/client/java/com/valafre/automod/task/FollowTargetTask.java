package com.valafre.automod.task;

import com.valafre.automod.core.Framework;
import com.valafre.automod.core.Task;
import com.valafre.automod.core.TaskStatus;
import com.valafre.automod.targeting.TargetInfo;
import com.valafre.automod.targeting.TargetSelector;
import net.minecraft.world.entity.Entity;

/**
 * Suit une entité mobile : à chaque tick la position et le regard sont recalculés sur la position ACTUELLE de la cible.
 * Le regard suit la cible progressivement ; le déplacement se fait par strafe relatif à ce regard.
 */
public final class FollowTargetTask extends Task {

	private final Entity target;
	private final Chase chase = new Chase();

	public FollowTargetTask(int priority, Entity target) {
		super(priority);
		this.target = target;
	}

	@Override
	public String name() {
		return "FollowTarget";
	}

	@Override
	public TaskStatus onTick(Framework f) {
		if (!TargetSelector.isValid(target)) {
			return TaskStatus.FAILED;
		}
		TargetInfo info = TargetInfo.of(f.player(), target);
		boolean sight = f.combat().hasStableLineOfSight(f.player(), target);
		if (sight) { // sans ligne de vue, le mouvement oriente la caméra vers le chemin (une seule source de regard)
			f.rotation().lookAt(f.humanizer().aim(target, f.player()), target.getBoundingBox(), "ENEMY");
		}
		chase.step(f, owner(), target, info, sight);
		return TaskStatus.RUNNING;
	}
}
