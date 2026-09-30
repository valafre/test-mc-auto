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

	private static final double TOUCH_TOLERANCE = 0.06;

	private final BlockPos destination;
	private final BooleanSupplier stillWanted;
	private int ticks;

	/** Distance (horizontale) à la case de destination à partir de laquelle on se met à pousser contre la face. */
	private static final double PRESS_STAGE_DISTANCE = 0.9;

	private final BlockPos pressAgainst;

	/** @param stillWanted condition externe de validité (ex. la mécanique existe encore) ; peut être null */
	public MoveToPositionTask(int priority, BlockPos destination, BooleanSupplier stillWanted) {
		this(priority, destination, stillWanted, null);
	}

	/**
	 * @param pressAgainst bloc contre lequel le joueur doit se COLLER (contact réel de la hitbox avec une de ses faces) ;
	 *                     null = simple arrivée sur la case. La tâche ne réussit que quand le contact est constaté.
	 */
	public MoveToPositionTask(int priority, BlockPos destination, BooleanSupplier stillWanted, BlockPos pressAgainst) {
		super(priority);
		this.destination = destination;
		this.stillWanted = stillWanted;
		this.pressAgainst = pressAgainst;
	}

	/** La hitbox du joueur touche-t-elle le bloc (tolérance de quelques centimètres) ? */
	public static boolean isTouching(Framework f, BlockPos block, double tolerance) {
		if (block == null || f.player().player() == null) { // pas de bloc : aucun contact possible
			return false;
		}
		return f.player().player().getBoundingBox().inflate(tolerance).intersects(new net.minecraft.world.phys.AABB(block));
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
		if (pressAgainst != null) {
			if (isTouching(f, pressAgainst, TOUCH_TOLERANCE)) {
				Debug.log("Movement", () -> "Collé au bloc " + pressAgainst);
				return TaskStatus.SUCCEEDED;
			}
			Vec3 here = f.player().position();
			double dx = here.x - target.x;
			double dz = here.z - target.z;
			if (dx * dx + dz * dz <= PRESS_STAGE_DISTANCE * PRESS_STAGE_DISTANCE) {
				// Sur la case voisine : on pousse contre la face jusqu'au contact.
				f.movement().pushToward(f.player(), owner(),
					new Vec3(pressAgainst.getX() + 0.5, here.y, pressAgainst.getZ() + 0.5));
				return TaskStatus.RUNNING;
			}
		}
		MoveStatus status = f.movement().moveTo(f.player(), owner(), target, cfg.positionArriveDistance, true);
		return switch (status) {
			case ARRIVED -> {
				if (pressAgainst != null) {
					yield TaskStatus.RUNNING; // arrivé sur la case : le prochain tick pousse contre la face
				}
				Debug.log("Movement", () -> "Repositionnement terminé " + destination);
				yield TaskStatus.SUCCEEDED;
			}
			case BLOCKED -> TaskStatus.FAILED;
			case MOVING -> TaskStatus.RUNNING;
		};
	}
}
