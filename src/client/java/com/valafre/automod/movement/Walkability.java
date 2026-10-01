package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.nav.NavGeometry;
import com.valafre.automod.nav.NavPoint;
import com.valafre.automod.nav.NavWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Adaptateur Minecraft de la géométrie de navigation ({@link NavGeometry}). Toute la logique (surfaces réelles par formes de
 * collision, boîte du joueur, balayage) vit dans {@code nav.NavGeometry} et est partagée avec le worker ; ici on ne fait que la
 * lire sur la vue « live » en cache ({@link NavigationWorldCache}), sans appeler getCollisionShape à chaque test.
 *
 * <p>Convention : une CELLULE ({@link BlockPos}) est la case qui contient les pieds, floor(feetY + 0,001) ; la hauteur physique
 * est {@code feetY} (dessus de la collision où l'on se tient : demi-dalle y+0,5, bloc plein y+1, escalier, etc.).
 */
public final class Walkability {

	/** Hauteur de marche maximale (sans saut). */
	public static final double STEP_HEIGHT = NavGeometry.STEP_HEIGHT;
	/** Hauteur maximale franchissable en sautant. */
	public static final double JUMP_HEIGHT = NavGeometry.JUMP_HEIGHT;

	private Walkability() {}

	private static NavWorld w(Level level) {
		return NavService.get().live(level);
	}

	private static double dropReach() {
		return ModConfig.get().maxDropBlocks + 0.5;
	}

	/** Cellule de grille contenant les pieds. */
	public static BlockPos cellOf(Vec3 feet) {
		return new BlockPos(NavGeometry.floor(feet.x), NavGeometry.cellY(feet.y), NavGeometry.floor(feet.z));
	}

	/** Le joueur peut-il se tenir debout dans cette cellule (surface réelle + corps libre) ? */
	public static boolean canStandAt(Level level, BlockPos cell) {
		return NavGeometry.canStandAt(w(level), cell.getX(), cell.getY(), cell.getZ());
	}

	/** Le corps (0,6 x 1,8) tient-il dans cette cellule (boîte alignée sur la case) sans collision, liquide ni danger ? */
	public static boolean isBodyFree(Level level, BlockPos feet) {
		return NavGeometry.bodyFreeAt(w(level), feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0);
	}

	/** Hauteur réelle des pieds de la cellule (surface de collision), sinon dessus de la plus haute collision, sinon le bas de la cellule. */
	public static double standHeight(Level level, BlockPos cell) {
		return NavGeometry.standHeight(w(level), cell.getX(), cell.getY(), cell.getZ());
	}

	/** Surface praticable de la cellule, ou NaN. */
	public static double surfaceY(Level level, BlockPos cell) {
		return NavGeometry.surfaceY(w(level), cell.getX(), cell.getY(), cell.getZ());
	}

	/** Hauteur de pieds en (x, z) la plus proche de {@code y} dans [y - down, y + up] où le corps tient, ou NaN. */
	public static double feetHeightAt(Level level, double x, double z, double y, double up, double down, double margin) {
		return NavGeometry.feetHeightAt(w(level), x, z, y, up, down, margin);
	}

	/** Surface réelle sur laquelle poser les pieds près de {@code aroundY} (VoxelShape, corps libre, ni liquide ni danger), ou NaN. */
	public static double findStandableSurface(Level level, double x, double z, double aroundY) {
		return NavGeometry.feetHeightAt(w(level), x, z, aroundY, JUMP_HEIGHT, dropReach(), 0.0);
	}

	/** Point de navigation (cellule + hauteur réelle) ou null si on ne peut pas s'y tenir. */
	public static NavPoint navPointAt(Level level, BlockPos cell) {
		double y = surfaceY(level, cell);
		return Double.isNaN(y) ? null : NavPoint.of(cell.getX(), cell.getY(), cell.getZ(), y, false);
	}

	/** Dénivelé devant un joueur aux pieds {@code feet} dans la direction (dx, dz) : 0 à plat, +infini si aucune place. */
	public static double riseAhead(Level level, Vec3 feet, double dx, double dz) {
		return NavGeometry.riseAhead(w(level), feet.x, feet.y, feet.z, dx, dz, JUMP_HEIGHT);
	}

	/** Le corps peut-il se tenir EXACTEMENT en (x, y, z) (marge de chaque côté comprise) ? */
	public static boolean bodyFreeAt(Level level, double x, double y, double z, double margin) {
		return NavGeometry.bodyFreeAt(w(level), x, y, z, margin);
	}

	/** Un sol (non dangereux) sous l'empreinte en (x, z) à la hauteur de pieds y ? */
	public static boolean supportedAt(Level level, double x, double y, double z) {
		return NavGeometry.supportedAt(w(level), x, y, z);
	}

	/** Premier point bloqué d'un balayage (montées et chutes tolérées), ou null. */
	public static BlockPos segmentBlockedAt(Level level, Vec3 from, Vec3 to, double margin) {
		return sweepBlockedAt(level, from, to, margin, JUMP_HEIGHT, dropReach(), false);
	}

	/** Balayage de la boîte du joueur ; renvoie la cellule du premier point bloqué ou null. */
	public static BlockPos sweepBlockedAt(Level level, Vec3 from, Vec3 to, double margin, double up, double down, boolean needSupport) {
		long l = NavGeometry.sweepBlocked(w(level), from.x, from.y, from.z, to.x, to.z, margin, up, down, needSupport);
		return l == NavGeometry.NO_BLOCK ? null : BlockPos.of(l);
	}

	/** Trajet praticable ? {@code needSupport} : à pied (marche <= 0,6, sol partout) ; sinon montées de saut et chutes tolérées. */
	public static boolean segmentWalkable(Level level, Vec3 from, Vec3 to, double margin, boolean needSupport) {
		return NavGeometry.segmentWalkable(w(level), from.x, from.y, from.z, to.x, to.z, margin, needSupport,
			ModConfig.get().navMaxClimb, dropReach());
	}

	/** Rien de solide ne bloque le rayon (vue / frappe) ? Lecture sur la géométrie en cache. */
	public static boolean rayClear(Level level, Vec3 from, Vec3 to, net.minecraft.world.entity.Entity viewer) {
		return NavGeometry.rayClear(w(level), from.x, from.y, from.z, to.x, to.y, to.z);
	}

	public static boolean isInWorld(Level level, BlockPos pos) {
		return !level.isOutsideBuildHeight(pos) && level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
	}

	public static boolean isHazard(BlockState state) {
		return state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.FIRE)
			|| state.is(Blocks.SOUL_FIRE) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.CAMPFIRE);
	}
}
