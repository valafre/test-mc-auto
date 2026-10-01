package com.valafre.automod.modules.slayer.voidgloom;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.AbstractModule;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.HudInfo;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.core.StateMachine;
import com.valafre.automod.core.TaskPriority;
import com.valafre.automod.core.TaskStatus;
import com.valafre.automod.movement.PositionController;
import com.valafre.automod.movement.RotationController;
import com.valafre.automod.targeting.TargetInfo;
import com.valafre.automod.targeting.TargetSelector;
import com.valafre.automod.task.AttackTargetTask;
import com.valafre.automod.task.LookAtTask;
import com.valafre.automod.task.MoveToPositionTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.EnderMan;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Module Voidgloom : ne contient que la logique propre au boss (quand suivre, quand se repositionner, quand frapper).
 * Il ne touche jamais aux touches ni à la rotation : il soumet des tâches au moteur générique.
 */
public final class VoidgloomModule extends AbstractModule {

	public static final String ID = "voidgloom";
	private static final int MODULE_PRIORITY = 50;
	/** Contact avec la face à l'arrivée (quelques cm) ; pendant le maintien on tolère un peu plus avant de se recoller. */
	private static final double OFF_SCREEN_PENALTY = 6.0;
	private static final double NO_SIGHT_PENALTY = 4.0;
	private static final double TOUCH_CHECK = 0.1;
	private static final double TOUCH_HOLD_TOLERANCE = 0.3;

	private final StateMachine<VoidgloomState> fsm = new StateMachine<>("Voidgloom", VoidgloomState.IDLE);
	private final GroundMechanicDetector mechanics = new GroundMechanicDetector();
	private final Set<BlockPos> failedPositions = new HashSet<>();

	private EnderMan target;
	private int searchTimer;

	private BlockPos mechanic;          // mécanique en cours de traitement
	private BlockPos handledMechanic;   // déjà traitée : ne pas redéclencher tant qu'elle est présente
	private MoveToPositionTask moveTask;
	private int repositionAttempts;
	private boolean slayerOk;
	private int endermenSeen;
	private int bossesSeen;
	private int mobsSeen;
	private int kills;
	private long enabledAtMs;
	private int hudRefreshTimer;
	private String hudTargetName = "";
	private String hudTier = "";
	private double hudHpRatio = -1;
	private double hudHp = -1;
	private double hudMaxHp = -1;
	private int foreignBosses;
	private boolean targetIsBoss;       // false : Enderman normal farmé pour faire apparaître le boss
	private int reactionTicks;          // délai de réaction humain avant d'agir sur une nouvelle cible
	private int bossCheckTimer;
	private int retargetAccum;
	private boolean pressMode;          // la position choisie doit TOUCHER une face du beacon
	private BlockPos moveDestination;   // case de repositionnement choisie (pour vérifier qu'on y est VRAIMENT collé)
	private AttackTargetTask attackTask;
	private boolean attackTaskHold;
	private EnderMan attackTaskTarget;
	private double engageStartRatio = -1;    // PV (0..1) de la cible au début de la fenêtre de combat ; -1 = inconnu
	private int engagedTicks = -1;    // ticks depuis le premier contact (portée + ligne de vue) ; -1 = pas encore au contact
	private net.minecraft.world.phys.Vec3 stuckAnchor; // position de référence pour détecter un joueur bloqué
	private int stuckTicks;
	private double bestDistance = Double.MAX_VALUE;   // plus petite distance atteinte vers la cible (suivi de progression)
	private int noProgressTicks;
	private int acquiredTicks;         // ticks depuis l'acquisition de la cible
	private int noLosTicks;            // ticks consécutifs sans ligne de vue sur la cible
	private long clock;
	private final java.util.Map<Integer, Long> skipped = new java.util.HashMap<>(); // cibles abandonnées (id -> fin d'exclusion)
	private boolean holdPosition;       // vrai après un repositionnement tant que la mécanique existe

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String status() {
		if (!slayerOk) {
			return "en attente : \"" + ModConfig.get().slayerScoreboardKeyword + "\" absent du scoreboard";
		}
		String cible = target == null ? " (aucune cible)" : targetIsBoss ? " (cible : BOSS)" : " (cible : Enderman)";
		return fsm.current() + cible + " | Enderman vus: " + endermenSeen + ", à farmer: " + mobsSeen + ", Voidgloom: " + bossesSeen
			+ (foreignBosses > 0 ? " (+" + foreignBosses + " d'autres joueurs ignorés)" : "");
	}

