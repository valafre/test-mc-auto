package com.valafre.automod.gui.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Registre des catégories du menu, dans l'ordre d'enregistrement. Un futur module y ajoute simplement la sienne. */
public final class SettingsRegistry {

	private static final List<SettingsCategory> CATEGORIES = new ArrayList<>();

	private SettingsRegistry() {}

	public static void register(SettingsCategory category) {
		CATEGORIES.add(category);
	}

	public static List<SettingsCategory> all() {
		return Collections.unmodifiableList(CATEGORIES);
	}
}
