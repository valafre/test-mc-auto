package com.valafre.automod.modules.slayer.voidgloom;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.gui.config.ConfigPageBuilder;
import com.valafre.automod.gui.config.ConfigTab;
import com.valafre.automod.gui.config.GuiModule;

import java.util.List;

/** Description de l'interface du module Voidgloom : chaque réglage est lié à un vrai champ de {@link ModConfig}. */
public final class VoidgloomGuiModule implements GuiModule {

	private static ModConfig c() {
		return ModConfig.get();
	}

	@Override
	public String moduleId() {
		return VoidgloomModule.ID;
	}

	@Override
	public String icon() {
		return "module_voidgloom";
	}

	@Override
	public List<ConfigTab> tabs() {
		return List.of(
			new ConfigTab("general", "Général", "icon_tab_general", VoidgloomGuiModule::general),
			new ConfigTab("targets", "Cibles", "icon_tab_target", VoidgloomGuiModule::targets),
			new ConfigTab("combat", "Combat", "icon_tab_combat", VoidgloomGuiModule::combat),
			new ConfigTab("move", "Mouvement", "icon_tab_move", VoidgloomGuiModule::movement),
			new ConfigTab("mechanics", "Mécaniques", "icon_tab_mechanics", VoidgloomGuiModule::mechanics),
			new ConfigTab("failsafe", "Failsafes", "icon_tab_failsafe", VoidgloomGuiModule::failsafes));
	}

	// ========================================
	// GÉNÉRAL
	// ========================================

	private static void general(ConfigPageBuilder b) {
		b.card("Détection du boss", "Comment le module reconnaît le Voidgloom")
			.toggle("Exiger un Slayer actif", "Ne fonctionne que si le scoreboard indique un Slayer en cours",
				() -> c().requireSlayer, v -> c().requireSlayer = v)
			.toggle("Seulement mon boss", "Ignore les boss invoqués par d'autres joueurs",
				() -> c().onlyOwnBoss, v -> c().onlyOwnBoss = v)
			.text("Pseudo du propriétaire", "Vide = votre pseudo", () -> c().bossOwnerName, v -> c().bossOwnerName = v.trim(), "votre pseudo")
			.text("Mot-clé du nom", "Texte cherché dans le nom de l'entité", () -> c().voidgloomNameKeyword,
				v -> c().voidgloomNameKeyword = v.trim(), "voidgloom")
			.number("Niveau requis", "0 = niveau ignoré", () -> c().voidgloomRequiredLevel, v -> c().voidgloomRequiredLevel = (int) v, 0, 500, 1, 0)
			.toggle("Nom porté par un ArmorStand", "Accepte le nom affiché par un ArmorStand posé sur l'Enderman",
				() -> c().allowArmorStandNameplate, v -> c().allowArmorStandNameplate = v);
		b.card("Affichage")
			.toggle("Mode debug", "Détails supplémentaires dans le HUD et les messages", () -> c().debugMode, v -> c().debugMode = v);
	}

	// ========================================
	// CIBLES
	// ========================================

