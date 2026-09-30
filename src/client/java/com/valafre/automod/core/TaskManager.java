package com.valafre.automod.core;

import com.valafre.automod.input.InputController;

/**
 * Exécute UNE tâche à la fois, avec priorités. Une nouvelle tâche préempte la courante si sa priorité est strictement
 * supérieure (ou égale et du même propriétaire) ; sinon elle est refusée. Le propriétaire de la tâche reçoit le contrôle
 * des inputs, rendu à la fin de la tâche.
 */
public final class TaskManager {

	private final InputController input;
	private Task current;
	private String currentOwner;

	public TaskManager(InputController input) {
		this.input = input;
	}

	// ========================================
	// TÂCHES
	// ========================================

	/** @return true si la tâche a été acceptée et démarrée. */
	public boolean submit(Framework framework, String owner, Task task) {
		if (current != null) {
			boolean higher = task.priority() > current.priority();
			boolean replacesOwn = task.priority() == current.priority() && owner.equals(currentOwner);
			if (!higher && !replacesOwn) {
				return false;
			}
			finish(framework, TaskStatus.CANCELLED);
		}
		if (!input.claim(owner, task.priority())) {
			return false;
		}
		current = task;
		currentOwner = owner;
		task.setOwner(owner);
		task.setStatus(TaskStatus.RUNNING);
		Debug.log("Task", () -> "Démarrage " + task.name() + " (priorité " + task.priority() + ", propriétaire " + owner + ")");
		task.onStart(framework);
		return true;
	}

	public void tick(Framework framework) {
		if (current == null) {
			return;
		}
		TaskStatus result = current.onTick(framework);
		if (result != TaskStatus.RUNNING) {
			finish(framework, result);
		}
	}

	private void finish(Framework framework, TaskStatus status) {
		Task finished = current;
		String owner = currentOwner;
		current = null;
		currentOwner = null;
		finished.setStatus(status);
		finished.onStop(framework);
		framework.movement().reset(); // chemin/hystérésis périmés ne doivent pas fuiter vers la tâche suivante
		input.release(owner);
		Debug.log("Task", () -> "Fin " + finished.name() + " : " + status);
	}

	public void cancelAll(Framework framework) {
		if (current != null) {
			finish(framework, TaskStatus.CANCELLED);
		}
		input.clearClaim();
	}

	public void cancelOwner(Framework framework, String owner) {
		if (current != null && owner.equals(currentOwner)) {
			finish(framework, TaskStatus.CANCELLED);
		}
	}

	public Task current() {
		return current;
	}

	public boolean isRunning(Class<? extends Task> type) {
		return current != null && type.isInstance(current);
	}
}
