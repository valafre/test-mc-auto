package com.valafre.automod.core;

public enum TaskStatus {
	PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED;

	public boolean isFinished() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED;
	}
}
