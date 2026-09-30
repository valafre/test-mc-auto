package com.valafre.automod.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Registre des modules. Exécute les modules actifs par priorité décroissante. */
public final class ModuleManager {

	private final List<AbstractModule> modules = new ArrayList<>();

	public void register(AbstractModule module) {
		modules.add(module);
		modules.sort(Comparator.comparingInt(AbstractModule::getPriority).reversed());
	}

	public boolean toggle(Framework framework, String id) {
		for (AbstractModule module : modules) {
			if (module.id().equals(id)) {
				module.setEnabled(framework, !module.isEnabled());
				return module.isEnabled();
			}
		}
		return false;
	}

	public boolean isEnabled(String id) {
		for (AbstractModule module : modules) {
			if (module.id().equals(id)) {
				return module.isEnabled();
			}
		}
		return false;
	}

	public String status(String id) {
		for (AbstractModule module : modules) {
			if (module.id().equals(id)) {
				return module.isEnabled() ? module.status() : "désactivé";
			}
		}
		return "";
	}

	public void tick(Framework framework) {
		for (AbstractModule module : modules) {
			if (module.isEnabled()) {
				module.onTick(framework);
			}
		}
	}

	public void safetyStop(Framework framework) {
		for (AbstractModule module : modules) {
			module.onSafetyStop(framework);
		}
	}

	public void disableAll(Framework framework) {
		for (AbstractModule module : modules) {
			module.setEnabled(framework, false);
		}
	}
}
