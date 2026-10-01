package com.valafre.automod.gui.navigation;

/** Destination de navigation : une page, et pour un module l'identifiant du module. */
public record Route(Kind kind, String arg) {

	public enum Kind { HOME, MODULES, MODULE, SETTINGS, PROFILES, ABOUT }

	public static Route of(Kind kind) {
		return new Route(kind, "");
	}

	public static Route module(String moduleId) {
		return new Route(Kind.MODULE, moduleId);
	}

	/** Entrée de la barre latérale qui doit apparaître sélectionnée pour cette route. */
	public Kind navKind() {
		return kind == Kind.MODULE ? Kind.MODULES : kind;
	}
}
