package com.valafre.automod.movement;

import com.valafre.automod.nav.NavGrid;
import com.valafre.automod.nav.NavTile;
import com.valafre.automod.nav.NavWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache de géométrie de navigation, construit UNIQUEMENT sur le thread Minecraft.
 *
 * <p>Point important : la vue live n'a plus de {@code TileSource} qui construit une tuile à la demande. Cela évite qu'une
 * simple collision check dans le mouvement déclenche une construction de 512 blocs sur le thread principal. Les tuiles sont
 * préparées à l'avance avec un budget très court et les workers reçoivent uniquement des tuiles immuables déjà construites.</p>
 */
public final class NavigationWorldCache {

	private record StateData(float[] boxes, byte flags) {}
	private record TileCandidate(int tx, int ty, int tz, double distanceSq, boolean missing) {}

	private final Map<Long, NavTile> tiles = new HashMap<>();
	private final Map<Long, Long> lastUsed = new HashMap<>();
	private final IdentityHashMap<BlockState, StateData> states = new IdentityHashMap<>();
	private Level level;
	private long now;
	private long lastPrune;
	private int liveTtl = 10;

	private final NavGrid liveGrid = new NavGrid(Collections.unmodifiableMap(tiles), null);
	private long lastPrepareTick = Long.MIN_VALUE;
	private int buildsThisTick;

	// Statistiques (profilage)
	public long tilesBuilt;
	public long buildNanos;
	public long snapshots;
	public long snapshotNanos;
	public long snapshotTruncated;

	/** À appeler avant toute lecture du cache. */
	public void begin(Level lvl, int liveTtlTicks) {
		if (lvl != level) {
			level = lvl;
			tiles.clear();
			lastUsed.clear();
			states.clear();
			lastPrepareTick = Long.MIN_VALUE;
			buildsThisTick = 0;
		}
		liveTtl = Math.max(1, liveTtlTicks);
		now = lvl.getGameTime();
		if (now - lastPrune > 200) {
			lastPrune = now;
			lastUsed.entrySet().removeIf(e -> now - e.getValue() > 600 && tiles.remove(e.getKey()) != null);
		}
	}

	/**
	 * Vue live SANS construction implicite. Les cellules manquantes restent inconnues et sont traitées comme non sûres.
	 * La construction se fait par {@link #prepareRegion} depuis les points de décision du déplacement.
	 */
	public NavWorld live() {
		return liveGrid;
	}

