package com.valafre.automod.nav;

/**
 * Géométrie de navigation commune (aucune dépendance Minecraft) : surfaces réelles à partir des boîtes de collision, boîte du
 * joueur (0,6 x 1,8), support, balayage de trajet. Source unique de la « vraie hauteur des pieds » (feetY) pour le chemin global,
 * la navigation locale et le positionnement de combat. Fonctionne sur n'importe quel {@link NavWorld}.
 */
public final class NavGeometry {

	public static final double HALF_WIDTH = 0.3;
	public static final double HEIGHT = 1.8;
	/** Hauteur de marche maximale (sans saut). */
	public static final double STEP_HEIGHT = 0.6;
	/** Hauteur maximale franchissable en sautant. */
	public static final double JUMP_HEIGHT = 1.2;
	public static final long NO_BLOCK = Long.MIN_VALUE;

	private NavGeometry() {}

	// ========================================
	// CELLULES
	// ========================================

	public static int floor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}

	/** Cellule contenant les pieds : floor(feetY + 0,001). */
	public static int cellY(double feetY) {
		return floor(feetY + 0.001);
	}

	/** Même encodage que {@code BlockPos.asLong}. */
	public static long pack(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF)) | ((long) (z & 0x3FFFFFF) << 12);
	}

	public static boolean known(NavWorld w, int x, int y, int z) {
		return (w.flags(x, y, z) & NavWorld.KNOWN) != 0;
	}

	// ========================================
	// COLLISION DU CORPS
	// ========================================

	/** Aucune boîte de collision ne chevauche (volume strictement positif) la boîte donnée ? Une cellule inconnue compte comme pleine. */
	public static boolean noCollision(NavWorld w, double x0, double y0, double z0, double x1, double y1, double z1) {
		w.countCheck();
		int bx0 = floor(x0);
		int by0 = floor(y0);
		int bz0 = floor(z0);
		int bx1 = floor(x1 - 1.0E-9);
		int by1 = floor(y1 - 1.0E-9);
		int bz1 = floor(z1 - 1.0E-9);
		for (int bx = bx0; bx <= bx1; bx++) {
			for (int by = by0; by <= by1; by++) {
				for (int bz = bz0; bz <= bz1; bz++) {
					float[] boxes = w.boxes(bx, by, bz);
					for (int i = 0; i < boxes.length; i += 6) {
						if (x0 < bx + boxes[i + 3] && x1 > bx + boxes[i]
							&& y0 < by + boxes[i + 4] && y1 > by + boxes[i + 1]
							&& z0 < bz + boxes[i + 5] && z1 > bz + boxes[i + 2]) {
							return false;
						}
					}
				}
			}
		}
		return true;
	}

	private static boolean safeCell(NavWorld w, int x, int y, int z) {
		return (w.flags(x, y, z) & (NavWorld.HAZARD | NavWorld.FLUID)) == 0;
	}

	/**
	 * Le corps du joueur (largeur 0,6 + {@code margin} de chaque côté, hauteur 1,8) peut-il se tenir EXACTEMENT en (x, y, z) :
	 * aucune collision, ni liquide ni danger aux pieds et à la tête, cellule connue.
	 */
	public static boolean bodyFreeAt(NavWorld w, double x, double y, double z, double margin) {
		double hw = HALF_WIDTH + margin;
		if (!noCollision(w, x - hw, y + 0.002, z - hw, x + hw, y + HEIGHT, z + hw)) {
			return false;
		}
		int cx = floor(x);
		int cy = floor(y + 0.05);
		int cz = floor(z);
		return known(w, cx, cy, cz) && safeCell(w, cx, cy, cz) && safeCell(w, cx, cy + 1, cz);
	}

	// ========================================
	// SURFACES
	// ========================================

	private static final int MAX_TOPS = 24;
	/** Buffers réutilisés par thread : la géométrie est appelée des milliers de fois par un A* et ne doit pas générer de GC. */
	private static final ThreadLocal<double[]> TOP_BUFFER = ThreadLocal.withInitial(() -> new double[MAX_TOPS]);

	/**
	 * Dessus des boîtes de collision (non dangereuses, cellules connues) qui touchent l'empreinte du joueur centrée en (x, z),
	 * compris dans [yLo, yHi]. Écrit dans {@code out} (sans doublon), renvoie le nombre de valeurs.
	 */
	public static int collectTops(NavWorld w, double x, double z, double yLo, double yHi, double[] out) {
		int n = 0;
		int x0 = floor(x - HALF_WIDTH);
		int x1 = floor(x + HALF_WIDTH);
		int z0 = floor(z - HALF_WIDTH);
		int z1 = floor(z + HALF_WIDTH);
		for (int by = floor(yHi); by >= floor(yLo) - 1; by--) {
			for (int bx = x0; bx <= x1; bx++) {
				for (int bz = z0; bz <= z1; bz++) {
					int f = w.flags(bx, by, bz);
					if ((f & NavWorld.KNOWN) == 0 || (f & NavWorld.HAZARD) != 0) {
						continue;
					}
					float[] boxes = w.boxes(bx, by, bz);
					for (int i = 0; i < boxes.length; i += 6) {
						double top = by + boxes[i + 4];
						if (top < yLo - 1.0E-6 || top > yHi + 1.0E-6) {
							continue;
						}
						if (bx + boxes[i + 3] <= x - HALF_WIDTH || bx + boxes[i] >= x + HALF_WIDTH
							|| bz + boxes[i + 5] <= z - HALF_WIDTH || bz + boxes[i + 2] >= z + HALF_WIDTH) {
							continue; // ne touche pas l'empreinte
						}
						boolean dup = false;
						for (int k = 0; k < n; k++) {
							if (out[k] == top) {
								dup = true;
								break;
							}
						}
						if (!dup && n < out.length) {
							out[n++] = top;
						}
					}
				}
			}
		}
		return n;
	}

	/**
	 * Hauteur réelle des pieds en (x, z) pour un joueur qui était à la hauteur y : la surface où le corps tient (marge
	 * comprise), entre y - down et y + up, la plus proche de y (à égalité la plus haute). NaN si aucune.
	 */
	public static double feetHeightAt(NavWorld w, double x, double z, double y, double up, double down, double margin) {
		double[] tops = TOP_BUFFER.get();
		int n = collectTops(w, x, z, y - down, y + up, tops);
		double best = Double.NaN;
		for (int i = 0; i < n; i++) {
			double top = tops[i];
			if (!bodyFreeAt(w, x, top, z, margin)) {
				continue;
			}
			if (Double.isNaN(best) || Math.abs(top - y) < Math.abs(best - y) - 1.0E-9
				|| (Math.abs(Math.abs(top - y) - Math.abs(best - y)) <= 1.0E-9 && top > best)) {
				best = top;
			}
		}
		return best;
	}

	/** Surface réelle pour la cellule (cx, cy, cz) (pieds dans cette cellule), ou NaN. */
	public static double surfaceY(NavWorld w, int cx, int cy, int cz) {
		if (!known(w, cx, cy, cz)) {
			return Double.NaN;
		}
		double x = cx + 0.5;
		double z = cz + 0.5;
		/*
		 * La cellule CY contient les pieds, pas le bloc support. Il faut donc regarder la cellule elle-même ET celle
		 * immédiatement dessous. Cela distingue correctement : bloc plein (support y-1 -> pieds y), demi-dalle basse
		 * (support y -> pieds y+0.5) et formes partielles similaires.
		 */
		double[] tops = TOP_BUFFER.get();
		int n = collectTops(w, x, z, cy - 0.001, cy + 0.999, tops);
		double best = Double.NaN;
		for (int i = 0; i < n; i++) {
			double top = tops[i];
			if (top < cy - 0.001 || top > cy + 0.999) {
				continue;
			}
			if ((Double.isNaN(best) || Math.abs(top - cy) < Math.abs(best - cy) - 1.0E-9
				|| (Math.abs(Math.abs(top - cy) - Math.abs(best - cy)) <= 1.0E-9 && top > best))
				&& bodyFreeAt(w, x, top, z, 0.0)) {
				best = top;
			}
		}
		return best;
	}

	public static boolean canStandAt(NavWorld w, int cx, int cy, int cz) {
		return !Double.isNaN(surfaceY(w, cx, cy, cz));
	}

	/** Hauteur de pieds de la cellule : surface réelle, sinon dessus de la plus haute collision de la cellule, sinon son bas. */
	public static double standHeight(NavWorld w, int cx, int cy, int cz) {
		return surfaceY(w, cx, cy, cz);
	}

	/** Un sol (non dangereux) sous l'empreinte en (x, z) à la hauteur de pieds y (0,06 près) ? */
	public static boolean supportedAt(NavWorld w, double x, double y, double z) {
		double[] tops = TOP_BUFFER.get();
		return collectTops(w, x, z, y - 0.06, y + 0.06, tops) > 0;
	}

	/** Dénivelé devant (dx, dz) : 0 à plat, positif = monter, +infini si aucune place. Par formes de collision. */
	public static double riseAhead(NavWorld w, double fx, double fy, double fz, double dx, double dz, double jumpReach) {
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1.0E-4) {
			return 0;
		}
		double h = feetHeightAt(w, fx + dx / len * 0.7, fz + dz / len * 0.7, fy, jumpReach, 0.0, 0.0);
		return Double.isNaN(h) ? Double.POSITIVE_INFINITY : Math.max(0, h - fy);
	}

	// ========================================
	// TRAJETS
	// ========================================

	/**
	 * Balayage de la boîte du joueur de (fx,fy,fz) à (tx,ty,tz) tous les 0,25 bloc ; la hauteur des pieds suit le vrai sol.
	 * Renvoie la cellule (encodée) du premier point bloqué, ou {@link #NO_BLOCK}.
	 */
	public static long sweepBlocked(NavWorld w, double fx, double fy, double fz, double tx, double tz, double margin,
									double up, double down, boolean needSupport) {
		double dx = tx - fx;
		double dz = tz - fz;
		double length = Math.sqrt(dx * dx + dz * dz);
		int samples = Math.max(1, (int) Math.ceil(length / 0.25));
		double y = fy;
		for (int i = 1; i <= samples; i++) {
			double t = (double) i / samples;
			double x = fx + dx * t;
			double z = fz + dz * t;
			double ny = feetHeightAt(w, x, z, y, up, down, margin);
			if (Double.isNaN(ny)) {
				if (needSupport || !bodyFreeAt(w, x, y, z, margin)) {
					return pack(floor(x), floor(y + 0.05), floor(z));
				}
				continue; // chute : le corps passe à la hauteur courante
			}
			if (ny < y - 0.05 && !bodyFreeAt(w, x, y, z, margin)) {
				return pack(floor(x), floor(y + 0.05), floor(z)); // descente : le corps doit passer à l'ancienne hauteur aussi
			}
			y = ny;
		}
		return NO_BLOCK;
	}

	/** À pied (montée / descente <= 0,6, sol partout) si {@code needSupport}, sinon montées de saut et chutes tolérées. */
	public static boolean segmentWalkable(NavWorld w, double fx, double fy, double fz, double tx, double tz, double margin,
										  boolean needSupport, double maxClimb, double dropReach) {
		if (needSupport) {
			return sweepBlocked(w, fx, fy, fz, tx, tz, margin, STEP_HEIGHT, STEP_HEIGHT, true) == NO_BLOCK;
		}
		return sweepBlocked(w, fx, fy, fz, tx, tz, margin, maxClimb, dropReach, false) == NO_BLOCK;
	}

	/** Rayon (vue / frappe) : bloqué seulement par une boîte de collision CONNUE ; l'inconnu ne bloque pas. */
	public static boolean rayClear(NavWorld w, double fx, double fy, double fz, double tx, double ty, double tz) {
		double dx = tx - fx;
		double dy = ty - fy;
		double dz = tz - fz;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		int steps = Math.max(1, (int) Math.ceil(len / 0.2));
		for (int i = 1; i < steps; i++) {
			double t = (double) i / steps;
			double x = fx + dx * t;
			double y = fy + dy * t;
			double z = fz + dz * t;
			int bx = floor(x);
			int by = floor(y);
			int bz = floor(z);
			if (!known(w, bx, by, bz)) {
				continue;
			}
			float[] boxes = w.boxes(bx, by, bz);
			for (int k = 0; k < boxes.length; k += 6) {
				if (x > bx + boxes[k] && x < bx + boxes[k + 3] && y > by + boxes[k + 1] && y < by + boxes[k + 4]
					&& z > bz + boxes[k + 2] && z < bz + boxes[k + 5]) {
					return false;
				}
			}
		}
		return true;
	}
}
