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

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache de géométrie de navigation (THREAD MINECRAFT UNIQUEMENT). Conserve des {@link NavTile} immuables de 8 x 8 x 8 cellules :
 * par bloc, ses boîtes de collision (lues UNE fois par état de bloc, plus un appel getCollisionShape par test) et ses
 * drapeaux (danger, liquide). Les tuiles sont rafraîchies par péremption (TTL) et seulement celles qui servent ; un snapshot
 * est un simple assemblage de références vers des tuiles immuables (pas de copie), donc peu coûteux et sûr à passer au worker.
 */
public final class NavigationWorldCache {

	private record StateData(float[] boxes, byte flags) {}

	private final Map<Long, NavTile> tiles = new HashMap<>();
	private final Map<Long, Long> lastUsed = new HashMap<>();
	private final IdentityHashMap<BlockState, StateData> states = new IdentityHashMap<>();
	private Level level;
	private long now;
	private long lastPrune;
	private int liveTtl = 10;
	private NavGrid liveGrid;
	private long liveGridTick = Long.MIN_VALUE;

	// Statistiques (profilage)
	public long tilesBuilt;
	public long buildNanos;
	public long snapshots;
	public long snapshotNanos;
	public long snapshotTruncated;

	/** À appeler avant toute lecture : synchronise avec le monde courant et l'heure de jeu. */
	public void begin(Level lvl, int liveTtlTicks) {
		if (lvl != level) {
			level = lvl;
			tiles.clear();
			lastUsed.clear();
			liveGrid = null;
			states.clear();
		}
		liveTtl = Math.max(1, liveTtlTicks);
		now = lvl.getGameTime();
		if (now - lastPrune > 200) { // oublie les tuiles non utilisées depuis 30 s
			lastPrune = now;
			lastUsed.entrySet().removeIf(e -> now - e.getValue() > 600 && tiles.remove(e.getKey()) != null);
		}
	}

	/** Vue « live » (thread Minecraft) : tuiles construites à la demande, rafraîchies après le TTL. Valable pour ce tick. */
	public NavWorld live() {
		if (liveGrid == null || liveGridTick != now) {
			liveGrid = new NavGrid(Map.of(), (tx, ty, tz) -> tile(tx, ty, tz, liveTtl));
			liveGridTick = now;
		}
		return liveGrid;
	}

	private NavTile tile(int tx, int ty, int tz, int ttl) {
		long key = NavTile.key(tx, ty, tz);
		NavTile t = tiles.get(key);
		if (t == null || now - t.builtTick > ttl) {
			t = build(tx, ty, tz);
			tiles.put(key, t);
		}
		lastUsed.put(key, now);
		return t;
	}

	/**
	 * Snapshot de la zone [minX..maxX] x [minY..maxY] x [minZ..maxZ] (coordonnées de blocs). Les tuiles proches de
	 * {@code (px, pz)} sont rafraîchies selon le TTL ; les lointaines vivent 6 fois plus longtemps ; la construction est bornée
	 * par {@code budgetMs} (une tuile manquante hors budget reste INCONNUE : la zone est tronquée et se complétera au snapshot suivant).
	 */
	public NavGrid snapshot(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, double px, double pz, double budgetMs) {
		long t0 = System.nanoTime();
		long deadline = t0 + (long) (budgetMs * 1_000_000.0);
		Map<Long, NavTile> picked = new HashMap<>();
		boolean truncated = false;
		for (int tx = minX >> 3; tx <= maxX >> 3; tx++) {
			for (int tz = minZ >> 3; tz <= maxZ >> 3; tz++) {
				double dx = tx * 8 + 4 - px;
				double dz = tz * 8 + 4 - pz;
				int ttl = dx * dx + dz * dz < 18 * 18 ? liveTtl : liveTtl * 6;
				for (int ty = minY >> 3; ty <= maxY >> 3; ty++) {
					long key = NavTile.key(tx, ty, tz);
					NavTile t = tiles.get(key);
					boolean stale = t == null || now - t.builtTick > ttl;
					if (stale && System.nanoTime() < deadline) {
						t = build(tx, ty, tz);
						tiles.put(key, t);
					} else if (stale && t == null) {
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
		return new NavGrid(picked, null);
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
							continue; // inconnu
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
