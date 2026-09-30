package com.valafre.automod.core;

/** Gère le drapeau enabled et les hooks de cycle de vie communs. */
public abstract class AbstractModule implements ModModule {

	private boolean enabled;

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	final void setEnabled(Framework framework, boolean value) {
		if (enabled == value) {
			return;
		}
		enabled = value;
		if (value) {
			onEnable(framework);
		} else {
			onDisable(framework);
		}
	}
}
