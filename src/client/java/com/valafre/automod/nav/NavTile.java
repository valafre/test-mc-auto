package com.valafre.automod.nav;

/** Bloc de 8 x 8 x 8 cellules de géométrie, IMMUABLE une fois construit (partageable entre threads). */
public final class NavTile {

	public final int tx;
	public final int ty;
	public final int tz;
	/** Tick (monde) de construction : sert à décider d'un rafraîchissement. */
	public final long builtTick;
	final byte[] flags;
	final float[][] boxes;

	public NavTile(int tx, int ty, int tz, long builtTick, byte[] flags, float[][] boxes) {
		this.tx = tx;
		this.ty = ty;
		this.tz = tz;
		this.builtTick = builtTick;
		this.flags = flags;
		this.boxes = boxes;
	}

	public static int index(int x, int y, int z) {
		return (x & 7) | ((y & 7) << 3) | ((z & 7) << 6);
	}

	public static long key(int tx, int ty, int tz) {
		return ((long) (tx & 0x3FFFFFF) << 38) | ((long) (tz & 0x3FFFFFF) << 12) | (ty & 0xFFFL);
	}
}
