package com.valafre.automod.movement;

import net.minecraft.core.BlockPos;
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

	/** Hauteur de marche maximale (sans saut). */
	public static final double STEP_HEIGHT = 0.6;
	/** Hauteur maximale franchissable en sautant. */
	public static final double JUMP_HEIGHT = 1.2;

	// ========================================
	// REPRÉSENTATION DE LA HAUTEUR DU SOL
	// ========================================
	// Une CASE (BlockPos) n'est qu'une position de grille : celle qui contient les pieds, soit floor(feetY + 0,001).
	// La hauteur réelle des pieds (feetY) est le dessus de la plus haute forme de collision sous l'empreinte 0,6 x 0,6 du
	// joueur : une demi-dalle donne y + 0,5, un bloc plein y + 1. Tout le reste (A*, waypoints, navigation locale, combat)
	// lit cette hauteur ICI, jamais un numéro de case.

	/** Case de grille contenant les pieds (convention unique : la case, pas le bloc sous les pieds). */
	public static BlockPos cellOf(Vec3 feet) {
		return BlockPos.containing(feet.x, feet.y + 0.001, feet.z);
	}

	/**
	 * Dessus des formes de collision (non dangereuses) qui touchent l'empreinte du joueur centrée en (x, z), compris dans
	 * [yLo, yHi], triés par ordre croissant. Générique : lit les AABB de collision, aucun cas particulier de bloc.
	 */
	public static List<Double> surfaceTops(Level level, double x, double z, double yLo, double yHi) {
		List<Double> tops = new ArrayList<>();
		int x0 = (int) Math.floor(x - HALF_WIDTH);
		int x1 = (int) Math.floor(x + HALF_WIDTH);
		int z0 = (int) Math.floor(z - HALF_WIDTH);
		int z1 = (int) Math.floor(z + HALF_WIDTH);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int by = (int) Math.floor(yHi); by >= (int) Math.floor(yLo) - 1; by--) {
			for (int bx = x0; bx <= x1; bx++) {
				for (int bz = z0; bz <= z1; bz++) {
					p.set(bx, by, bz);
					if (!isInWorld(level, p)) {
						continue;
					}
					BlockState state = level.getBlockState(p);
					VoxelShape shape = state.getCollisionShape(level, p);
					if (shape.isEmpty() || isHazard(state)) {
						continue;
					}
					for (AABB box : shape.toAabbs()) {
						double top = by + box.maxY;
						if (top < yLo - 1.0E-6 || top > yHi + 1.0E-6) {
							continue;
						}
						if (bx + box.maxX <= x - HALF_WIDTH || bx + box.minX >= x + HALF_WIDTH
							|| bz + box.maxZ <= z - HALF_WIDTH || bz + box.minZ >= z + HALF_WIDTH) {
							continue; // ne touche pas l'empreinte
						}
						if (!tops.contains(top)) {
							tops.add(top);
						}
					}
				}
			}
		}
		Collections.sort(tops);
		return tops;
	}

	/**
	 * Hauteur réelle des pieds en (x, z) pour un joueur qui était à la hauteur y : la surface où le corps tient (marge
	 * comprise), comprise entre y - down et y + up, la plus proche de y (à égalité la plus haute). NaN si aucune.
	 */
	public static double feetHeightAt(Level level, double x, double z, double y, double up, double down, double margin) {
		double best = Double.NaN;
		for (double top : surfaceTops(level, x, z, y - down, y + up)) {
			if (!bodyFreeAt(level, x, top, z, margin)) {
				continue;
			}
			if (Double.isNaN(best) || Math.abs(top - y) < Math.abs(best - y) - 1.0E-9
				|| (Math.abs(Math.abs(top - y) - Math.abs(best - y)) <= 1.0E-9 && top > best)) {
				best = top;
			}
		}
		return best;
	}

	/**
	 * Surface réelle sur laquelle le joueur peut poser les pieds en (x, z) près de la hauteur {@code aroundY} : formes de
	 * collision (VoxelShape) sous l'empreinte, corps 0,6 x 1,8 libre à cette hauteur, ni liquide ni danger. NaN si aucune.
	 * Montée jusqu'à {@link #JUMP_HEIGHT}, descente jusqu'à la chute maximale configurée.
	 */
	public static double findStandableSurface(Level level, double x, double z, double aroundY) {
		return feetHeightAt(level, x, z, aroundY, JUMP_HEIGHT, dropReach(), 0.0);
	}

	/** Point de navigation de la cellule {@code cell} (centre + hauteur réelle), ou null si on ne peut pas s'y tenir. */
	public static NavPoint navPointAt(Level level, BlockPos cell) {
		double y = surfaceY(level, cell);
		return Double.isNaN(y) ? null : NavPoint.of(cell, y, false);
	}

	/** Hauteur réelle des pieds pour la case {@code cell} (sol praticable où le corps tient), ou NaN. */
	public static double surfaceY(Level level, BlockPos cell) {
		if (!isInWorld(level, cell)) {
			return Double.NaN;
		}
		double x = cell.getX() + 0.5;
		double z = cell.getZ() + 0.5;
		List<Double> tops = surfaceTops(level, x, z, cell.getY(), cell.getY() + 0.999);
		for (int i = tops.size() - 1; i >= 0; i--) {
			if (bodyFreeAt(level, x, tops.get(i), z, 0.0)) {
				return tops.get(i);
			}
		}
		return Double.NaN;
	}

	/** Le joueur peut-il se tenir debout dans cette case : une surface réelle sous l'empreinte ET le corps (0,6 x 1,8) libre à cette hauteur ? */
	public static boolean canStandAt(Level level, BlockPos cell) {
		return !Double.isNaN(surfaceY(level, cell));
	}

	/**
	 * Le corps du joueur tient-il dans cette case (boîte alignée sur la case, depuis son bas) sans collision, liquide ni danger ?
	 * Test grossier de « case d'air » ; pour la hauteur réelle utiliser {@link #surfaceY}.
	 */
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
	 * Hauteur monde des pieds quand ils sont dans la case {@code cell} : surface réelle (voir {@link #surfaceY}). Pour une case
	 * non praticable : dessus de la plus haute forme de collision de la case ou de celle dessous, à défaut le bas de la case.
	 */
	public static double standHeight(Level level, BlockPos cell) {
		double y = surfaceY(level, cell);
		if (!Double.isNaN(y)) {
			return y;
		}
		List<Double> tops = surfaceTops(level, cell.getX() + 0.5, cell.getZ() + 0.5, cell.getY(), cell.getY() + 0.999);
		return tops.isEmpty() ? cell.getY() : tops.get(tops.size() - 1);
	}

	/**
	 * Dénivelé à franchir devant un joueur dont les pieds sont en {@code feet} dans la direction (dx, dz) : 0 si la voie est
	 * libre à plat, sinon (positif = monter, négatif = descendre) l'écart jusqu'à la surface où le corps tient. {@code
	 * POSITIVE_INFINITY} si aucune place. Calcul par formes de collision (demi-dalle = 0,5, bloc = 1, bloc + dalle = 1,5...).
	 */
	public static double riseAhead(Level level, Vec3 feet, double dx, double dz) {
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1.0E-4) {
			return 0;
		}
		double ax = feet.x + dx / len * 0.7;
		double az = feet.z + dz / len * 0.7;
		double h = feetHeightAt(level, ax, az, feet.y, JUMP_HEIGHT, 0.0, 0.0);
		return Double.isNaN(h) ? Double.POSITIVE_INFINITY : Math.max(0, h - feet.y);
	}

	/**
	 * Le corps du joueur (largeur 0,6 + {@code margin} de chaque côté, hauteur 1,8) peut-il se tenir EXACTEMENT en (x, y, z) ?
	 * Contrairement à {@link #isBodyFree}, la boîte est testée à la position réelle (pas au centre de la case) : c'est ce
	 * qui détecte les coins et les passages trop étroits que la grille de cases ne voit pas.
	 */
	public static boolean bodyFreeAt(Level level, double x, double y, double z, double margin) {
		double hw = HALF_WIDTH + margin;
		AABB box = new AABB(x - hw, y + 0.002, z - hw, x + hw, y + HEIGHT, z + hw);
		if (!level.noCollision(box)) {
			return false;
		}
		BlockPos feet = BlockPos.containing(x, y + 0.05, z);
		return isInWorld(level, feet) && isSafeFluidAndBlock(level, feet) && isSafeFluidAndBlock(level, feet.above());
	}

	/** Un sol (non dangereux) est-il sous l'empreinte du joueur en (x, z) à la hauteur de pieds y (à 0,06 près) ? */
	public static boolean supportedAt(Level level, double x, double y, double z) {
		return !surfaceTops(level, x, z, y - 0.06, y + 0.06).isEmpty();
	}

	/**
	 * Balayage de la boîte du joueur de {@code from} à {@code to} tous les 0,25 bloc. La hauteur des pieds suit le vrai sol
	 * à chaque échantillon (marche d'une demi-dalle, montée, descente) : on n'impose plus de hauteur « plate ». Retourne la
	 * case du premier point bloqué, ou null si le tronçon est praticable.
	 * @param up   montée maximale acceptée entre deux échantillons
	 * @param down descente maximale acceptée
	 * @param needSupport sol exigé sous chaque échantillon (sinon un vide est toléré : tronçon de chute)
	 */
	public static BlockPos sweepBlockedAt(Level level, Vec3 from, Vec3 to, double margin, double up, double down, boolean needSupport) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dz * dz);
		int samples = Math.max(1, (int) Math.ceil(length / 0.25));
		double y = from.y;
		for (int i = 1; i <= samples; i++) {
			double t = (double) i / samples;
			double x = from.x + dx * t;
			double z = from.z + dz * t;
			double ny = feetHeightAt(level, x, z, y, up, down, margin);
			if (Double.isNaN(ny)) {
				if (needSupport || !bodyFreeAt(level, x, y, z, margin)) {
					return BlockPos.containing(x, y + 0.05, z);
				}
				continue; // chute : le corps passe à la hauteur courante
			}
			if (ny < y - 0.05 && !bodyFreeAt(level, x, y, z, margin)) {
				return BlockPos.containing(x, y + 0.05, z); // descente : le corps doit passer à l'ancienne hauteur aussi
			}
			y = ny;
		}
		return null;
	}

	private static double dropReach() {
		return com.valafre.automod.config.ModConfig.get().maxDropBlocks + 0.5;
	}

	/** Premier point bloqué d'un tronçon de déplacement (montées et chutes tolérées), ou null. */
	public static BlockPos segmentBlockedAt(Level level, Vec3 from, Vec3 to, double margin) {
		return sweepBlockedAt(level, from, to, margin, JUMP_HEIGHT, dropReach(), false);
	}

	/**
	 * Le déplacement de {@code from} à {@code to} est-il réellement praticable ? Avec {@code needSupport} : à pied (montée
	 * d'au plus 0,6, descente d'au plus 0,6, sol sous chaque point) ; sinon montées de saut et chutes tolérées.
	 */
	public static boolean segmentWalkable(Level level, Vec3 from, Vec3 to, double margin, boolean needSupport) {
		if (needSupport) {
			return sweepBlockedAt(level, from, to, margin, STEP_HEIGHT, STEP_HEIGHT, true) == null;
		}
		return sweepBlockedAt(level, from, to, margin, JUMP_HEIGHT, dropReach(), false) == null;
	}

	/** Rien ne bloque le rayon {@code from} -> {@code to} (blocs pleins) ? Sert à savoir si une position permet de voir/frapper la cible. */
	public static boolean rayClear(Level level, Vec3 from, Vec3 to, net.minecraft.world.entity.Entity viewer) {
		net.minecraft.world.level.ClipContext context = new net.minecraft.world.level.ClipContext(from, to,
			net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, viewer);
		return level.clip(context).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
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
