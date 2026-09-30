package com.valafre.automod.modules.slayer.voidgloom;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.AbstractModule;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.core.StateMachine;
import com.valafre.automod.core.TaskPriority;
import com.valafre.automod.core.TaskStatus;
import com.valafre.automod.movement.PositionController;
import com.valafre.automod.targeting.TargetInfo;
import com.valafre.automod.targeting.TargetSelector;
import com.valafre.automod.task.AttackTargetTask;
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
	private int foreignBosses;
	private boolean targetIsBoss;       // false : Enderman normal farmé pour faire apparaître le boss
	private int reactionTicks;          // délai de réaction humain avant d'agir sur une nouvelle cible
	private int bossCheckTimer;
	private AttackTargetTask attackTask;
	private boolean attackTaskHold;
	private EnderMan attackTaskTarget;
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
	public int getPriority() {
		return MODULE_PRIORITY;
	}

	// ========================================
	// CYCLE DE VIE
	// ========================================

	@Override
	public void onEnable(Framework f) {
		resetState();
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
		targetIsBoss = false;
		reactionTicks = 0;
		bossCheckTimer = 0;
		mechanic = null;
		handledMechanic = null;
		moveTask = null;
		holdPosition = false;
		repositionAttempts = 0;
		searchTimer = 0;
		failedPositions.clear();
		mechanics.reset();
		fsm.transition(VoidgloomState.IDLE);
	}

	private void stopActions(Framework f) {
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
				target = null;
				targetIsBoss = false;
				mechanic = null;
				handledMechanic = null;
				holdPosition = false;
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
		EnderMan picked = boss != null ? boss : f.targetSelector().select(reachable(result.mobs()), ps.position(), null, range);
		if (picked != null) {
			target = picked;
			noLosTicks = 0;
			targetIsBoss = boss != null;
			reactionTicks = f.humanizer().nextReactionDelay();
			Debug.log("Voidgloom", () -> (targetIsBoss ? "Boss trouvé : " : "Enderman à farmer : ")
				+ f.entityInfo().resolve(ps.level(), picked));
			fsm.transition(VoidgloomState.FOLLOWING_TARGET);
		}
	}

	/** Retire les Enderman récemment abandonnés car inaccessibles (derrière un mur, sur une autre plateforme...). */
	private java.util.List<EnderMan> reachable(java.util.List<EnderMan> mobs) {
		skipped.values().removeIf(until -> until <= clock);
		return mobs.stream().filter(m -> !skipped.containsKey(m.getId())).toList();
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
			return false;
		}
		Debug.log("Voidgloom", () -> "Le boss est apparu, changement de cible");
		stopActions(f);
		target = boss;
		targetIsBoss = true;
		reactionTicks = f.humanizer().nextReactionDelay();
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
		if (!TargetSelector.isValid(target) || target.position().distanceToSqr(ps.position()) > keep * keep) {
			Debug.log("Voidgloom", () -> "Cible perdue");
			fsm.transition(VoidgloomState.TARGET_LOST);
			return;
		}

		if (!targetIsBoss && switchToBossIfSpawned(f)) {
			return;
		}
		if (reactionTicks > 0) { // réaction humaine : petite pause avant d'agir sur une nouvelle cible
			reactionTicks--;
			return;
		}

		// La mécanique au sol n'existe que pendant le combat contre le boss.
		BlockPos found = targetIsBoss ? mechanics.poll(ps) : null;

		// Un Enderman qu'on ne voit plus depuis trop longtemps (mur, autre plateforme) est abandonné pour un autre.
		noLosTicks = f.combat().hasLineOfSight(ps, target) ? 0 : noLosTicks + 1;
		if (!targetIsBoss && noLosTicks > cfg.unreachableAfterTicks) {
			skipped.put(target.getId(), clock + cfg.skipTargetTicks);
			Debug.log("Voidgloom", () -> "Enderman inaccessible, on en prend un autre");
			fsm.transition(VoidgloomState.TARGET_LOST);
			return;
		}
		if (found == null) {
			handledMechanic = null;
			if (holdPosition) { // la mécanique a disparu : on reprend le combat normal
				holdPosition = false;
				mechanic = null;
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
				holdPosition = true;
				f.tasks().cancelOwner(f, ID);
				fsm.transition(VoidgloomState.ALIGNING);
			}
			case FOLLOWING_TARGET, ALIGNING, ATTACKING -> engage(f);
			default -> { }
		}
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
		PositionController.Request request =
			new PositionController.Request(mechanic, target.position(), failedPositions);
		Optional<PositionController.Candidate> choice = f.positions().choose(f.player(), request);
		if (choice.isEmpty()) {
			// Aucune position sûre : on reste en combat normal, sans réessayer tant que cette mécanique est là.
			Debug.log("Voidgloom", () -> "Aucune position valide, reprise du combat");
			holdPosition = false;
			fsm.transition(VoidgloomState.FOLLOWING_TARGET);
			return;
		}
		BlockPos dest = choice.get().pos();
		BlockPos mech = mechanic;
		moveTask = new MoveToPositionTask(TaskPriority.REPOSITIONING, dest, () -> mechanics.isPresent(f.player(), mech));
		if (f.tasks().submit(f, ID, moveTask)) {
			Debug.log("Movement", () -> "Destination = " + dest);
			fsm.transition(VoidgloomState.REPOSITIONING);
		}
	}

	private void reposition(Framework f) {
		if (!mechanics.isPresent(f.player(), mechanic)) { // disparue pendant le trajet
			holdPosition = false;
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
			if (++repositionAttempts >= ModConfig.get().maxRepositionAttempts) {
				Debug.log("Voidgloom", () -> "Repositionnement abandonné après " + repositionAttempts + " essais");
				holdPosition = false;
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
		boolean wantHold = holdPosition;
		if (attackTask == null || attackTaskHold != wantHold || attackTaskTarget != target
			|| !f.tasks().isRunning(AttackTargetTask.class)) {
			attackTask = new AttackTargetTask(TaskPriority.COMBAT, target, wantHold);
			attackTaskHold = wantHold;
			attackTaskTarget = target;
			f.tasks().submit(f, ID, attackTask);
		}
		TargetInfo info = TargetInfo.of(ps, target);
		boolean inRange = info.distance() <= cfg.attackDistance && f.combat().hasLineOfSight(ps, target);
		boolean ready = inRange && f.combat().isAligned(ps, target);
		fsm.transition(ready ? VoidgloomState.ATTACKING : inRange ? VoidgloomState.ALIGNING : VoidgloomState.FOLLOWING_TARGET);
	}
}
