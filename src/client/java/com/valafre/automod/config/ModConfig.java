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
	private static final int CURRENT_VERSION = 10;
	private static ModConfig instance = new ModConfig();

	/** Sert à migrer les anciens fichiers de config dont les valeurs par défaut ont changé. */
	public int configVersion = 0;

	// ========================================
	// GÉNÉRAL
	// ========================================
	public boolean debugMode = false;
	/** Affichage en jeu : une ligne courte ; le détail (tâche, touches, survie) n'apparaît qu'en mode debug. */
	public boolean hudEnabled = true;

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
	// SURVIE : WAND DE SOIN + ORB
	// ========================================
	/** Arme équipée automatiquement pendant le combat : premier objet de la hotbar dont le nom contient ce mot (vide = désactivé). */
	public String weaponKeyword = "katana";
	public boolean healEnabled = true;
	/** Mots présents dans le nom de l'objet (tous tiers confondus, insensible à la casse). */
	public String healWandKeyword = "wand";
	/** Utilise la Wand quand les PV passent sous ce pourcentage. */
	public float healThresholdPercent = 50.0f;
	/** Délai minimal entre deux utilisations de la Wand tant que les PV restent sous le seuil. */
	public int wandCooldownTicks = 30;
	public boolean orbEnabled = true;
	public String orbKeyword = "orb";
	/** Textes (séparés par des virgules) d'un ArmorStand qui prouve que l'Orb est posée ; à vérifier avec la touche I. */
	public String orbStandKeywords = "orb,radiant,mana flux,overflux";
	public double orbSearchRadius = 20.0;
	/** Délai minimal avant de replacer l'Orb si elle n'est plus détectée. */
	public int orbMinReplaceTicks = 100;
	/** Repli : l'Orb est replacée après cette durée même sans détection (30 s). */
	public int orbAssumedDurationTicks = 600;

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
	public double aimBandLowFraction = 0.45;
	/** Limite haute de la bande, en fraction de la hauteur depuis le bas : 0.60 = 40 % de marge depuis le haut. */
	public double aimBandHighFraction = 0.60;
	/** Anticipation : on vise la position de la cible dans X ticks (selon sa vitesse) pour ne pas être en retard sur une cible mobile. */
	public double aimLeadTicks = 2.0;
	/** Marge ajoutée à la hitbox pour décider qu'on "est dessus" (le serveur ne vérifie que la distance). */
	public double hitboxMargin = 0.15;

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
	public float minYawSpeed = 3.0f;
	public float maxYawSpeed = 40.0f;
	public float minPitchSpeed = 3.0f;
	public float maxPitchSpeed = 30.0f;
	/** Fraction de l'écart restant corrigée par tick (avant bornage min/max) : donne le ralentissement progressif. */
	public float rotationEaseFactor = 0.45f;
	/** Étale chaque pas de rotation sur les images du tick (caméra fluide à haut FPS au lieu de 20 sauts/s). */
	public boolean smoothFrameRotation = true;
	/** Caméra naturelle : vitesse angulaire maximale (degrés/tick) ; 18 = environ 360 degrés/s en crête. */
	public float camPeakSpeedDeg = 18.0f;
	/** Variation maximale de la vitesse de la caméra par tick (degrés/tick²) : démarrage et arrêt progressifs, jamais brusques. */
	public float camMaxAccelDeg = 6.5f;
	/** Amortissement du ressort de caméra : 1 = aucun dépassement (robotique) ; 0.7 = léger dépassement puis retour, comme une vraie main. */
	public float camDamping = 0.7f;
	/** Micro-mouvements lents de la caméra (suite de sinusoïdes déphasées, pas de bruit aléatoire saccadé). Amplitude en degrés. */
	public boolean camTremor = true;
	public float camTremorDeg = 0.08f;
	/** Quand la cible actuelle est presque morte, la caméra dérive légèrement vers la suivante (sans quitter la hitbox actuelle). */
	public boolean glance = true;
	public float glanceHealthFraction = 0.25f;
	/** Le joueur lève le pied (plus de sprint) avant un virage serré du chemin. */
	public boolean turnSlowdown = true;
	/** Courtes pauses de clic de temps en temps (rythme humain), qui s'ajoutent à la cadence 10-13 CPS. */
	public boolean attackPauses = true;
	/** Durée minimale (ticks) d'un mouvement de caméra, même pour un tout petit angle. */
	public float camMinSettleTicks = 2.0f;
	/** Ticks ajoutés par doublement de (angle / taille apparente de la cible) : grand angle ou petite cible = plus long. */
	public float camSettleSlope = 1.2f;
	/** Zone de tolérance (fraction de la taille apparente de la cible) : on y entre sous camLockIn, on en sort au-dessus de camLockOut. */
	public float camLockIn = 0.3f;
	public float camLockOut = 0.7f;
	/** Dans la zone de tolérance, part du mouvement de la cible qui est reproduite (suivi sans corrections). */
	public float camLockedFollow = 0.7f;
	/** Attente maximale (ticks) avant d'engager une nouvelle cible ; proportionnelle à l'angle à tourner (1 tick par 45 degrés). */
	public int transitionMaxTicks = 5;
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
	/** Sneak (accroupi) pendant le combat contre le boss. Ignoré si l'option vanilla "sneak en bascule" est active. */
	public boolean sneakOnBoss = true;
	/** Enderman normal : abandonné si toujours en vie X ticks après le premier contact (portée + ligne de vue). Jamais pour le boss. */
	public int mobKillTimeoutTicks = 60;
	/** Enderman normal : abandonné si jamais atteint après X ticks (trop loin, plateforme inaccessible). */
	public int mobAcquireTimeoutTicks = 200;
	/** Enderman normal : abandonné si le joueur reste quasi immobile (moins de 0,5 bloc) pendant X ticks. */
	public int stuckSkipTicks = 60;
	/** Enderman normal : on vérifie toutes les X ticks s'il y en a un nettement plus proche que la cible poursuivie. */
	public int retargetIntervalTicks = 20;
	/** Il faut qu'il soit plus proche d'au moins cette distance (blocs) pour changer de cible (évite les allers-retours). */
	public double retargetMarginBlocks = 1.0;
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
	/** Distance horizontale (blocs) à laquelle la position choisie est considérée atteinte : le joueur doit être VRAIMENT dessus. */
	public double positionArriveDistance = 0.6;
	/** Pendant qu'on tient la position, si on s'en écarte de plus que ça (recul, knockback), on y retourne. */
	public double positionDriftDistance = 1.3;

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

	/**
	 * Migre les anciens fichiers de config quand des valeurs par défaut changent. Les versions successives ont, entre autres :
	 * supprimé le blocage par la force d'attaque vanilla (nécessaire pour 10-13 CPS), accéléré l'enchaînement des cibles,
	 * élargi la détection du beacon et désactivé le strafe continu. La v8 passe à la caméra naturelle et retire le hasard.
	 */
	private static void migrate() {
		ModConfig c = instance;
		if (c.configVersion < 2) {
			c.minAttackStrength = 0.0f;
		}
		if (c.configVersion < 4) {
			c.pathRecomputeIntervalTicks = 10;
		}
		if (c.configVersion < 6) {
			c.mechanicScanRadius = 12;
			c.mechanicScanHalfHeight = 4;
		}
		if (c.configVersion < 8) {
			c.idleSearchIntervalTicks = 1;
			c.camPeakSpeedDeg = 14.0f;
		}
		if (c.configVersion < 9) { // visée plus vive (les nouveaux champs de bande prennent leurs défauts)
			c.camPeakSpeedDeg = 18.0f;
			c.camMaxAccelDeg = 6.5f;
		}
		if (c.configVersion < 10) { // bande de visée 45 % -> 60 % (40 % de marge en haut)
			c.aimBandLowFraction = 0.45;
			c.aimBandHighFraction = 0.60;
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
