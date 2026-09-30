package com.valafre.automod.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

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
