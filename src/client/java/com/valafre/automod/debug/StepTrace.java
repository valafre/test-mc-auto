package com.valafre.automod.debug;

import com.valafre.automod.core.Debug;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.movement.MovementController;
import com.valafre.automod.movement.PathController;
import com.valafre.automod.movement.Walkability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic des surfaces à hauteur partielle (demi-dalles, tapis, chemins de terre, marches...). Aucun effet sur le
 * comportement. Émet des lignes {@code [STEP]} dans trois situations :
 * <ul>
 *   <li>{@code PLAYER-*} : le joueur monte / reste / quitte une surface à hauteur partielle (hauteur réelle du sol,
 *       conventions de case, rise, collisions) ;</li>
 *   <li>{@code A*-REJECT} : le pathfinding rejette une arête de montée/descente (échantillon exact qui échoue) ;</li>
 *   <li>{@code ROLLOUT-BLOCK} : la navigation locale s'arrête à un changement de hauteur (raison précise).</li>
 * </ul>
 * Les lignes sont dédoublonnées et limitées pour ne pas noyer le journal.
 */
public final class StepTrace {

	private static final Map<String, Long> LAST = new HashMap<>();
	private static final int DEDUPE_TICKS = 60;
	private static int perTickBudget;
	private static long budgetTick = Long.MIN_VALUE;

	private static boolean wasPartial;
	private static long lastPeriodic = Long.MIN_VALUE / 2;

	private StepTrace() {}

	// ========================================
	// UTILITAIRES
	// ========================================

	private static boolean allow(Level level, String key, int period) {
		long now = level.getGameTime();
		if (now != budgetTick) {
			budgetTick = now;
			perTickBudget = 4;
		}
		if (perTickBudget <= 0) {
			return false;
		}
		Long last = LAST.get(key);
		if (last != null && now - last < period) {
			return false;
		}
		if (LAST.size() > 500) {
			LAST.clear();
		}
		LAST.put(key, now);
		perTickBudget--;
		return true;
	}

	private static double shapeTop(Level level, BlockPos p) {
		VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
		return shape.isEmpty() ? Double.NaN : shape.max(Direction.Axis.Y);
	}

	private static boolean partial(double top) {
		return !Double.isNaN(top) && top > 0.001 && top < 0.999;
	}

	private static String block(Level level, BlockPos p) {
		BlockState st = level.getBlockState(p);
		double top = shapeTop(level, p);
		return String.format(Locale.ROOT, "(%d,%d,%d) %s shapeTop=%s", p.getX(), p.getY(), p.getZ(), st,
			Double.isNaN(top) ? "aucune" : String.format(Locale.ROOT, "%.4f", top));
	}

	private static String v(Vec3 p) {
		return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", p.x, p.y, p.z);
	}