	@Override
	public String shortStatus() {
		if (!slayerOk) {
			return "attente Slayer";
		}
		return fsm.current() + (target == null ? "" : targetIsBoss ? " · boss" : " · mob");
	}

	@Override
	public String displayName() {
		return "Voidgloom";
	}

	@Override
	public String description() {
		return "Assistant pour le Slayer Voidgloom";
	}

	@Override
	public HudInfo hudInfo() {
		long uptime = isEnabled() ? net.minecraft.util.Util.getMillis() - enabledAtMs : 0;
		return new HudInfo(readableState(), hudTargetName, hudTier, hudHpRatio, hudHp, hudMaxHp, kills, uptime);
	}

	/** État du combat en français, pour le HUD. */
	private String readableState() {
		if (!slayerOk) {
			return "En attente";
		}
		return switch (fsm.current()) {
			case IDLE, SLAYER_CHECK -> "Inactif";
			case SEARCHING_TARGET, TARGET_LOST -> "Recherche";
			case FOLLOWING_TARGET -> "Approche";
			case ALIGNING -> "Visée";
			case ATTACKING -> "Attaque";
			case GROUND_MECHANIC_DETECTED, CHOOSING_POSITION, REPOSITIONING, POSITION_REACHED -> "Mécanique";
			case STOPPING -> "Arrêt";
		};
	}

	/** Met à jour les valeurs d'affichage de la cible (nom, PV, palier) : appelé de temps en temps, pas à chaque image. */
	private void refreshHudInfo(Framework f) {
		if (target == null) {
			hudTargetName = "";
			hudTier = "";
			hudHpRatio = -1;
			hudHp = -1;
			hudMaxHp = -1;
			return;
		}
		var info = f.entityInfo().resolve(f.player().level(), target);
		hudTargetName = info.name();
		hudHp = info.hasHealth() ? info.health() : -1;
		hudMaxHp = info.hasHealth() ? info.maxHealth() : -1;
		hudHpRatio = info.hasHealth() && info.maxHealth() > 0 ? info.health() / info.maxHealth() : -1;
		hudTier = parseTier(info.name());
	}

