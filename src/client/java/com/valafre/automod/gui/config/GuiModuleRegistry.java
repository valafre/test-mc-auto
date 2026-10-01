package com.valafre.automod.gui.config;

import java.util.ArrayList;
import java.util.List;

/** Registre des descripteurs GUI des modules. */
public final class GuiModuleRegistry {

	private static final List<GuiModule> MODULES = new ArrayList<>();

	private GuiModuleRegistry() {}

	public static void register(GuiModule module) {
		MODULES.add(module);
	}

	public static List<GuiModule> all() {
		return MODULES;
	}

	public static GuiModule get(String moduleId) {
		for (GuiModule m : MODULES) {
			if (m.moduleId().equals(moduleId)) {
				return m;
			}
		}
		return null;
	}

	/** Module ciblé par la touche « Activer/désactiver le module » : le premier enregistré. */
	public static String primaryId() {
		return MODULES.isEmpty() ? null : MODULES.get(0).moduleId();
	}
}
