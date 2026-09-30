package com.valafre.automod.nametag;

/**
 * Résultat de l'analyse d'un nametag. Les champs inconnus valent -1 ({@code level}, {@code health}, {@code maxHealth}).
 * Exemple : "[Lv50] Enderman 9,000/9,000❤" -> name="Enderman", level=50, health=9000, maxHealth=9000.
 */
public record NametagInfo(String raw, String name, int level, double health, double maxHealth) {

	public static final NametagInfo EMPTY = new NametagInfo("", "", -1, -1, -1);

	public boolean hasLevel() {
		return level >= 0;
	}

	public boolean hasHealth() {
		return health >= 0;
	}

	public boolean isEmpty() {
		return name.isEmpty() && !hasLevel() && !hasHealth();
	}
}
