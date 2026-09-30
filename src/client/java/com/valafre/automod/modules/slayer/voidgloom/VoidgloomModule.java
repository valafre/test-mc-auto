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
import com.valafre.automod.task.FollowTargetTask;
import com.valafre.automod.task.MoveToPositionTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.EnderMan;

import java.util.HashSet;
import java.util.List;
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
	private boolean holdPosition;       // vrai après un repositionnement tant que la mécanique existe

	@Override
	public String id() {
		return ID;
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
		if (!f.slayer().isActive()) {
			if (!fsm.is(VoidgloomState.IDLE)) {
				fsm.transition(VoidgloomState.STOPPING);
				stopActions(f);
				resetState();
			}
			return;
		}
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
				mechanic = null;
				handledMechanic = null;
				holdPosition = false;
				mechanics.reset();
				fsm.transition(VoidgloomState.SEARCHING_TARGET);
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
		searchTimer = cfg.targetSearchIntervalTicks - 1;
		PlayerState ps = f.player();
		List<EnderMan> found = VoidgloomTarget.find(f);
		EnderMan picked = f.targetSelector().select(found, ps.position(), null, cfg.targetSearchRange);
		if (picked != null) {
			target = picked;
			Debug.log("Voidgloom", () -> "Cible trouvée : " + f.entityInfo().resolve(ps.level(), picked));
			fsm.transition(VoidgloomState.FOLLOWING_TARGET);
		}
	}

	// ========================================
	// GROUND MECHANIC
	// ========================================

	private void combatTick(Framework f) {
		ModConfig cfg = ModConfig.get();
		PlayerState ps = f.player();

		double keep = cfg.targetSearchRange * cfg.targetKeepRangeFactor;
		if (!TargetSelector.isValid(target) || target.position().distanceToSqr(ps.position()) > keep * keep) {
			Debug.log("Voidgloom", () -> "Cible perdue");
			fsm.transition(VoidgloomState.TARGET_LOST);
			return;
		}

		BlockPos found = mechanics.poll(ps);
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
			case FOLLOWING_TARGET -> follow(f);
			case GROUND_MECHANIC_DETECTED -> fsm.transition(VoidgloomState.CHOOSING_POSITION);
			case CHOOSING_POSITION -> choosePosition(f);
			case REPOSITIONING -> reposition(f);
			case POSITION_REACHED -> {
				holdPosition = true;
				f.tasks().cancelOwner(f, ID);
				fsm.transition(VoidgloomState.ALIGNING);
			}
			case ALIGNING, ATTACKING -> fight(f);
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

	private void follow(Framework f) {
		TargetInfo info = TargetInfo.of(f.player(), target);
		if (info.distance() <= ModConfig.get().attackDistance) {
			fsm.transition(VoidgloomState.ALIGNING);
			return;
		}
		if (!f.tasks().isRunning(FollowTargetTask.class)) {
			f.tasks().submit(f, ID, new FollowTargetTask(TaskPriority.FOLLOW, target));
		}
	}

	private void fight(Framework f) {
		ModConfig cfg = ModConfig.get();
		TargetInfo info = TargetInfo.of(f.player(), target);
		if (!holdPosition && info.distance() > cfg.attackDistance + 0.5) {
			f.tasks().cancelOwner(f, ID);
			fsm.transition(VoidgloomState.FOLLOWING_TARGET);
			return;
		}
		if (!f.tasks().isRunning(AttackTargetTask.class)) {
			f.tasks().submit(f, ID, new AttackTargetTask(TaskPriority.COMBAT, target, !holdPosition));
		}
		boolean ready = info.distance() <= cfg.attackDistance && f.combat().isAligned(f.player(), target);
		fsm.transition(ready ? VoidgloomState.ATTACKING : VoidgloomState.ALIGNING);
	}
}
