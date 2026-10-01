package com.valafre.automod.nav;

import java.util.Map;

/**
 * Snapshot de navigation : ensemble de {@link NavTile} immuables. Utilisé tel quel par le worker (aucun accès au monde Minecraft),
 * ou avec un {@link TileSource} qui construit à la demande les tuiles manquantes (vue « live » sur le thread Minecraft).
 * Une cellule hors des tuiles connues est INCONNUE (traitée comme pleine mais jamais comme surface praticable).
 */
public final class NavGrid implements NavWorld {

	/** Fournisseur de tuiles à la demande (thread Minecraft uniquement). */
	public interface TileSource {
		NavTile tile(int tx, int ty, int tz);
	}

	private final Map<Long, NavTile> tiles;
	private final TileSource source;
	private NavTile last;
	private long checks;

	public NavGrid(Map<Long, NavTile> tiles, TileSource source) {
		this.tiles = tiles;
		this.source = source;
	}

	public int tileCount() {
		return tiles.size();
	}

	private NavTile tileOf(int x, int y, int z) {
		int tx = x >> 3;
		int ty = y >> 3;
		int tz = z >> 3;
		NavTile t = last;
		if (t != null && t.tx == tx && t.ty == ty && t.tz == tz) {
			return t;
		}
		t = tiles.get(NavTile.key(tx, ty, tz));
		if (t == null && source != null) {
			t = source.tile(tx, ty, tz);
		}
		if (t != null) {
			last = t;
		}
		return t;
	}

	@Override
	public int flags(int x, int y, int z) {
		NavTile t = tileOf(x, y, z);
		return t == null ? 0 : t.flags[NavTile.index(x, y, z)];
	}

	@Override
	public float[] boxes(int x, int y, int z) {
		NavTile t = tileOf(x, y, z);
		if (t == null) {
			return FULL; // inconnu : traité comme plein (jamais praticable : flags == 0)
		}
		float[] b = t.boxes[NavTile.index(x, y, z)];
		return b == null ? NONE : b;
	}

	@Override
	public void countCheck() {
		checks++;
	}

	public long checks() {
		return checks;
	}
}
