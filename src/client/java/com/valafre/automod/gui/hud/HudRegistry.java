package com.valafre.automod.gui.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Registre des sections du HUD, dans l'ordre d'enregistrement (de haut en bas). */
public final class HudRegistry {

	private static final List<HudSection> SECTIONS = new ArrayList<>();

	private HudRegistry() {}

	public static void register(HudSection section) {
		SECTIONS.add(section);
	}

	public static List<HudSection> all() {
		return Collections.unmodifiableList(SECTIONS);
	}
}
