package com.valafre.automod.task;

import com.valafre.automod.core.Framework;
import com.valafre.automod.core.Task;
import com.valafre.automod.core.TaskStatus;

/** Arrête immédiatement le déplacement et la rotation automatiques (les touches sont relâchées en fin de tick). */
public final class StopMovementTask extends Task {

	public StopMovementTask(int priority) {
		super(priority);
	}

	@Override
	public String name() {
		return "StopMovement";
	}

	@Override
	public void onStart(Framework f) {
		f.movement().reset();
		f.rotation().cancel();
	}

	@Override
	public TaskStatus onTick(Framework f) {
		return TaskStatus.SUCCEEDED;
	}
}