	private static void targets(ConfigPageBuilder b) {
		b.card("Farm des mobs", "Mobs tués en attendant l'apparition du boss")
			.toggle("Farmer les mobs", "Active la chasse aux mobs tant qu'aucun boss n'est présent", () -> c().farmMobs, v -> c().farmMobs = v)
			.toggle("Enderman", null, () -> c().farmsMobType("enderman"), v -> c().setFarmMobType("enderman", v))
			.toggle("Zealot", null, () -> c().farmsMobType("zealot"), v -> c().setFarmMobType("zealot", v))
			.toggle("Voidling Fanatic", null, () -> c().farmsMobType("voidling fanatic"), v -> c().setFarmMobType("voidling fanatic", v))
			.toggle("Accepter les mobs perchés", "Cibles en hauteur, difficiles à atteindre", () -> c().acceptElevatedMobs, v -> c().acceptElevatedMobs = v)
			.slider("Angle de vue des mobs", "Ignore les mobs apparus derrière vous au-delà de cet angle (180 = tout autour)",
				() -> c().farmViewAngleDeg, v -> c().farmViewAngleDeg = (float) v, 60, 180, 5, 0, "°")
			.toggle("Préférer les mobs à l'écran", "Favorise les cibles visibles", () -> c().preferOnScreen, v -> c().preferOnScreen = v);
		b.card("Portées")
			.slider("Rayon de farm", "Distance de recherche des mobs", () -> c().farmSearchRange, v -> c().farmSearchRange = v, 8, 48, 2, 0, " m")
			.slider("Rayon du boss", "Distance de recherche du boss", () -> c().targetSearchRange, v -> c().targetSearchRange = v, 4, 48, 2, 0, " m");
		b.card("Abandon d'une cible", "Changer de cible quand ça n'avance pas")
			.slider("Délai de kill", "Ticks avant d'abandonner un mob sans dégâts", () -> c().mobKillTimeoutTicks, v -> c().mobKillTimeoutTicks = (int) v, 20, 400, 10, 0, " t")
			.slider("Joueur bloqué", "Ticks sur place avant de changer de cible", () -> c().stuckSkipTicks, v -> c().stuckSkipTicks = (int) v, 20, 400, 10, 0, " t")
			.slider("Sans progrès", "Ticks sans progression avant de changer de cible", () -> c().noProgressTicks, v -> c().noProgressTicks = (int) v, 20, 400, 10, 0, " t");
	}

	// ========================================
	// COMBAT
	// ========================================

	private static void combat(ConfigPageBuilder b) {
		b.card("Distances")
			.slider("Portée d'attaque", "Distance à partir de laquelle on frappe", () -> c().attackDistance, v -> c().attackDistance = v, 2, 6, 0.25, 2, " m")
			.slider("Distance d'approche", "Distance visée en s'approchant",
				() -> c().approachDistance, v -> c().approachDistance = Math.min(v, c().attackDistance - 0.1), 1, 5.9, 0.25, 2, " m")
			.slider("Distance minimale", "On ne se rapproche pas plus que ça", () -> c().combatMinDistance, v -> c().combatMinDistance = v, 0.8, 3, 0.1, 1, " m");
		b.card("Cadence de clic")
			.slider("CPS minimum", null, () -> c().minCps, v -> { c().minCps = v; c().maxCps = Math.max(c().maxCps, v); }, 1, 20, 0.5, 1, "")
			.slider("CPS maximum", null, () -> c().maxCps, v -> { c().maxCps = v; c().minCps = Math.min(c().minCps, v); }, 1, 20, 0.5, 1, "")
			.toggle("Pauses de clic", "Petites pauses irrégulières", () -> c().attackPauses, v -> c().attackPauses = v);
		b.card("Déplacement en combat")
			.toggle("Sprint", null, () -> c().useSprint, v -> c().useSprint = v)
			.toggle("S'accroupir sur le boss", null, () -> c().sneakOnBoss, v -> c().sneakOnBoss = v)
			.toggle("Ralentir dans les virages", null, () -> c().turnSlowdown, v -> c().turnSlowdown = v);
		b.card("Arme (katana)")
			.text("Mot-clé de l'arme", "Premier objet de la hotbar dont le nom contient ce mot", () -> c().weaponKeyword, v -> c().weaponKeyword = v.trim(), "katana")
			.toggle("Clic droit pendant le boss", "Utilise la capacité de l'arme à intervalle tiré au hasard", () -> c().katanaClick, v -> c().katanaClick = v)
			.slider("Intervalle minimum", null, () -> c().katanaClickMinTicks,
				v -> { c().katanaClickMinTicks = (int) v; c().katanaClickMaxTicks = Math.max(c().katanaClickMaxTicks, (int) v); }, 20, 200, 1, 0, " t")
			.slider("Intervalle maximum", null, () -> c().katanaClickMaxTicks,
				v -> { c().katanaClickMaxTicks = (int) v; c().katanaClickMinTicks = Math.min(c().katanaClickMinTicks, (int) v); }, 20, 200, 1, 0, " t");
	}

	// ========================================
	// MOUVEMENT (+ caméra)
	// ========================================