	/** Premier échantillon du balayage qui échoue (même échantillonnage que {@link Walkability#segmentWalkable}). */
	private static String firstFailure(Level level, Vec3 from, Vec3 to, double margin) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dz * dz);
		int samples = Math.max(1, (int) Math.ceil(length / 0.25));
		boolean stepped = Math.abs(to.y - from.y) > 0.6;
		boolean slight = !stepped && Math.abs(to.y - from.y) > 0.05;
		for (int i = 1; i <= samples; i++) {
			double t = (double) i / samples;
			double x = from.x + dx * t;
			double z = from.z + dz * t;
			double y = (stepped || slight) ? (t < 0.5 ? from.y : to.y) : from.y;
			if (!Walkability.bodyFreeAt(level, x, y, z, margin)) {
				return String.format(Locale.ROOT, "t=%.2f (échantillon %d/%d) corps testé à y=%.3f au centre (%.2f, %.2f) [modèle: hauteur de départ avant 50 %%, d'arrivée après]",
					t, i, samples, y, x, z);
			}
		}
		return "aucun";
	}

	// ========================================
	// A* : arête rejetée
	// ========================================

	/** Le pathfinding vient de rejeter l'arête {@code base -> next} parce que le balayage du corps échoue. */
	public static void astarReject(Level level, BlockPos base, BlockPos next, Vec3 a, Vec3 b) {
		if (!Debug.enabled()) {
			return;
		}
		double top1 = shapeTop(level, base.below());
		double top2 = shapeTop(level, next.below());
		boolean part = partial(top1) || partial(top2);
		String key = "A*" + base.asLong() + ">" + next.asLong();
		if (!allow(level, key, DEDUPE_TICKS * 5)) {
			return;
		}
		double rise = Walkability.standHeight(level, next) - Walkability.standHeight(level, base);
		String line = String.format(Locale.ROOT,
			"[STEP] A*-REJECT partiel=%s | base=%s | sol sous base: %s | next=%s | sol sous next: %s | standHeight(base)=%.4f standHeight(next)=%.4f rise=%.4f"
				+ " | a=%s b=%s | bodyFreeAt(a)=%s bodyFreeAt(b)=%s supported(a)=%s supported(b)=%s"
				+ " | segmentWalkable(marge 0.02)=false | premier échec: %s",
			part, cellText(base), block(level, base.below()), cellText(next), block(level, next.below()),
			Walkability.standHeight(level, base), Walkability.standHeight(level, next), rise, v(a), v(b),
			Walkability.bodyFreeAt(level, a.x, a.y, a.z, 0.0), Walkability.bodyFreeAt(level, b.x, b.y, b.z, 0.0),
			Walkability.supportedAt(level, a.x, a.y, a.z), Walkability.supportedAt(level, b.x, b.y, b.z),
			firstFailure(level, a, b, 0.02));
		Debug.log("STEP", () -> line);
	}

	private static String cellText(BlockPos p) {
		return String.format(Locale.ROOT, "cellule pieds (%d,%d,%d)", p.getX(), p.getY(), p.getZ());
	}

	// ========================================
	// NAVIGATION LOCALE : simulation bloquée
	// ========================================

	/** La simulation locale s'arrête à un changement de hauteur ; {@code reason} dit pourquoi (voir appels dans LocalNavigator). */
	public static void rolloutBlock(Level level, String reason, int step, Vec3 from, double nx, double ny, double nz,
									double headingDeg, double rise, double groundTop) {
		if (!Debug.enabled()) {
			return;
		}
		BlockPos ahead = BlockPos.containing(nx, ny + 0.05, nz);
		BlockPos aheadFloor = BlockPos.containing(nx, ny - 0.05, nz);
		double tAhead = shapeTop(level, ahead);
		double tFloor = shapeTop(level, aheadFloor);
		boolean relevant = !Double.isNaN(rise) && Double.isFinite(rise) && rise > 0.05 || partial(tAhead) || partial(tFloor)
			|| !Double.isNaN(groundTop);
		if (!relevant) {
			return; // simple mur plein : pas une question de hauteur
		}
		String key = "R" + reason + aheadFloor.asLong() + ((int) Math.round(headingDeg / 15.0));
		if (!allow(level, key, DEDUPE_TICKS)) {
			return;
		}
		String line = String.format(Locale.ROOT,
			"[STEP] ROLLOUT-BLOCK raison=%s pas=%d | de=%s vers=(%.3f, %.3f, %.3f) cap=%.0f | rise(riseAhead)=%s groundTop=%s"
				+ " | bloc devant: %s | sol sous le point: %s | bodyFreeAt(point, marge 0.12)=%s supportedAt(point)=%s",
			reason, step, v(from), nx, ny, nz, headingDeg, Double.isInfinite(rise) ? "∞" : String.format(Locale.ROOT, "%.4f", rise),
			Double.isNaN(groundTop) ? "NaN" : String.format(Locale.ROOT, "%.4f", groundTop),
			block(level, ahead), block(level, aheadFloor), Walkability.bodyFreeAt(level, nx, ny, nz, 0.12),
			Walkability.supportedAt(level, nx, ny, nz));
		Debug.log("STEP", () -> line);
	}

	// ========================================
	// JOUEUR : surface partielle réellement traversée
	// ========================================

	/** Appelé chaque tick (debug) : détecte l'entrée / le séjour / la sortie d'une surface à hauteur partielle. */
	public static void tick(Framework f) {
		if (!Debug.enabled()) {
			wasPartial = false;
			return;
		}
		PlayerState ps = f.player();
		Level level = ps.level();
		Vec3 pos = ps.position();
		BlockPos floorCell = BlockPos.containing(pos.x, pos.y - 0.05, pos.z);
		double top = shapeTop(level, floorCell);
		boolean onPartial = ps.onGround() && partial(top);
		long now = level.getGameTime();
		MovementController.DebugState nav = f.movement().debugState();
		boolean trouble = !nav.safe() || !nav.waypointValid();
		String kind = null;
		if (onPartial && !wasPartial) {
			kind = "PLAYER-ENTER";
		} else if (!onPartial && wasPartial) {
			kind = "PLAYER-LEAVE";
		} else if (onPartial && ((trouble && now - lastPeriodic >= 5) || now - lastPeriodic >= 20)) {
			kind = trouble ? "PLAYER-ON(problème de navigation)" : "PLAYER-ON";
		}
		wasPartial = onPartial;
		if (kind == null) {
			return;
		}
		lastPeriodic = now;

		// Direction du regard / du mouvement pour les tests « devant »
		Vec3 motion = ps.player().getDeltaMovement();
		double dirX;
		double dirZ;
		if (Math.hypot(motion.x, motion.z) > 0.02) {
			double len = Math.hypot(motion.x, motion.z);
			dirX = motion.x / len;
			dirZ = motion.z / len;
		} else {
			double rad = Math.toRadians(ps.yaw());
			dirX = -Math.sin(rad);
			dirZ = Math.cos(rad);
		}
		BlockPos startCell = ps.player().blockPosition();          // utilisé comme départ du pathfinding
		BlockPos aboveFloor = floorCell.above();                      // convention « case des pieds » de Walkability
		double rise = Walkability.riseAhead(level, pos, dirX, dirZ);
		Vec3 ahead = new Vec3(pos.x + dirX * 1.0, pos.y, pos.z + dirZ * 1.0);
		boolean seg0 = Walkability.segmentWalkable(level, pos, ahead, 0.0, false);
		boolean seg12 = Walkability.segmentWalkable(level, pos, ahead, 0.12, true);
		BlockPos aheadFloor = BlockPos.containing(pos.x + dirX * 0.9, pos.y - 0.05, pos.z + dirZ * 0.9);
		String line = String.format(Locale.ROOT,
			"[STEP] %s | block=%s | playerFeetY=%.4f (surface réelle=%.4f) | départ A* = blockPosition()=(%d,%d,%d) vs case au-dessus du sol=(%d,%d,%d)"
				+ " | standHeight(blockPosition)=%.4f standHeight(case au-dessus)=%.4f | canStandAt(blockPosition)=%s canStandAt(case au-dessus)=%s isBodyFree(blockPosition)=%s"
				+ " | rise(devant)=%s | bodyFreeAt(joueur)=%s supported=%s | segmentWalkable(1 bloc devant, marge 0)=%s (marge 0.12+sol)=%s"
				+ " | sol devant: %s | navigation: statut=%s waypointValide=%s sûr=%s refus=%s point exécuté=%s",
			kind, block(level, floorCell), pos.y, floorCell.getY() + top, startCell.getX(), startCell.getY(), startCell.getZ(),
			aboveFloor.getX(), aboveFloor.getY(), aboveFloor.getZ(), Walkability.standHeight(level, startCell),
			Walkability.standHeight(level, aboveFloor), Walkability.canStandAt(level, startCell), Walkability.canStandAt(level, aboveFloor),
			Walkability.isBodyFree(level, startCell), Double.isInfinite(rise) ? "∞" : String.format(Locale.ROOT, "%.4f", rise),
			Walkability.bodyFreeAt(level, pos.x, pos.y, pos.z, 0.0), Walkability.supportedAt(level, pos.x, pos.y, pos.z), seg0, seg12,
			block(level, aheadFloor), f.movement().lastStatus(), nav.waypointValid(), nav.safe(), nav.refusal(),
			nav.executedPoint() == null ? "—" : v(nav.executedPoint()));
		Debug.log("STEP", () -> line);
	}
}
