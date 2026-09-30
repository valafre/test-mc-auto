package com.valafre.automod.nametag;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sépare un nametag en niveau / nom / vie / vie max au lieu de traiter la ligne entière comme un nom.
 * Formats gérés : "[Lv50] Enderman 9,000/9,000❤", "Lv. 90 Zombie", "Voidgloom Seraph IV 50M❤", "Spider 1.5k/2k".
 * Classe volontairement indépendante de Minecraft (testable seule).
 */
public final class NametagParser {

	private static final String NUMBER = "[0-9][0-9.,]*[kKmMbB]?";
	private static final Pattern FORMAT_CODES = Pattern.compile("(?i)§[0-9A-FK-ORX]");
	private static final Pattern NON_TEXT = Pattern.compile("[^\\p{L}\\p{N}\\p{P}\\p{Zs}]");
	private static final Pattern LEVEL = Pattern.compile("(?i)\\[?\\s*lv\\.?\\s*(\\d+)\\s*]?");
	private static final Pattern HEALTH_PAIR = Pattern.compile("(?i)(" + NUMBER + ")\\s*/\\s*(" + NUMBER + ")(?:\\s*HP\\b)?");
	private static final Pattern HEALTH_SINGLE = Pattern.compile("(?i)(" + NUMBER + ")\\s*HP\\b");
	private static final Pattern BRACKETS = Pattern.compile("[\\[\\](){}]");
	private static final Pattern SPACES = Pattern.compile("\\s+");
	private static final Pattern EDGE_PUNCT = Pattern.compile("^[\\p{P}\\s]+|[\\p{P}\\s]+$");

	private NametagParser() {}

	public static NametagInfo parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return NametagInfo.EMPTY;
		}
		// Le coeur devient un marqueur "HP" (il serait sinon supprimé avec les autres symboles).
		String text = FORMAT_CODES.matcher(raw).replaceAll("")
			.replace("❤️", " HP ").replace("❤", " HP ").replace("♥", " HP ");
		text = NON_TEXT.matcher(text).replaceAll("");

		int level = -1;
		Matcher lv = LEVEL.matcher(text);
		if (lv.find()) {
			level = Integer.parseInt(lv.group(1));
			text = lv.replaceFirst(" ");
		}

		double health = -1;
		double maxHealth = -1;
		Matcher pair = HEALTH_PAIR.matcher(text);
		if (pair.find()) {
			health = parseNumber(pair.group(1));
			maxHealth = parseNumber(pair.group(2));
			text = pair.replaceFirst(" ");
		} else {
			Matcher single = HEALTH_SINGLE.matcher(text);
			if (single.find()) {
				health = parseNumber(single.group(1));
				maxHealth = health;
				text = single.replaceFirst(" ");
			}
		}

		String name = BRACKETS.matcher(text).replaceAll(" ");
		name = SPACES.matcher(name).replaceAll(" ");
		name = EDGE_PUNCT.matcher(name).replaceAll("");
		return new NametagInfo(raw, name, level, health, maxHealth);
	}

	/** "9,000" -> 9000 ; "1.5k" -> 1500 ; "50M" -> 5.0E7 ; -1 si illisible. */
	static double parseNumber(String token) {
		String t = token.trim().toLowerCase(Locale.ROOT).replaceAll("[.,]+$", "");
		double multiplier = 1;
		if (!t.isEmpty()) {
			char last = t.charAt(t.length() - 1);
			if (last == 'k' || last == 'm' || last == 'b') {
				multiplier = last == 'k' ? 1_000 : last == 'm' ? 1_000_000 : 1_000_000_000;
				t = t.substring(0, t.length() - 1);
			}
		}
		// Un suffixe k/m/b utilise le point comme décimale ; sinon la virgule est un séparateur de milliers.
		t = multiplier == 1 ? t.replace(",", "") : t.replace(",", ".");
		try {
			return Double.parseDouble(t) * multiplier;
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
