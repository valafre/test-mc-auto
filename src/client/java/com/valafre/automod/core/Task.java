package com.valafre.automod.core;

/**
 * Action demandée au moteur par un module (déplacer, regarder, suivre, attaquer, arrêter...).
 * Le {@link TaskManager} appelle {@link #onTick} chaque tick tant que la tâche est en cours.
 */
public abstract class Task {

	private TaskStatus status = TaskStatus.PENDING;
	private final int priority;
	private String owner;

	protected Task(int priority) {
		this.priority = priority;
	}

	public abstract String name();

	public int priority() {
		return priority;
	}

	public TaskStatus status() {
		return status;
	}

	/** Propriétaire (id du module) : c'est lui qui détient les inputs pendant la tâche. */
	public String owner() {
		return owner;
	}

	void setOwner(String owner) {
		this.owner = owner;
	}

	void setStatus(TaskStatus status) {
		this.status = status;
	}

	/** Appelée une fois au démarrage. */
	public void onStart(Framework framework) {}

	/** @return RUNNING pour continuer, SUCCEEDED ou FAILED pour terminer. */
	public abstract TaskStatus onTick(Framework framework);

	/** Appelée à la fin, y compris en cas d'annulation/interruption : nettoyer ce qui a été demandé. */
	public void onStop(Framework framework) {}
}