	private static void movement(ConfigPageBuilder b) {
		b.card("Chemin")
			.number("Nœuds max du chemin", "Limite de recherche du pathfinding", () -> c().pathMaxNodes, v -> c().pathMaxNodes = (int) v, 100, 6000, 100, 0)
			.slider("Chute maximale", "Descend de plusieurs blocs plutôt que de faire un long détour (monter reste limité à 1 bloc)",
				() -> c().maxDropBlocks, v -> c().maxDropBlocks = (int) v, 1, 12, 1, 0, " blocs")
			.slider("Recalcul du chemin", "Ticks entre deux recalculs", () -> c().pathRecomputeIntervalTicks, v -> c().pathRecomputeIntervalTicks = (int) v, 2, 40, 1, 0, " t")
			.slider("Délai de déplacement", "Ticks avant d'abandonner un trajet", () -> c().moveTimeoutTicks, v -> c().moveTimeoutTicks = (int) v, 40, 600, 10, 0, " t")
			.slider("Transition entre cibles", "Ticks de transition maximum", () -> c().transitionMaxTicks, v -> c().transitionMaxTicks = (int) v, 0, 20, 1, 0, " t");
		b.card("Navigation", "Trajectoires prédictives : le relief est analysé avant d'y arriver")
			.slider("Réévaluation locale", "Ticks entre deux analyses de trajectoire (2 = 100 ms)", () -> c().navReplanTicks, v -> c().navReplanTicks = (int) v, 1, 10, 1, 0, " t")
			.slider("Horizon local", "Distance analysée devant le joueur", () -> c().navLocalHorizonBlocks, v -> c().navLocalHorizonBlocks = v, 2, 8, 0.25, 2, " m")
			.slider("Pas de simulation", "Distance entre deux points simulés", () -> c().navSampleDistance, v -> c().navSampleDistance = v, 0.15, 0.5, 0.05, 2, " m")
			.slider("Marge de sécurité", "Marge de chaque côté du corps", () -> c().navSafetyMargin, v -> c().navSafetyMargin = v, 0, 0.3, 0.02, 2, " m")
			.slider("Distance sûre minimale", "Une trajectoire doit pouvoir avancer au moins ça", () -> c().navMinSafeDistance, v -> c().navMinSafeDistance = v, 0.6, 3, 0.1, 1, " m")
			.slider("Montée maximale", "Dénivelé franchi en sautant (chemin global)", () -> c().navMaxClimb, v -> c().navMaxClimb = v, 0.6, 1.25, 0.05, 2, " m")
			.slider("Hauteur de saut", "Dénivelé franchi en sautant (trajectoires locales)", () -> c().navJumpHeight, v -> c().navJumpHeight = v, 0.6, 1.25, 0.05, 2, " m")
			.slider("Poids de la marge", "Préférer les trajectoires qui restent loin des parois", () -> c().navClearanceWeight, v -> c().navClearanceWeight = v, 0, 4, 0.1, 1, "")
			.slider("Poids de la progression", null, () -> c().navProgressWeight, v -> c().navProgressWeight = v, 0, 5, 0.1, 1, "")
			.slider("Pénalité de virage", "Stabilité du cap (évite gauche/droite)", () -> c().navTurnPenalty, v -> c().navTurnPenalty = v, 0, 4, 0.1, 1, "")
			.slider("Orientation vers la cible", null, () -> c().navTargetAlignWeight, v -> c().navTargetAlignWeight = v, 0, 4, 0.1, 1, "")
			.slider("Pénalité d'impasse", null, () -> c().navDeadEndPenalty, v -> c().navDeadEndPenalty = v, 0, 10, 0.5, 1, "")
			.slider("Poids de visibilité (combat)", "Garder la cible en vue en la contournant", () -> c().navCombatVisibilityWeight, v -> c().navCombatVisibilityWeight = v, 0, 5, 0.1, 1, "")
			.toggle("Lissage du chemin", "Supprime les points inutiles quand le tronçon est praticable", () -> c().navPathSmoothing, v -> c().navPathSmoothing = v)
			.slider("Points regardés en avant", "Vise le point le plus lointain directement franchissable", () -> c().navLookAheadNodes, v -> c().navLookAheadNodes = (int) v, 1, 16, 1, 0, "")
			.slider("Mémoire des échecs", "Durée du surcoût d'une case refusée", () -> c().navFailureMemoryMs, v -> c().navFailureMemoryMs = (int) v, 500, 10000, 250, 0, " ms");
		b.card("Calcul asynchrone", "Les calculs lourds tournent dans un thread dédié, jamais sur le thread Minecraft")
			.slider("Budget du chemin global", "Temps max du A* dans le worker (résultat partiel au-delà)", () -> c().navMaxComputeTimeMs, v -> c().navMaxComputeTimeMs = v, 5, 100, 1, 0, " ms")
			.slider("Budget du calcul local", "Temps max de la planification locale", () -> c().navLocalComputeTimeMs, v -> c().navLocalComputeTimeMs = v, 2, 30, 1, 0, " ms")
			.slider("Rayon du snapshot global", "Zone copiée autour du joueur pour le chemin", () -> c().navSnapshotRadius, v -> c().navSnapshotRadius = (int) v, 12, 64, 2, 0, " m")
			.slider("Marge du snapshot", "Zone ajoutée autour du joueur et du but", () -> c().navSnapshotMargin, v -> c().navSnapshotMargin = (int) v, 4, 24, 1, 0, " m")
			.slider("Rayon du snapshot local", null, () -> c().navLocalSnapshotRadius, v -> c().navLocalSnapshotRadius = (int) v, 6, 20, 1, 0, " m")
			.slider("Budget du snapshot", "Temps max passé sur le thread Minecraft à copier la géométrie", () -> c().navSnapshotBudgetMs, v -> c().navSnapshotBudgetMs = v, 0.5, 8, 0.5, 1, " ms")
			.slider("Durée de vie du cache", "Ticks avant relecture d'une zone proche", () -> c().navTileTtlTicks, v -> c().navTileTtlTicks = (int) v, 2, 60, 1, 0, " t");
		b.card("Réinitialisation")
			.button("Navigation par défaut", "Remet tous les réglages de chemin, navigation et calcul asynchrone aux valeurs d'origine",
				"Réinitialiser", com.valafre.automod.gui.components.Button.Kind.DANGER, ModConfig::resetNavigation);
		b.card("Caméra", "Rotation lissée et naturelle")
			.toggle("Humaniser", "Légère variation de visée", () -> c().humanize, v -> c().humanize = v)
			.toggle("Rotation par image", "Applique la rotation à chaque image affichée", () -> c().smoothFrameRotation, v -> c().smoothFrameRotation = v)
			.toggle("Micro-mouvements", "Petit tremblement de la caméra", () -> c().camTremor, v -> c().camTremor = v)
			.slider("Amplitude du tremblement", null, () -> c().camTremorDeg, v -> c().camTremorDeg = (float) v, 0, 0.5, 0.01, 2, "°")
			.toggle("Coup d'œil", "Jette un œil à la cible suivante", () -> c().glance, v -> c().glance = v)
			.slider("Seuil du coup d'œil", "Fraction de PV de la cible", () -> c().glanceHealthFraction, v -> c().glanceHealthFraction = (float) v, 0, 1, 0.05, 2, "")
			.slider("Vitesse max", "Degrés par tick", () -> c().camPeakSpeedDeg, v -> c().camPeakSpeedDeg = (float) v, 6, 40, 1, 0, "°")
			.slider("Accélération max", null, () -> c().camMaxAccelDeg, v -> c().camMaxAccelDeg = (float) v, 2, 15, 0.5, 1, "°")
			.slider("Amortissement", null, () -> c().camDamping, v -> c().camDamping = (float) v, 0.4, 1, 0.05, 2, "")
			.slider("Anticipation", "Ticks de prédiction du mouvement", () -> c().aimLeadTicks, v -> c().aimLeadTicks = v, 0, 4, 0.5, 1, " t")
			.slider("Visée basse", "Fraction de la hauteur (depuis le bas)", () -> c().aimBandLowFraction,
				v -> c().aimBandLowFraction = Math.min(v, c().aimBandHighFraction), 0.1, 0.9, 0.05, 2, "")
			.slider("Visée haute", "Fraction de la hauteur (depuis le bas)", () -> c().aimBandHighFraction,
				v -> c().aimBandHighFraction = Math.max(v, c().aimBandLowFraction), 0.2, 1, 0.05, 2, "");
	}

