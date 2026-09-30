package com.valafre.automod.task;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.Task;
import com.valafre.automod.core.TaskStatus;
import com.valafre.automod.movement.MovementController.MoveStatus;
import com.valafre.automod.movement.Walkability;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;

/**
 * Marche (sans téléportation) jusqu'à une case précise. Échoue si la destination devient invalide/inaccessible,
 * si le joueur est bloqué ou si le délai maximal est dépassé : jamais de blocage indéfini.
 */
public final class MoveToPositionTask extends Task {

	private final BlockPos destination;
	private final BooleanSupplier stillWanted;
	private int ticks;

	/** @param stillWanted condition externe de validité (ex. la mécanique existe encore) ; peut être null */
	public MoveToPositionTask(int priority, BlockPos destination, BooleanSupplier stillWanted) {
		super(priority);
		this.destination = destination;
		this.stillWanted = stillWanted;
	}

	public BlockPos destination() {
		return destination;
	}

	@Override
	public String name() {
		return "MoveToPosition" + destination.toShortString();
	}

	@Override
	public TaskStatus onTick(Framework f) {
		ModConfig cfg = ModConfig.get();
		if (++ticks > cfg.moveTimeoutTicks) {
			Debug.log("Movement", () -> "Délai dépassé vers " + destination);
			return TaskStatus.FAILED;
		}
		if (stillWanted != null && !stillWanted.getAsBoolean()) {
			return TaskStatus.FAILED;
		}
		if (!Walkability.canStandAt(f.player().level(), destination)) {
			Debug.log("Movement", () -> "Destination devenue inaccessible " + destination);
			return TaskStatus.FAILED;
		}
		Vec3 target = new Vec3(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5);
		MoveStatus status = f.movement().moveTo(f.player(), owner(), target, cfg.stopDistance, true);
		return switch (status) {
			case ARRIVED -> {
				Debug.log("Movement", () -> "Repositionnement terminé " + destination);
				yield TaskStatus.SUCCEEDED;
			}
			case BLOCKED -> TaskStatus.FAILED;
			case MOVING -> TaskStatus.RUNNING;
		};
	}
}
