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
	private static final int CURRENT_VERSION = 4;
	private static ModConfig instance = new ModConfig();

	/** Sert à migrer les anciens fichiers de config dont les valeurs par défaut ont changé. */
	public int configVersion = 0;

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
	/** Intervalle de recherche quand aucune cible n'est suivie (enchaînement des kills). */
	public int idleSearchIntervalTicks = 1;
	/** Sur certains serveurs le nom est porté par un ArmorStand posé sur l'Enderman : on l'accepte aussi. */
	public boolean allowArmorStandNameplate = true;
	/** Ne cibler que le boss invoqué par soi (ligne "Spawned by: pseudo" du nametag). */
	public boolean onlyOwnBoss = true;
	/** Pseudo du propriétaire ; vide = pseudo du joueur connecté. */
	public String bossOwnerName = "";
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
	/** La visée reste dans la partie HAUTE de la hitbox : à partir de cette fraction de la hauteur (0.55 = les 45 % du haut). */
	public double aimBandMinFraction = 0.55;
	/** Amplitude de la dérive du point visé, en fraction de la taille de la hitbox. */
	public double aimOffsetFraction = 0.25;
	/** Variation (+/-) de la vitesse de rotation propre à chaque cible. */
	public float rotationSpeedVariation = 0.2f;
	public int reactionDelayMinTicks = 0;
	public int reactionDelayMaxTicks = 2;

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
	/** Étale chaque pas de rotation sur les images du tick (caméra fluide à haut FPS au lieu de 20 sauts/s). */
	public boolean smoothFrameRotation = true;
	public float alignToleranceYaw = 6.0f;
	public float alignTolerancePitch = 8.0f;

	// ========================================
	// COMBAT
	// ========================================
	/** Cadence d'attaque : un CPS est tiré entre min et max à chaque coup (moyenne respectée au tick près). */
	public double minCps = 10.0;
	public double maxCps = 13.0;
	/** Force d'attaque vanilla minimale (0..1) avant de frapper ; 0 = désactivé (nécessaire pour 10+ CPS). */
	public float minAttackStrength = 0.0f;

	// ========================================
	// MOUVEMENT / PATHFINDING
	// ========================================
	public boolean useSprint = true;
	/** Pendant le combat, le joueur bouge en continu (strafe autour de la cible) au lieu de s'arrêter pour frapper. */
	public boolean strafeInCombat = true;
	/** En dessous de cette distance (oeil -> hitbox) on recule légèrement pour garder la portée. */
	public double combatMinDistance = 1.4;
	public int pathMaxNodes = 1500;
	public int pathRecomputeIntervalTicks = 10;
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
	/** Cible d'un Enderman sans ligne de vue depuis ce nombre de ticks : jugée inaccessible et abandonnée (pas pour le boss). */
	public int unreachableAfterTicks = 60;
	/** Durée pendant laquelle une cible abandonnée est ignorée. */
	public int skipTargetTicks = 600;
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
		migrate();
		save(); // réécrit le fichier pour y ajouter les nouveaux champs
	}

	/** v2 : enchaînement des cibles plus rapide et plus de blocage par la force d'attaque vanilla (nécessaire pour 10-13 CPS). */
	private static void migrate() {
		ModConfig c = instance;
		if (c.configVersion < 2) {
			c.reactionDelayMinTicks = 1;
			c.reactionDelayMaxTicks = 5;
			c.idleSearchIntervalTicks = 2;
			c.minAttackStrength = 0.0f;
		}
		if (c.configVersion < 3) { // enchaînement quasi immédiat (~2-3 ticks)
			c.reactionDelayMinTicks = 0;
			c.reactionDelayMaxTicks = 2;
			c.idleSearchIntervalTicks = 1;
		}
		if (c.configVersion < 4) { // navigation : recalcul de chemin plus fréquent (cibles mobiles)
			c.pathRecomputeIntervalTicks = 10;
		}
		c.configVersion = CURRENT_VERSION;
	}

	public static void save() {
		try (Writer writer = Files.newBufferedWriter(file())) {
			GSON.toJson(instance, writer);
		} catch (IOException e) {
			LOGGER.warn("Impossible d'écrire la config", e);
		}
	}
}