	// ========================================
	// MÉCANIQUES
	// ========================================

	private static void mechanics(ConfigPageBuilder b) {
		b.card("Bloc de mécanique", "Bloc à toucher pendant le boss (ex. balise)")
			.text("Identifiant du bloc", "Ex. minecraft:beacon", () -> c().mechanicBlockId, v -> c().mechanicBlockId = v.trim(), "minecraft:beacon")
			.slider("Rayon de recherche", null, () -> c().mechanicScanRadius, v -> c().mechanicScanRadius = (int) v, 2, 24, 1, 0, " m")
			.slider("Demi-hauteur de recherche", null, () -> c().mechanicScanHalfHeight, v -> c().mechanicScanHalfHeight = (int) v, 1, 12, 1, 0, " m")
			.slider("Intervalle de scan", null, () -> c().mechanicScanIntervalTicks, v -> c().mechanicScanIntervalTicks = (int) v, 1, 40, 1, 0, " t");
		b.card("Positionnement")
			.slider("Rayon de l'anneau", null, () -> c().positionRingRadius, v -> c().positionRingRadius = (int) v, 1, 6, 1, 0, " m")
			.slider("Distance de combat max", null, () -> c().positionCombatMaxDistance, v -> c().positionCombatMaxDistance = v, 3, 12, 0.5, 1, " m")
			.slider("Rayon sous la mécanique", null, () -> c().underMechanicRadius, v -> c().underMechanicRadius = v, 0.5, 4, 0.1, 1, " m")
			.toggle("Autoriser au-dessus", "Se placer au-dessus du bloc", () -> c().allowAbovePosition, v -> c().allowAbovePosition = v)
			.slider("Essais de repositionnement", null, () -> c().maxRepositionAttempts, v -> c().maxRepositionAttempts = (int) v, 1, 8, 1, 0, "")
			.slider("Distance d'arrivée", null, () -> c().positionArriveDistance, v -> c().positionArriveDistance = v, 0.2, 2, 0.1, 1, " m")
			.slider("Distance de dérive", null, () -> c().positionDriftDistance, v -> c().positionDriftDistance = v, 0.5, 4, 0.1, 1, " m");
	}