	/** "Voidgloom Seraph IV" -> "T4" (chiffre romain final du nom) ; vide si absent. */
	private static String parseTier(String name) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(IV|V|I{1,3})$").matcher(name.trim());
		if (!m.find()) {
			return "";
		}
		return switch (m.group(1)) {
			case "I" -> "T1";
			case "II" -> "T2";
			case "III" -> "T3";
			case "IV" -> "T4";
			default -> "T5";
		};
	}

	@Override
	public int getPriority() {
		return MODULE_PRIORITY;
	}

	// ========================================
	// CYCLE DE VIE
	// ========================================

	@Override
	public void onEnable(Framework f) {
		resetState();
		kills = 0;
		enabledAtMs = net.minecraft.util.Util.getMillis();
		Debug.log("Voidgloom", () -> "Module activé");
	}

	@Override
	public void onDisable(Framework f) {
		fsm.transition(VoidgloomState.STOPPING);
		stopActions(f);
		resetState();
		Debug.log("Voidgloom", () -> "Module désactivé");
	}

	@Override
	public void onSafetyStop(Framework f) {
		resetState();
	}

	private void resetState() {
		target = null;
		noLosTicks = 0;
		engagedTicks = -1;
		engageStartRatio = -1;
		acquiredTicks = 0;
		stuckAnchor = null;
		stuckTicks = 0;
		bestDistance = Double.MAX_VALUE;
		noProgressTicks = 0;
		targetIsBoss = false;
		reactionTicks = 0;
		bossCheckTimer = 0;
		clearReposition();
		handledMechanic = null;
		searchTimer = 0;
		mechanics.reset();
		fsm.transition(VoidgloomState.IDLE);
	}

	/**
	 * Remet à zéro, D'UN COUP, tout l'état lié à la mécanique au sol. Ces champs doivent rester cohérents entre eux :
	 * holdPosition, pressMode et moveDestination n'ont de sens que si {@code mechanic != null}. Les effacer séparément
	 * (comme avant) laissait pressMode à true avec mechanic à null, d'où le crash dans isTouching().
	 */
	private void clearReposition() {
		mechanic = null;
		moveTask = null;
		moveDestination = null;
		pressMode = false;
		holdPosition = false;
		repositionAttempts = 0;
		failedPositions.clear();
	}

	private void stopActions(Framework f) {
		f.hints().setGlance(null);
		attackTask = null;
		f.tasks().cancelOwner(f, ID);
		f.movement().reset();
		f.rotation().cancel();
	}

	// ========================================
	// TICK
	// ========================================

	@Override
	public void onTick(Framework f) {
		// Performance : tant que Slayer n'est pas actif, aucune recherche d'entité ni de bloc n'est faite.
		slayerOk = !ModConfig.get().requireSlayer || f.slayer().isActive();
		if (!slayerOk) {
			if (!fsm.is(VoidgloomState.IDLE)) {
				fsm.transition(VoidgloomState.STOPPING);
				stopActions(f);
				resetState();
			}
			return;
		}
		clock++;
		// Survie intégrée : tourne à chaque tick, en parallèle du suivi/positionnement/combat (aucune interruption).
		f.support().tick(f, targetIsBoss && TargetSelector.isValid(target));
		fsm.tick();
		switch (fsm.current()) {
			case IDLE -> fsm.transition(VoidgloomState.SLAYER_CHECK);
			case SLAYER_CHECK -> {
				Debug.log("Mod", () -> "Slayer détecté");
				fsm.transition(VoidgloomState.SEARCHING_TARGET);
			}
			case SEARCHING_TARGET -> searchTarget(f);
			case TARGET_LOST -> {
				stopActions(f);
				logDropped(f, target, targetIsBoss, dropReason);
				dropReason = "inconnue";
				target = null;
				targetIsBoss = false;
				clearReposition();
				handledMechanic = null;
				mechanics.reset();
				searchTimer = 0; // chercher la cible suivante tout de suite, dans ce même tick
				fsm.transition(VoidgloomState.SEARCHING_TARGET);
				searchTarget(f);
			}
			case STOPPING -> {
				stopActions(f);
				resetState();
			}
			default -> combatTick(f);
		}
	}

	// ========================================
	// TARGETING
	// ========================================

	private void searchTarget(Framework f) {
		ModConfig cfg = ModConfig.get();
		if (searchTimer-- > 0) {
			return; // recherche d'entités espacée
		}
		searchTimer = Math.max(1, cfg.idleSearchIntervalTicks) - 1;
		PlayerState ps = f.player();
		VoidgloomTarget.Result result = VoidgloomTarget.find(f);
		updateCounters(result);
		double range = cfg.farmMobs ? Math.max(cfg.targetSearchRange, cfg.farmSearchRange) : cfg.targetSearchRange;
		// Le boss est toujours prioritaire ; sinon on farme l'Enderman normal le plus proche pour le faire apparaître.
		EnderMan boss = f.targetSelector().select(result.bosses(), ps.position(), null, range);
		EnderMan picked = boss != null ? boss : f.targetSelector().selectBy(reachable(f, result.mobs()), m -> approachCost(f, ps, m));
		if (picked != null) {
			target = picked;
			noLosTicks = 0;
		engagedTicks = -1;
		engageStartRatio = -1;
		acquiredTicks = 0;
		stuckAnchor = null;
		stuckTicks = 0;
		bestDistance = Double.MAX_VALUE;
		noProgressTicks = 0;
			targetIsBoss = boss != null;
			logSelected(f, picked, targetIsBoss, targetIsBoss ? "boss prioritaire (propriétaire/niveau/nom validés)"
				: "farm : coût d'approche le plus bas parmi " + result.mobs().size() + " Enderman détecté(s)");
			reactionTicks = transitionDelay(f, picked);
			Debug.log("Voidgloom", () -> (targetIsBoss ? "Boss trouvé : " : "Enderman à farmer : ")
				+ f.entityInfo().resolve(ps.level(), picked));
			fsm.transition(VoidgloomState.FOLLOWING_TARGET);
		}
	}

	// ========================================
	// DIAGNOSTIC DE CIBLE (journal uniquement, aucun effet sur les décisions)
	// ========================================

	private String dropReason = "inconnue";

	private static String describeTarget(Framework f, EnderMan t, boolean boss) {
		PlayerState ps = f.player();
		return String.format(java.util.Locale.ROOT,
			"id=%d type=%s uuid=%s pos=(%.1f, %.1f, %.1f) distance=%.1f hp=%.0f/%.0f valide=%s boss=%s",
			t.getId(), net.minecraft.world.entity.EntityType.getKey(t.getType()), t.getUUID(), t.getX(), t.getY(), t.getZ(),
			ps.position().distanceTo(t.position()), t.getHealth(), t.getMaxHealth(), TargetSelector.isValid(t), boss);
	}

	private void logSelected(Framework f, EnderMan t, boolean boss, String reason) {
		Debug.log("Cible", () -> "SÉLECTION " + describeTarget(f, t, boss) + " | raison : " + reason);
	}

	private void logDropped(Framework f, EnderMan t, boolean boss, String reason) {
		if (t != null) {
			Debug.log("Cible", () -> "ABANDON " + describeTarget(f, t, boss) + " | raison : " + reason);
		}
	}

	/** Retire les Enderman récemment abandonnés car inaccessibles (derrière un mur, sur une autre plateforme...). */
	private java.util.List<EnderMan> reachable(Framework f, java.util.List<EnderMan> mobs) {
		skipped.values().removeIf(until -> until <= clock);
		PlayerState ps = f.player();
		float limit = ModConfig.get().farmViewAngleDeg;
		// Un mob apparu dans le dos n'est pas censé être vu : on ne se retourne pas pour lui (la cible déjà suivie reste valable).
		return mobs.stream().filter(m -> !skipped.containsKey(m.getId()))
			.filter(m -> limit <= 0 || limit >= 180 || m == target
				|| Math.abs(RotationController.yawDelta(ps.eyePosition(), m.position(), ps.yaw())) <= limit)
			.toList();
	}

	/** PV de la cible en fraction (0..1) d'après son nametag ou ses PV réels ; -1 si inconnus. */
	private double targetHealthRatio(Framework f) {
		if (target == null) {
			return -1;
		}
		var info = f.entityInfo().resolve(f.player().level(), target);
		return info.hasHealth() && info.maxHealth() > 0 ? info.health() / info.maxHealth() : -1;
	}

	/** Cible presque morte : on désigne la prochaine, vers laquelle la caméra peut commencer à dériver (point 2 : coup d'oeil). */
	private void updateGlance(Framework f, VoidgloomTarget.Result result, ModConfig cfg) {
		f.hints().setGlance(null);
		if (!cfg.glance || targetIsBoss || target == null) {
			return;
		}
		PlayerState ps = f.player();
		var info = f.entityInfo().resolve(ps.level(), target);
		boolean almostDead = info.hasHealth() && info.maxHealth() > 0 && info.health() / info.maxHealth() < cfg.glanceHealthFraction;
		if (!almostDead) {
			return;
		}
		EnderMan current = target;
		f.hints().setGlance(f.targetSelector().selectBy(
			reachable(f, result.mobs()).stream().filter(m -> m != current).toList(), m -> approachCost(f, ps, m)));
	}

	/** Toutes les ~1 s : un Enderman nettement plus proche que celui qu'on poursuit ? Alors on change (sauf si on le frappe déjà). */
	private boolean retargetNearest(Framework f, VoidgloomTarget.Result result, ModConfig cfg) {
		retargetAccum += cfg.targetSearchIntervalTicks;
		if (retargetAccum < cfg.retargetIntervalTicks) {
			return false;
		}
		retargetAccum = 0;
		PlayerState ps = f.player();
		if (TargetInfo.of(ps, target).distance() <= cfg.attackDistance && f.combat().hasStableLineOfSight(ps, target)) {
			return false; // déjà au contact : on ne lâche pas la cible qu'on frappe
		}
		EnderMan nearest = f.targetSelector().select(reachable(f, result.mobs()), ps.position(), null,
			Math.max(cfg.targetSearchRange, cfg.farmSearchRange));
		if (nearest == null || nearest == target) {
			return false;
		}
		double current = target.position().distanceTo(ps.position());
		double other = nearest.position().distanceTo(ps.position());
		if (other >= current - cfg.retargetMarginBlocks) {
			return false;
		}
		Debug.log("Voidgloom", () -> "Enderman plus proche trouvé (" + Math.round(other) + " au lieu de " + Math.round(current) + ")");
		stopActions(f);
		logDropped(f, target, targetIsBoss, "retarget : un autre Enderman est plus proche de " + String.format(java.util.Locale.ROOT, "%.1f", current - other)
			+ " blocs (marge " + cfg.retargetMarginBlocks + "), pas encore au contact");
		target = nearest;
		logSelected(f, nearest, false, "retarget : plus proche (" + Math.round(other) + " au lieu de " + Math.round(current) + ")");
		noLosTicks = 0;
		engagedTicks = -1;
		engageStartRatio = -1;
		acquiredTicks = 0;
		stuckAnchor = null;
		stuckTicks = 0;
		bestDistance = Double.MAX_VALUE;
		noProgressTicks = 0;
		reactionTicks = 0;
		fsm.transition(VoidgloomState.FOLLOWING_TARGET);
		return true;
	}

	private void updateCounters(VoidgloomTarget.Result result) {
		endermenSeen = result.endermen();
		bossesSeen = result.bosses().size();
		mobsSeen = result.mobs().size();
		foreignBosses = result.foreignBosses();
	}

	/** Pendant le farm : si un Voidgloom apparaît, on abandonne l'Enderman courant pour le boss. */
	private boolean switchToBossIfSpawned(Framework f) {
		ModConfig cfg = ModConfig.get();
		if (bossCheckTimer-- > 0) {
			return false;
		}
		bossCheckTimer = cfg.targetSearchIntervalTicks - 1;
		VoidgloomTarget.Result result = VoidgloomTarget.find(f);
		updateCounters(result);
		EnderMan boss = f.targetSelector().select(result.bosses(), f.player().position(), null,
			Math.max(cfg.targetSearchRange, cfg.farmSearchRange));
		if (boss == null) {
			updateGlance(f, result, cfg);
			return retargetNearest(f, result, cfg);
		}
		Debug.log("Voidgloom", () -> "Le boss est apparu, changement de cible");
		stopActions(f);
		logDropped(f, target, targetIsBoss, "le boss est apparu");
		target = boss;
		logSelected(f, boss, true, "boss apparu pendant le farm");
		targetIsBoss = true;
		reactionTicks = transitionDelay(f, boss);
		fsm.transition(VoidgloomState.FOLLOWING_TARGET);
		return true;
	}

	// ========================================
	// GROUND MECHANIC
	// ========================================

	private void combatTick(Framework f) {
		ModConfig cfg = ModConfig.get();
		PlayerState ps = f.player();

		double base = targetIsBoss ? cfg.targetSearchRange : Math.max(cfg.targetSearchRange, cfg.farmSearchRange);
		double keep = base * cfg.targetKeepRangeFactor;
		com.valafre.automod.debug.CombatTrace.moduleTarget(target);
		if (target != null && target.isDeadOrDying()) {
			kills++;
		}
		if (hudRefreshTimer-- <= 0) { // valeurs d'affichage : 2 fois par seconde suffisent
			hudRefreshTimer = 10;
			refreshHudInfo(f);
		}
		if (!TargetSelector.isValid(target) || target.position().distanceToSqr(ps.position()) > keep * keep) {
			Debug.log("Voidgloom", () -> "Cible perdue");
			dropReason = !TargetSelector.isValid(target) ? "invalide (morte ou retirée du monde)"
				: "hors de la portée de conservation (" + Math.round(Math.sqrt(target.position().distanceToSqr(ps.position()))) + " > " + Math.round(keep) + ")";
			fsm.transition(VoidgloomState.TARGET_LOST);
			return;
		}

		if (!targetIsBoss && switchToBossIfSpawned(f)) {
			return;
		}
		if (reactionTicks > 0 && !f.combat().hasLineOfSight(ps, target)) {
			reactionTicks = 0; // cible cachée derrière un bloc : on ne la fixe pas du regard, le déplacement s'en charge
		}
		if (reactionTicks > 0) { // transition : la caméra s'oriente vers la nouvelle cible avant l'engagement
			reactionTicks--;
			if (!f.tasks().isRunning(LookAtTask.class)) {
				EnderMan t = target;
				f.tasks().submit(f, ID, new LookAtTask(TaskPriority.FOLLOW, () -> f.humanizer().aim(t, f.player())));
			}
			return;
		}

		// La mécanique au sol n'existe que pendant le combat contre le boss.
		BlockPos found = targetIsBoss ? mechanics.poll(ps) : null;

		// Un Enderman qu'on ne voit plus depuis trop longtemps (mur, autre plateforme) est abandonné pour un autre.
		noLosTicks = f.combat().hasStableLineOfSight(ps, target) ? 0 : noLosTicks + 1;
		// Enderman normal : 3 s après le premier contact sans l'avoir tué (ou jamais atteint), on en prend un autre.
		// Le boss, lui, n'est JAMAIS abandonné.
		acquiredTicks++;
		if (engagedTicks >= 0) {
			engagedTicks++;
		} else if (TargetInfo.of(ps, target).distance() <= cfg.attackDistance && noLosTicks == 0) {
			engagedTicks = 0;
			engageStartRatio = targetHealthRatio(f);
		}
		// Joueur quasi immobile depuis 3 s (coincé dans un mur, sur un bord...) : on change de cible.
		net.minecraft.world.phys.Vec3 here = ps.position();
		if (stuckAnchor == null || here.distanceToSqr(stuckAnchor) > 0.25) {
			stuckAnchor = here;
			stuckTicks = 0;
		} else {
			stuckTicks++;
		}
		boolean stuck = stuckTicks > cfg.stuckSkipTicks;
		// Progression : si on n'arrive pas à se rapprocher (ex. mob perché au-dessus), on change de cible rapidement.
		double dist = TargetInfo.of(ps, target).distance();
		if (dist <= cfg.attackDistance || dist < bestDistance - 0.5) {
			bestDistance = dist;
			noProgressTicks = 0;
		} else {
			noProgressTicks++;
		}
		boolean elevated = target.getY() - ps.position().y > 2.5;
		boolean noProgress = noProgressTicks > (elevated && !cfg.acceptElevatedMobs ? cfg.noProgressElevatedTicks : cfg.noProgressTicks);
		// Délai de combat écoulé : on continue seulement si les PV de la cible baissent réellement (mob solide mais en train de mourir).
		boolean killTimeout = false;
		if (engagedTicks > cfg.mobKillTimeoutTicks) {
			double ratio = targetHealthRatio(f);
			if (engageStartRatio >= 0 && ratio >= 0 && engageStartRatio - ratio >= cfg.killProgressFraction) {
				engageStartRatio = ratio;
				engagedTicks = 0;
			} else {
				killTimeout = true;
			}
		}
		boolean acquireTimeout = acquiredTicks > cfg.mobAcquireTimeoutTicks;
		if (!targetIsBoss && (killTimeout || acquireTimeout || stuck || noProgress || noLosTicks > cfg.unreachableAfterTicks)) {
			dropReason = "abandon d'un Enderman de farm : killTimeout=" + killTimeout + " acquireTimeout=" + acquireTimeout
				+ " joueurBloqué=" + stuck + " sansProgrès=" + noProgress + " sansLigneDeVue=" + (noLosTicks > cfg.unreachableAfterTicks);
			skipped.put(target.getId(), clock + cfg.skipTargetTicks);
			Debug.log("Voidgloom", () -> "Enderman non tué à temps / inaccessible, on en prend un autre");
			fsm.transition(VoidgloomState.TARGET_LOST);
			return;
		}
		if (found == null) {
			handledMechanic = null;
			if (holdPosition) { // la mécanique a disparu : on reprend le combat normal
				clearReposition();
				stopActions(f);
				fsm.transition(VoidgloomState.FOLLOWING_TARGET);
			}
		} else if (!found.equals(handledMechanic) && !isRepositioning()) {
			mechanic = found;
			handledMechanic = found;
			failedPositions.clear();
			repositionAttempts = 0;
			Debug.log("Voidgloom", () -> "Mécanique détectée en " + found);
			fsm.transition(VoidgloomState.GROUND_MECHANIC_DETECTED);
		}

		switch (fsm.current()) {
			case GROUND_MECHANIC_DETECTED -> fsm.transition(VoidgloomState.CHOOSING_POSITION);
			case CHOOSING_POSITION -> choosePosition(f);
			case REPOSITIONING -> reposition(f);
			case POSITION_REACHED -> {
				boolean placed = mechanic != null && (pressMode
					? MoveToPositionTask.isTouching(f, mechanic, TOUCH_CHECK)
					: isNear(ps, moveDestination, cfg.positionArriveDistance + 0.3));
				if (!placed) {
					// Pas vraiment collé à la position : on recommence au lieu de se battre au mauvais endroit.
					Debug.log("Voidgloom", () -> "Position non atteinte précisément, nouvel essai");
					if (++repositionAttempts >= cfg.maxRepositionAttempts) {
						clearReposition();
						fsm.transition(VoidgloomState.FOLLOWING_TARGET);
					} else {
						fsm.transition(VoidgloomState.CHOOSING_POSITION);
					}
				} else {
					holdPosition = true;
					f.tasks().cancelOwner(f, ID);
					fsm.transition(VoidgloomState.ALIGNING);
				}
			}
			case FOLLOWING_TARGET, ALIGNING, ATTACKING -> engage(f);
			default -> { }
		}
	}

	/** Le joueur est-il à moins de {@code tolerance} blocs (horizontal) du centre de la case {@code pos} ? */
	private static boolean isNear(PlayerState ps, BlockPos pos, double tolerance) {
		if (pos == null) {
			return true;
		}
		net.minecraft.world.phys.Vec3 p = ps.position();
		double dx = p.x - (pos.getX() + 0.5);
		double dz = p.z - (pos.getZ() + 0.5);
		return dx * dx + dz * dz <= tolerance * tolerance && Math.abs(p.y - pos.getY()) < 1.5;
	}

	/**
	 * Attente avant d'engager une nouvelle cible, proportionnelle à l'angle dont il faut tourner (1 tick par 45 degrés, plafonné) :
	 * nulle si la cible est déjà dans l'axe, plus longue pour un demi-tour. Aucun hasard : elle vient de la situation.
	 */
	private int transitionDelay(Framework f, net.minecraft.world.entity.Entity entity) {
		ModConfig cfg = ModConfig.get();
		if (!cfg.humanize) {
			return 0;
		}
		PlayerState ps = f.player();
		float angle = Math.abs(RotationController.yawDelta(ps.eyePosition(), entity.position(), ps.yaw()));
		return Math.min(cfg.transitionMaxTicks, (int) (angle / 45.0f));
	}

	/**
	 * Coût d'une cible candidate (plus bas = préférée) : distance + petite pénalité par degré à tourner, plus de
	 * fortes pénalités pour les mobs hors de l'écran ou cachés derrière un bloc. On choisit donc en priorité ceux qu'on voit.
	 */
	private static double approachCost(Framework f, PlayerState ps, net.minecraft.world.entity.Entity entity) {
		float angle = Math.abs(RotationController.yawDelta(ps.eyePosition(), entity.position(), ps.yaw()));
		double cost = ps.position().distanceTo(entity.position()) + 0.02 * angle;
		// Un mob perché au-dessus ou en contrebas est souvent inaccessible : forte pénalité au-delà de 1,5 bloc de dénivelé.
		double dy = Math.abs(entity.getY() - ps.position().y);
		if (dy > 1.5 && !ModConfig.get().acceptElevatedMobs) {
			cost += 3.0 * (dy - 1.5);
		}
		if (ModConfig.get().preferOnScreen && !isOnScreen(f, ps, entity)) {
			cost += OFF_SCREEN_PENALTY;
		}
		if (!f.combat().hasLineOfSight(ps, entity)) {
			cost += NO_SIGHT_PENALTY;
		}
		return cost;
	}

	/** L'entité est-elle dans le champ de vision affiché (FOV vertical de l'option, FOV horizontal déduit du format de la fenêtre) ? */
	private static boolean isOnScreen(Framework f, PlayerState ps, net.minecraft.world.entity.Entity entity) {
		net.minecraft.client.Minecraft mc = f.minecraft();
		net.minecraft.world.phys.Vec3 eye = ps.eyePosition();
		net.minecraft.world.phys.Vec3 center = entity.getBoundingBox().getCenter();
		double halfV = Math.toRadians(mc.options.fov().get()) / 2.0;
		double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		double halfH = Math.atan(Math.tan(halfV) * aspect);
		double yaw = Math.toRadians(Math.abs(RotationController.yawDelta(eye, center, ps.yaw())));
		double pitch = Math.toRadians(Math.abs(RotationController.pitchDelta(eye, center, ps.pitch())));
		return yaw <= halfH && pitch <= halfV;
	}

	private boolean isRepositioning() {
		VoidgloomState s = fsm.current();
		return s == VoidgloomState.GROUND_MECHANIC_DETECTED || s == VoidgloomState.CHOOSING_POSITION
			|| s == VoidgloomState.REPOSITIONING || s == VoidgloomState.POSITION_REACHED;
	}

	// ========================================
	// REPOSITIONING
	// ========================================

	private void choosePosition(Framework f) {
		if (mechanic == null || target == null) { // plus de mécanique à traiter : on reprend le combat normal
			clearReposition();
			fsm.transition(VoidgloomState.FOLLOWING_TARGET);
			return;
		}
		PositionController.Request request =
			new PositionController.Request(mechanic, target.position(), failedPositions);
		Optional<PositionController.Candidate> choice = f.positions().choose(f.player(), request);
		if (choice.isEmpty()) {
			// Beacon loin du boss : on se colle quand même au beacon plutôt que de l'ignorer.
			choice = f.positions().choose(f.player(),
				new PositionController.Request(mechanic, null, failedPositions));
		}
		if (choice.isEmpty()) {
			// Aucune position sûre : on reste en combat normal, sans réessayer tant que cette mécanique est là.
			Debug.log("Voidgloom", () -> "Aucune position valide, reprise du combat");
			clearReposition();
			fsm.transition(VoidgloomState.FOLLOWING_TARGET);
			return;
		}
		BlockPos dest = choice.get().pos();
		moveDestination = dest;
		BlockPos mech = mechanic;
		pressMode = choice.get().touch();
		moveTask = new MoveToPositionTask(TaskPriority.REPOSITIONING, dest, () -> mechanics.isPresent(f.player(), mech),
			pressMode ? mech : null);
		if (f.tasks().submit(f, ID, moveTask)) {
			Debug.log("Movement", () -> "Destination = " + dest);
			fsm.transition(VoidgloomState.REPOSITIONING);
		}
	}

	private void reposition(Framework f) {
		if (moveTask == null) { // incohérence : pas de trajet en cours
			clearReposition();
			fsm.transition(VoidgloomState.FOLLOWING_TARGET);
			return;
		}
		if (!mechanics.isPresent(f.player(), mechanic)) { // disparue pendant le trajet
			clearReposition();
			stopActions(f);
			fsm.transition(VoidgloomState.FOLLOWING_TARGET);
			return;
		}
		TaskStatus status = moveTask.status();
		if (status == TaskStatus.SUCCEEDED) {
			fsm.transition(VoidgloomState.POSITION_REACHED);
		} else if (status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
			// Position devenue inaccessible / bloqué : on en exclut cette position et on en cherche une autre.
			failedPositions.add(moveTask.destination());
			int attempts = ++repositionAttempts;
			if (attempts >= ModConfig.get().maxRepositionAttempts) {
				Debug.log("Voidgloom", () -> "Repositionnement abandonné après " + attempts + " essais");
				clearReposition();
				fsm.transition(VoidgloomState.FOLLOWING_TARGET);
			} else {
				fsm.transition(VoidgloomState.CHOOSING_POSITION);
			}
		}
	}

	// ========================================
	// COMBAT
	// ========================================

	/**
	 * Une seule tâche de combat continue (poursuite + strafe + attaque). Les états FOLLOWING / ALIGNING / ATTACKING ne
	 * sont que le reflet de la situation (affichage et décisions), ils ne changent plus de tâche : pas de pause entre deux coups.
	 */
	private void engage(Framework f) {
		ModConfig cfg = ModConfig.get();
		PlayerState ps = f.player();
		// Invariant : tenir une position n'a de sens que s'il existe une mécanique. Sinon on ne tient rien.
		boolean wantHold = holdPosition && mechanic != null;
		boolean drifted = wantHold && (pressMode
			? !MoveToPositionTask.isTouching(f, mechanic, TOUCH_HOLD_TOLERANCE)
			: !isNear(ps, moveDestination, cfg.positionDriftDistance));
		if (drifted) {
			// Écarté de la position (recul, knockback...) : on y retourne.
			Debug.log("Voidgloom", () -> "Écarté de la position, retour");
			holdPosition = false;
			repositionAttempts = 0;
			stopActions(f);
			fsm.transition(VoidgloomState.CHOOSING_POSITION);
			return;
		}
		if (attackTask == null || attackTaskHold != wantHold || attackTaskTarget != target
			|| !f.tasks().isRunning(AttackTargetTask.class)) {
			Debug.log("Cible", () -> "Tâche de combat créée pour #" + target.getId() + " (cible changée=" + (attackTaskTarget != target)
				+ ", position tenue=" + wantHold + ", tâche active=" + f.tasks().isRunning(AttackTargetTask.class) + ")");
			attackTask = new AttackTargetTask(TaskPriority.COMBAT, target, wantHold, cfg.sneakOnBoss && targetIsBoss);
			attackTaskHold = wantHold;
			attackTaskTarget = target;
			f.tasks().submit(f, ID, attackTask);
		}
		TargetInfo info = TargetInfo.of(ps, target);
		boolean inRange = info.distance() <= cfg.attackDistance && f.combat().hasStableLineOfSight(ps, target);
		boolean ready = inRange && f.combat().isAligned(ps, target);
		fsm.transition(ready ? VoidgloomState.ATTACKING : inRange ? VoidgloomState.ALIGNING : VoidgloomState.FOLLOWING_TARGET);
	}
}
