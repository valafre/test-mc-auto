package com.valafre.automod.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tests de collision / praticabilité partagés par le pathfinding, le mouvement et le positionnement. */
public final class Walkability {

	private static final double HALF_WIDTH = 0.3;
	private static final double HEIGHT = 1.8;

	private Walkability() {}

	/** Le joueur peut-il se tenir debout avec les pieds dans la case {@code feet} (sol solide, corps libre) ? */
	public static boolean canStandAt(Level level, BlockPos feet) {
		if (!isInWorld(level, feet)) {
			return false;
		}
		BlockPos below = feet.below();
		BlockState floor = level.getBlockState(below);
		if (floor.getCollisionShape(level, below).isEmpty() || isHazard(floor)) {
			return false;
		}
		return isBodyFree(level, feet);
	}

	/** Le corps (0.6 x 1.8) du joueur tient-il dans cette case sans collision, sans liquide ni danger ? */
	public static boolean isBodyFree(Level level, BlockPos feet) {
		if (!isInWorld(level, feet)) {
			return false;
		}
		AABB box = new AABB(
			feet.getX() + 0.5 - HALF_WIDTH, feet.getY(), feet.getZ() + 0.5 - HALF_WIDTH,
			feet.getX() + 0.5 + HALF_WIDTH, feet.getY() + HEIGHT, feet.getZ() + 0.5 + HALF_WIDTH);
		if (!level.noCollision(box)) {
			return false;
		}
		return isSafeFluidAndBlock(level, feet) && isSafeFluidAndBlock(level, feet.above());
	}

	/**
	 * Hauteur monde de la surface sur laquelle on se tient quand les pieds sont dans la case {@code feet} : dessus de la
	 * forme de collision du bloc dessous (une demi-dalle donne y + 0,5, un bloc plein y + 1). C'est la vraie hauteur,
	 * pas un simple numéro de case.
	 */
	public static double standHeight(Level level, BlockPos feet) {
		BlockPos below = feet.below();
		VoxelShape shape = level.getBlockState(below).getCollisionShape(level, below);
		return shape.isEmpty() ? below.getY() : below.getY() + shape.max(Direction.Axis.Y);
	}

	/**
	 * Hauteur à gravir devant un joueur dont les pieds sont en {@code feet} (coordonnées réelles) dans la direction
	 * (dx, dz) : 0 si la voie est libre, sinon le dénivelé jusqu'à la première surface où le corps (1,8) tient.
	 * {@code POSITIVE_INFINITY} si aucune place. Avec des demi-dalles, 1 bloc apparent peut valoir 0,5 (on monte en marchant)
	 * ou 1,5 (impossible même en sautant) : c'est ce calcul qui permet de ne sauter que quand c'est utile.
	 */
	public static double riseAhead(Level level, Vec3 feet, double dx, double dz) {
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1.0E-4) {
			return 0;
		}
		double ax = feet.x + dx / len * 0.7;
		double az = feet.z + dz / len * 0.7;
		int cx = (int) Math.floor(ax);
		int cz = (int) Math.floor(az);
		int baseY = (int) Math.floor(feet.y + 0.001);

		List<Double> heights = new ArrayList<>();
		heights.add(feet.y);
		for (int y = baseY - 1; y <= baseY + 2; y++) {
			BlockPos p = new BlockPos(cx, y, cz);
			VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
			if (!shape.isEmpty()) {
				double top = y + shape.max(Direction.Axis.Y);
				if (top > feet.y && top <= feet.y + 1.6) {
					heights.add(top);
				}
			}
		}
		Collections.sort(heights);
		for (double h : heights) {
			AABB body = new AABB(ax - HALF_WIDTH, h + 0.002, az - HALF_WIDTH, ax + HALF_WIDTH, h + HEIGHT, az + HALF_WIDTH);
			if (level.noCollision(body)) {
				return h - feet.y;
			}
		}
		return Double.POSITIVE_INFINITY;
	}

	public static boolean isInWorld(Level level, BlockPos pos) {
		return !level.isOutsideBuildHeight(pos)
			&& level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
	}

	public static boolean isHazard(BlockState state) {
		return state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.FIRE)
			|| state.is(Blocks.SOUL_FIRE) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.CAMPFIRE);
	}

	private static boolean isSafeFluidAndBlock(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getFluidState().isEmpty() && !isHazard(state);
	}
}