	// ========================================
	// FAILSAFES (survie)
	// ========================================

	private static void failsafes(ConfigPageBuilder b) {
		b.card("Soin (Wand)")
			.toggle("Soin automatique", "Utilise la Wand quand les PV sont bas", () -> c().healEnabled, v -> c().healEnabled = v)
			.slider("Seuil de soin", "Pourcentage de PV", () -> c().healThresholdPercent, v -> c().healThresholdPercent = (float) v, 5, 95, 5, 0, " %")
			.text("Mot-clé de la Wand", null, () -> c().healWandKeyword, v -> c().healWandKeyword = v.trim(), "wand")
			.slider("Délai entre deux soins", null, () -> c().wandCooldownTicks, v -> c().wandCooldownTicks = (int) v, 5, 200, 5, 0, " t");
		b.card("Orb")
			.toggle("Orb pendant le boss", "Pose l'Orb si elle n'est plus active", () -> c().orbEnabled, v -> c().orbEnabled = v)
			.text("Mot-clé de l'Orb", null, () -> c().orbKeyword, v -> c().orbKeyword = v.trim(), "orb")
			.text("Textes de détection", "Séparés par des virgules (ArmorStand de l'Orb)", () -> c().orbStandKeywords, v -> c().orbStandKeywords = v.trim(), "orb,radiant")
			.slider("Rayon de détection", null, () -> c().orbSearchRadius, v -> c().orbSearchRadius = v, 4, 40, 1, 0, " m")
			.slider("Délai avant de replacer", null, () -> c().orbMinReplaceTicks, v -> c().orbMinReplaceTicks = (int) v, 20, 600, 20, 0, " t")
			.slider("Durée supposée", "Replace l'Orb après ce délai même sans détection", () -> c().orbAssumedDurationTicks, v -> c().orbAssumedDurationTicks = (int) v, 100, 1200, 50, 0, " t");
	}
}
