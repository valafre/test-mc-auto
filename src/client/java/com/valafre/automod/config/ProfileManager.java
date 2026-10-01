package com.valafre.automod.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** Profils de configuration : copies nommées de la config, stockées dans {@code config/automod-profiles/}. */
public final class ProfileManager {

	private static String current = "";

	private ProfileManager() {}

	private static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("automod-profiles");
	}

	/** Nettoie un nom saisi : lettres, chiffres, espace, tiret et souligné uniquement. */
	public static String sanitize(String name) {
		return name == null ? "" : name.replaceAll("[^A-Za-z0-9 _\\-]", "").trim();
	}

	public static String current() {
		return current;
	}

	public static List<String> list() {
		List<String> names = new ArrayList<>();
		try (Stream<Path> files = Files.exists(dir()) ? Files.list(dir()) : Stream.empty()) {
			files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
				.forEach(n -> names.add(n.substring(0, n.length() - 5)));
		} catch (IOException ignored) {
			// dossier illisible : liste vide
		}
		Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
		return names;
	}

	/** Enregistre la config actuelle sous ce nom. @return message de résultat. */
	public static String save(String rawName) {
		String name = sanitize(rawName);
		if (name.isEmpty()) {
			return "Nom de profil invalide.";
		}
		try {
			Files.createDirectories(dir());
			Files.writeString(dir().resolve(name + ".json"), ModConfig.toJson(), StandardCharsets.UTF_8);
			current = name;
			return "Profil « " + name + " » enregistré.";
		} catch (IOException e) {
			return "Échec de l'enregistrement : " + e.getMessage();
		}
	}

	public static String load(String name) {
		try {
			String json = Files.readString(dir().resolve(name + ".json"), StandardCharsets.UTF_8);
			if (!ModConfig.loadFromJson(json)) {
				return "Profil « " + name + " » illisible.";
			}
			ModConfig.save();
			current = name;
			return "Profil « " + name + " » chargé.";
		} catch (IOException e) {
			return "Profil introuvable : " + name;
		}
	}

	public static String delete(String name) {
		try {
			Files.deleteIfExists(dir().resolve(name + ".json"));
			if (name.equals(current)) {
				current = "";
			}
			return "Profil « " + name + " » supprimé.";
		} catch (IOException e) {
			return "Échec de la suppression : " + e.getMessage();
		}
	}

	public static String resetDefaults() {
		ModConfig.resetToDefaults();
		ModConfig.save();
		current = "";
		return "Configuration réinitialisée aux valeurs par défaut.";
	}
}
