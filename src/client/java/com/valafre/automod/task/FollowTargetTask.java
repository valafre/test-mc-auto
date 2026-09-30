package com.valafre.automod.task;

import com.valafre.automod.config.ModConfig;
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
		f.rotation().lookAt(info.aimPoint());
		ModConfig cfg = ModConfig.get();
		if (info.distance() > cfg.approachDistance) {
			// Destination = position actuelle de la cible ; stop à approachDistance de sa position horizontale.
			f.movement().moveTo(f.player(), owner(), target.position(), cfg.approachDistance, false);
		} else {
			f.movement().reset();
		}
		return TaskStatus.RUNNING;
	}
}
