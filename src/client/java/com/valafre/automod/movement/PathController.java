package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.nav.NavGeometry;
import com.valafre.automod.nav.NavGrid;
import com.valafre.automod.nav.NavParams;
import com.valafre.automod.nav.NavPoint;
import com.valafre.automod.nav.NavWorld;
import com.valafre.automod.nav.PathEngine;
import com.valafre.automod.nav.PathRequest;
import com.valafre.automod.nav.PathResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client du chemin global côté thread Minecraft : construit un snapshot, dépose une demande au worker ({@link #request}, retour
 * immédiat) et lit le résultat sans attendre ({@link #poll}). Un seul calcul en cours + la demande la plus récente en attente.
 * Les résultats d'une génération périmée ({@link #invalidate}) sont jetés. Garde aussi la mémoire des échecs récents (expirante).
 * Le calcul lui-même (A*, lissage) est dans {@link PathEngine}, jamais sur le thread Minecraft.
 */
public final class PathController {

	private final NavService service = NavService.get();
	private final Map<BlockPos, Long> failures = new HashMap<>();
	private long generation = 1;
	private long sequence;
	/** Demande dont on attend la réponse : le but demandé peut avoir été ramené dans la zone du snapshot. */
	private boolean lastClipped;
	private BlockPos lastGoal;

	/** Mémorise un échec sur {@code pos} : surcoût pendant {@code navFailureMemoryMs}, puis oubli. */
	public void avoid(BlockPos pos) {
		if (failures.size() > 96) {
			prune();
		}
		failures.put(pos.immutable(), System.currentTimeMillis() + ModConfig.get().navFailureMemoryMs);
	}

	public void clearAvoid() {
		prune();
	}

	private void prune() {
		long now = System.currentTimeMillis();
		failures.values().removeIf(t -> t < now);
	}

	/** Les résultats en vol ou en attente deviennent obsolètes (cible téléportée, nouveau but, reset). */
	public void invalidate() {
		generation++;
	}

	public long generation() {
		return generation;
	}

	/** Un calcul est en cours ou une demande attend. */
	public boolean busy() {
		return service.worker().global.busy();
	}

	/** La demande dont on lit le résultat a-t-elle eu son but ramené dans la zone copiée (chemin forcément partiel) ? */
	public boolean lastClipped() {
		return lastClipped;
	}

	public BlockPos lastGoal() {
		return lastGoal;
	}

	/**
	 * Dépose une demande de chemin (retour IMMÉDIAT). Construit seulement le snapshot (tuiles en cache, budget borné).
	 *
	 * @param feet  position réelle des pieds
	 * @param goal  destination (pieds)
	 * @param light calcul allégé (après un échec) : moins de nœuds, zone plus petite, but plus proche
	 */
	public void request(Level level, Vec3 feet, Vec3 goal, boolean light) {
		ModConfig cfg = ModConfig.get();
		NavigationWorldCache cache = service.cache();
		cache.begin(level, cfg.navTileTtlTicks);
		int radius = light ? Math.min(14, cfg.navSnapshotRadius) : cfg.navSnapshotRadius;
		int margin = light ? 4 : cfg.navSnapshotMargin;
		double gx = goal.x;
		double gz = goal.z;
		double gy = goal.y;
		double dx = gx - feet.x;
		double dz = gz - feet.z;
		double dist = Math.hypot(dx, dz);
		int reach = radius - 2;
		boolean clipped = false;
		if (dist > reach) { // but hors de la zone copiée : on vise un point intermédiaire (chemin partiel qui se poursuit)
			gx = feet.x + dx / dist * reach;
			gz = feet.z + dz / dist * reach;
			gy = feet.y;
			clipped = true;
		}
		int minX = (int) Math.floor(Math.min(feet.x, gx)) - margin;
		int maxX = (int) Math.floor(Math.max(feet.x, gx)) + margin;
		int minZ = (int) Math.floor(Math.min(feet.z, gz)) - margin;
		int maxZ = (int) Math.floor(Math.max(feet.z, gz)) + margin;
		int minY = (int) Math.floor(Math.min(feet.y, gy)) - cfg.maxDropBlocks - 3;
		int maxY = (int) Math.floor(Math.max(feet.y, gy)) + 6;
		// Les tuiles sont préparées par petits budgets sur le thread Minecraft, mais le snapshot lui-même ne construit plus rien.
		cache.prepareRegion(minX, minY, minZ, maxX, maxY, maxZ, feet.x, feet.z, Math.min(1.25, cfg.navSnapshotBudgetMs));
		NavGrid grid = cache.snapshot(minX, minY, minZ, maxX, maxY, maxZ, feet.x, feet.z, cfg.navSnapshotBudgetMs);
		NavParams params = NavService.params(light);
		long[] failed = activeFailures();
		PathRequest req = new PathRequest(++sequence, generation, grid, feet.x, feet.y, feet.z,
			NavGeometry.floor(gx), NavGeometry.cellY(gy), NavGeometry.floor(gz), gx, gy, gz, params, failed);
		lastClipped = clipped;
		lastGoal = new BlockPos(req.goalCx(), req.goalCy(), req.goalCz());
		service.worker().global.submit(req);
	}

	private long[] activeFailures() {
		if (failures.isEmpty()) {
			return new long[0];
		}
		long now = System.currentTimeMillis();
		long[] out = new long[failures.size()];
		int n = 0;
		for (Map.Entry<BlockPos, Long> e : failures.entrySet()) {
			if (e.getValue() >= now) {
				out[n++] = e.getKey().asLong();
			}
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/** Dernier résultat terminé de la génération courante (null sinon). Ne bloque jamais ; les périmés sont jetés. */
	public PathResult poll() {
		PathResult r = service.worker().global.poll();
		if (r == null) {
			return null;
		}
		boolean stale = r.generation() != generation;
		service.recordGlobal(r, stale);
		return stale ? null : r;
	}

	/**
	 * Ligne droite praticable (marge de sécurité, sol partout) ? Contrôle BORNÉ sur la géométrie en cache : jamais au-delà de
	 * 14 blocs (les longues distances sont décidées par le worker : résultat « ligne directe »).
	 */
	public boolean isClearLine(Level level, Vec3 from, Vec3 to) {
		if (Math.abs(to.y - from.y) > 0.6 || Math.hypot(to.x - from.x, to.z - from.z) > 14.0) {
			return false;
		}
		return Walkability.segmentWalkable(level, from, to, Math.max(0.12, ModConfig.get().navSafetyMargin), true);
	}

	/**
	 * Chemin SYNCHRONE borné (validation d'une position par le positionneur de mécanique, rare) : au plus 400 nœuds et 4 ms,
	 * sur la vue en cache du thread Minecraft. Jamais utilisé pour le déplacement.
	 */
	public List<NavPoint> findPath(Level level, BlockPos start, BlockPos goal, int maxNodes) {
		service.prepareArea(level, start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 8, 0.35);
		service.prepareArea(level, goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 8, 0.35);
		NavWorld w = service.live(level);
		double sy = Walkability.standHeight(level, start);
		double gy = Walkability.standHeight(level, goal);
		NavParams base = NavService.params(true);
		NavParams p = new NavParams(base.maxClimb(), base.maxDrop(), base.clearanceWeight(), base.deadEndPenalty(),
			Math.min(maxNodes, 400), 4.0, base.safetyMargin(), base.smoothing(), base.sampleDistance(), base.horizon(),
			base.jumpHeight(), base.minSafeDistance(), base.progressWeight(), base.turnPenalty(), base.alignWeight(),
			base.combatVisibilityWeight(), base.localBudgetMs());
		PathRequest req = new PathRequest(0, 0, w, start.getX() + 0.5, sy, start.getZ() + 0.5, goal.getX(), goal.getY(), goal.getZ(),
			goal.getX() + 0.5, gy, goal.getZ() + 0.5, p, new long[0]);
		PathResult r = PathEngine.search(req);
		return r.status() == PathResult.Status.COMPLETE ? r.points() : List.of();
	}

	/** Cellule sans véritable issue (une seule sortie ou aucune) ? Lecture sur la géométrie en cache. */
	public static boolean isDeadEnd(Level level, BlockPos pos) {
		return PathEngine.isDeadEnd(NavService.get().live(level), pos.getX(), pos.getY(), pos.getZ(), ModConfig.get().navMaxClimb);
	}
}
