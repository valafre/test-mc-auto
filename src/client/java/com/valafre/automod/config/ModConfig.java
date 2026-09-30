package com.valafre.automod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration centralisée. Toutes les valeurs "magiques" du framework vivent ici.
 * Persistée en JSON dans {@code config/automod.json} ; les champs absents du fichier gardent leur valeur par défaut.
 */
public final class ModConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger("automod");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ModConfig instance = new ModConfig();

	// ========================================
	// GÉNÉRAL
	// ========================================
	public boolean debugMode = false;

	// ========================================
	// TARGETING
	// ========================================
	/** Rayon de recherche des cibles autour du joueur (blocs). */
	public double targetSearchRange = 16.0;
	/** Une cible courante valide est conservée tant qu'elle reste sous range * ce facteur. */
	public double targetKeepRangeFactor = 1.5;
	/** Intervalle (ticks) entre deux recherches d'entités quand aucune cible n'est suivie. */
	public int targetSearchIntervalTicks = 10;
	/** Sur certains serveurs le nom est porté par un ArmorStand posé sur l'Enderman : on l'accepte aussi. */
	public boolean allowArmorStandNameplate = true;
	public String voidgloomNameKeyword = "voidgloom";
	/** Niveau exigé du nametag ("[Lv90]") ; 0 = niveau ignoré. */
	public int voidgloomRequiredLevel = 0;

	// ========================================
	// FARM DES ENDERMAN (pour faire apparaître le boss)
	// ========================================
	/** Tue les Enderman normaux tant qu'aucun Voidgloom n'est présent. */
	public boolean farmMobs = true;
	public String farmMobKeyword = "enderman";
	public double farmSearchRange = 32.0;

	// ========================================
	// HUMANISATION
	// ========================================
	public boolean humanize = true;
	/** Amplitude de la dérive du point visé, en fraction de la taille de la hitbox. */
	public double aimOffsetFraction = 0.25;
	/** Variation (+/-) de la vitesse de rotation propre à chaque cible. */
	public float rotationSpeedVariation = 0.2f;
	public int reactionDelayMinTicks = 4;
	public int reactionDelayMaxTicks = 14;
	public int attackJitterTicks = 3;

	// ========================================
	// DISTANCES
	// ========================================
	/** Distance (oeil -> hitbox) à laquelle le suivi s'arrête. Doit être < attackDistance. */
	public double approachDistance = 2.5;
	/** Portée maximale d'attaque (oeil -> hitbox). */
	public double attackDistance = 3.0;
	/** Distance horizontale à laquelle une destination est considérée atteinte. */
	public double stopDistance = 0.35;
	/** En dessous de cette distance, le sprint est coupé et le freinage anticipé. */
	public double slowDistance = 2.5;

	// ========================================
	// ROTATION (degrés par tick)
	// ========================================
	public float minYawSpeed = 2.0f;
	public float maxYawSpeed = 25.0f;
	public float minPitchSpeed = 1.5f;
	public float maxPitchSpeed = 15.0f;
	/** Fraction de l'écart restant corrigée par tick (avant bornage min/max) : donne le ralentissement progressif. */
	public float rotationEaseFactor = 0.35f;
	public float alignToleranceYaw = 6.0f;
	public float alignTolerancePitch = 8.0f;

	// ========================================
	// COMBAT
	// ========================================
	public int attackCooldownTicks = 10;
	/** Force d'attaque vanilla minimale (0..1) avant de frapper. */
	public float minAttackStrength = 0.9f;

	// ========================================
	// MOUVEMENT / PATHFINDING
	// ========================================
	public boolean useSprint = true;
	public int pathMaxNodes = 1500;
	public int pathRecomputeIntervalTicks = 20;
	/** Durée maximale d'un déplacement vers une position avant abandon. */
	public int moveTimeoutTicks = 200;
	/** Fenêtre (ticks) de détection de blocage ; 3 fenêtres consécutives sans progrès = bloqué. */
	public int stuckWindowTicks = 20;

	// ========================================
	// POSITIONNEMENT
	// ========================================
	/** Distance (blocs) des positions candidates autour de la mécanique. */
	public int positionRingRadius = 2;
	/** Une position candidate ne doit pas être plus loin que ça de la cible de combat. */
	public double positionCombatMaxDistance = 6.0;
	/** Rayon horizontal autour de la mécanique dans lequel un Y inférieur est considéré "directement dessous". */
	public double underMechanicRadius = 1.5;
	public boolean allowAbovePosition = true;
	public int maxRepositionAttempts = 3;

	// ========================================
	// SCOREBOARD / SLAYER
	// ========================================
	/** false : le module ne vérifie plus le scoreboard et agit dès qu'il est activé. */
	public boolean requireSlayer = true;
	public String slayerScoreboardKeyword = "slayer";
	public int scoreboardRefreshTicks = 10;

	// ========================================
	// MÉCANIQUE AU SOL (détection par bloc, à confirmer par observation en jeu)
	// ========================================
	public String mechanicBlockId = "minecraft:beacon";
	public int mechanicScanRadius = 10;
	public int mechanicScanHalfHeight = 3;
	public int mechanicScanIntervalTicks = 5;

	private ModConfig() {}

	public static ModConfig get() {
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("automod.json");
	}

	public static void load() {
		Path file = file();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Config illisible, valeurs par défaut utilisées", e);
			}
		}
		save(); // réécrit le fichier pour y ajouter les nouveaux champs
	}

	public static void save() {
		try (Writer writer = Files.newBufferedWriter(file())) {
			GSON.toJson(instance, writer);
		} catch (IOException e) {
			LOGGER.warn("Impossible d'écrire la config", e);
		}
	}
}
