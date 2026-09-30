package com.valafre.automod.task;

import com.valafre.automod.core.Framework;
import com.valafre.automod.core.Task;
import com.valafre.automod.core.TaskStatus;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/** Tourne progressivement vers un point (recalculé chaque tick) ; réussit quand l'orientation est dans la tolérance. */
public final class LookAtTask extends Task {

	private final Supplier<Vec3> point;

	public LookAtTask(int priority, Supplier<Vec3> point) {
		super(priority);
		this.point = point;
	}

	@Override
	public String name() {
		return "LookAt";
	}

	@Override
	public TaskStatus onTick(Framework f) {
		Vec3 target = point.get();
		if (target == null) {
			return TaskStatus.FAILED;
		}
		f.rotation().lookAt(target);
		return f.rotation().isAligned(f.player(), target) ? TaskStatus.SUCCEEDED : TaskStatus.RUNNING;
	}
}
