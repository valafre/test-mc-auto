package com.valafre.automod.core;

/** Niveaux de priorité standard des tâches. Une tâche de priorité strictement supérieure interrompt la courante. */
public final class TaskPriority {

	public static final int SAFETY = 100;
	public static final int CRITICAL_MECHANIC = 90;
	public static final int REPOSITIONING = 80;
	public static final int COMBAT = 60;
	public static final int FOLLOW = 40;
	public static final int IDLE = 10;

	private TaskPriority() {}
}