	/**
	 * Prépare progressivement une zone. Au plus le budget demandé est dépensé et chaque appel privilégie les tuiles proches de
	 * l'ancre. Cette méthode est volontairement synchrone, mais bornée : elle ne doit jamais être appelée avec un budget élevé.
	 */
	public int prepareRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
				double anchorX, double anchorZ, double budgetMs) {
		long t0 = System.nanoTime();
		if (now != lastPrepareTick) {
			lastPrepareTick = now;
			buildsThisTick = 0;
		}
		// Une seule construction de tuile par tick, toutes les demandes confondues. Une tuile Minecraft est indivisible :
		// un budget inférieur à son temps de construction ne peut pas interrompre build(), donc on limite d'abord le nombre de tuiles.
		if (buildsThisTick >= 1 || budgetMs <= 0.0) {
			return 0;
		}
		long deadline = t0 + (long) (Math.max(0.10, budgetMs) * 1_000_000.0);
		int minTx = minX >> 3;
		int maxTx = maxX >> 3;
		int minTy = minY >> 3;
		int maxTy = maxY >> 3;
		int minTz = minZ >> 3;
		int maxTz = maxZ >> 3;

		List<TileCandidate> candidates = new ArrayList<>();
		for (int tx = minTx; tx <= maxTx; tx++) {
			for (int tz = minTz; tz <= maxTz; tz++) {
				double cx = tx * 8 + 4;
				double cz = tz * 8 + 4;
				double dx = cx - anchorX;
				double dz = cz - anchorZ;
				for (int ty = minTy; ty <= maxTy; ty++) {
					long key = NavTile.key(tx, ty, tz);
					NavTile t = tiles.get(key);
					int ttl = dx * dx + dz * dz < 16 * 16 ? Math.max(liveTtl, 20) : Math.max(liveTtl * 6, 120);
					boolean missing = t == null;
					boolean stale = missing || now - t.builtTick > ttl;
					if (stale) {
						candidates.add(new TileCandidate(tx, ty, tz, dx * dx + dz * dz, missing));
					}
				}
			}
		}
		candidates.sort(Comparator.<TileCandidate>comparingInt(c -> c.missing() ? 0 : 1).thenComparingDouble(TileCandidate::distanceSq));

		int built = 0;
		for (TileCandidate c : candidates) {
			if (System.nanoTime() >= deadline) {
				break;
			}
			NavTile t = build(c.tx(), c.ty(), c.tz());
			tiles.put(NavTile.key(c.tx(), c.ty(), c.tz()), t);
			lastUsed.put(NavTile.key(c.tx(), c.ty(), c.tz()), now);
			built++;
			buildsThisTick++;
			break;
		}
		return built;
	}


	/**
	 * Assemblage non bloquant d'un snapshot à partir des tuiles déjà disponibles. AUCUNE construction de tuile ici.
	 * Les tuiles absentes deviennent inconnues dans le worker et seront récupérées par les prochains appels à prepareRegion.
	 */
	public NavGrid snapshot(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
			double px, double pz, double budgetMs) {
		long t0 = System.nanoTime();
		Map<Long, NavTile> picked = new HashMap<>();
		boolean truncated = false;
		for (int tx = minX >> 3; tx <= maxX >> 3; tx++) {
			for (int tz = minZ >> 3; tz <= maxZ >> 3; tz++) {
				for (int ty = minY >> 3; ty <= maxY >> 3; ty++) {
					long key = NavTile.key(tx, ty, tz);
					NavTile t = tiles.get(key);
					if (t == null) {
						truncated = true;
						continue;
					}
					lastUsed.put(key, now);
					picked.put(key, t);
				}
			}
		}
		snapshots++;
		snapshotNanos += System.nanoTime() - t0;
		if (truncated) {
			snapshotTruncated++;
		}
		return new NavGrid(Map.copyOf(picked), null);
	}


	// ========================================
	// CONSTRUCTION D'UNE TUILE
	// ========================================

	private NavTile build(int tx, int ty, int tz) {
		long t0 = System.nanoTime();
		byte[] flags = new byte[512];
		float[][] boxes = new float[512][];
		if (level.hasChunk(tx >> 1, tz >> 1)) {
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (int lx = 0; lx < 8; lx++) {
				for (int ly = 0; ly < 8; ly++) {
					for (int lz = 0; lz < 8; lz++) {
						p.set(tx * 8 + lx, ty * 8 + ly, tz * 8 + lz);
						if (level.isOutsideBuildHeight(p)) {
							continue;
						}
						BlockState state = level.getBlockState(p);
						int i = NavTile.index(lx, ly, lz);
						if (state.isAir()) {
							flags[i] = (byte) NavWorld.KNOWN;
							continue;
						}
						StateData d = data(state);
						flags[i] = (byte) (NavWorld.KNOWN | d.flags());
						boxes[i] = d.boxes().length == 0 ? null : d.boxes();
					}
				}
			}
		}
		tilesBuilt++;
		buildNanos += System.nanoTime() - t0;
		return new NavTile(tx, ty, tz, now, flags, boxes);
	}

	private StateData data(BlockState state) {
		StateData d = states.get(state);
		if (d == null) {
			VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			float[] boxes = NavWorld.NONE;
			if (!shape.isEmpty()) {
				List<AABB> list = shape.toAabbs();
				boxes = new float[list.size() * 6];
				for (int i = 0; i < list.size(); i++) {
					AABB b = list.get(i);
					boxes[i * 6] = (float) b.minX;
					boxes[i * 6 + 1] = (float) b.minY;
					boxes[i * 6 + 2] = (float) b.minZ;
					boxes[i * 6 + 3] = (float) b.maxX;
					boxes[i * 6 + 4] = (float) b.maxY;
					boxes[i * 6 + 5] = (float) b.maxZ;
				}
				if (list.size() == 1 && boxes[0] == 0 && boxes[1] == 0 && boxes[2] == 0 && boxes[3] == 1 && boxes[4] == 1 && boxes[5] == 1) {
					boxes = NavWorld.FULL;
				}
			}
			int flags = 0;
			if (Walkability.isHazard(state)) {
				flags |= NavWorld.HAZARD;
			}
			if (!state.getFluidState().isEmpty()) {
				flags |= NavWorld.FLUID;
			}
			d = new StateData(boxes, (byte) flags);
			states.put(state, d);
		}
		return d;
	}
}
