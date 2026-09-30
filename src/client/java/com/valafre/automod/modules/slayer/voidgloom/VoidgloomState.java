package com.valafre.automod.modules.slayer.voidgloom;

/** États du module Voidgloom. */
public enum VoidgloomState {
	IDLE,
	SLAYER_CHECK,
	SEARCHING_TARGET,
	FOLLOWING_TARGET,
	GROUND_MECHANIC_DETECTED,
	CHOOSING_POSITION,
	REPOSITIONING,
	POSITION_REACHED,
	ALIGNING,
	ATTACKING,
	TARGET_LOST,
	STOPPING
}
