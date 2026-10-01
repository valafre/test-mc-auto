package com.valafre.automod.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Moteur de chemin global INDÉPENDANT de Minecraft : A* sur {@link NavPoint} (cellule + hauteur réelle des pieds) puis lissage.
 * Ne lit que le {@link NavWorld} reçu (snapshot). Borné en nœuds ET en temps ({@code timeBudgetMs}) : rend toujours la main et
 * renvoie COMPLETE, PARTIAL (meilleur point exploré) ou FAILED.
 *
 * <p>Voisins : 8 directions (diagonales sans coin coupé), marche (&lt;= 0,6), saut (&lt;= maxClimb, atterrissage libre), chute
 * bornée. Coût : distance + dénivelé + marge avec les parois + culs-de-sac (0 sortie très pénalisé, 1 sortie pénalisé) + cases
 * en échec récent (surcoût temporaire, jamais interdites).
 */
public final class PathEngine {

	private static final double WALK_RISE = NavGeometry.STEP_HEIGHT;
	private static final int[][] DIRECTIONS = {
		{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};
	private static final double LINE_MARGIN = 0.12;
	private static final int SMOOTH_MAX_SKIP = 14;

	private PathEngine() {}

	private static final class Node {
		final NavPoint pt;
		Node parent;
		double g;
		double f;
		boolean closed;

		Node(NavPoint pt, Node parent, double g, double f) {
			this.pt = pt;
			this.parent = parent;
			this.g = g;
			this.f = f;
		}
	}

	public static PathResult search(PathRequest req) {
		long t0 = System.nanoTime();
		NavWorld w = req.world();
		long checks0 = w instanceof NavGrid g ? g.checks() : 0;
		NavParams p = req.params();
		long deadline = t0 + (long) (p.timeBudgetMs() * 1_000_000.0);

		// Ligne droite praticable : un seul point, aucun A*.
		double dyLine = req.goalY() - req.startY();
		if (Math.abs(dyLine) <= WALK_RISE
			&& NavGeometry.segmentWalkable(w, req.startX(), req.startY(), req.startZ(), req.goalX(), req.goalZ(),
				Math.max(LINE_MARGIN, p.safetyMargin()), true, p.maxClimb(), p.maxDrop() + 0.5)) {
			NavPoint end = new NavPoint(req.goalCx(), req.goalCy(), req.goalCz(), req.goalX(), req.goalY(), req.goalZ(), false);
			return result(req, PathResult.Status.COMPLETE, List.of(end), true, req.goalCx(), req.goalCy(), req.goalCz(), 0, w, checks0, t0, 0);
		}

		int[] goal = nearestStandable(w, req.goalCx(), req.goalCy(), req.goalCz(), 3);
		if (goal == null) {
			return result(req, PathResult.Status.FAILED, List.of(), false, req.goalCx(), req.goalCy(), req.goalCz(), 0, w, checks0, t0, 0);
		}
		Set<Long> failed = new HashSet<>();
		for (long l : req.failedCells()) {
			failed.add(l);
		}

		int scx = NavGeometry.floor(req.startX());
		int scy = NavGeometry.cellY(req.startY());
		int scz = NavGeometry.floor(req.startZ());
		NavPoint start = NavPoint.of(scx, scy, scz, req.startY(), false);
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Map<Long, Node> nodes = new HashMap<>();
		Map<Long, Double> penalties = new HashMap<>();
		Node first = new Node(start, null, 0, heuristic(scx, scy, scz, goal));
		open.add(first);
		nodes.put(start.cellKey(), first);

		Node closest = first;
		double closestH = first.f;
		int expanded = 0;
		boolean complete = false;
		Node found = null;
		while (!open.isEmpty() && expanded < p.maxNodes()) {
			if ((expanded & 15) == 0 && System.nanoTime() > deadline) {
				break; // budget de temps épuisé : on rend le meilleur résultat disponible
			}
			Node current = open.poll();
			if (current.closed) {
				continue;
			}
			current.closed = true;
			expanded++;
			NavPoint c = current.pt;
			if (c.cx() == goal[0] && c.cy() == goal[1] && c.cz() == goal[2]) {
				complete = true;
				found = current;
				break;
			}
			double h = current.f - current.g;
			if (h < closestH) {
				closestH = h;
				closest = current;
			}
			for (int[] dir : DIRECTIONS) {
				expand(w, p, current, dir[0], dir[1], goal, failed, open, nodes, penalties);
			}
		}
		Node endNode = complete ? found : closest;
		if (endNode == null || endNode == first) {
			return result(req, PathResult.Status.FAILED, List.of(), false, goal[0], goal[1], goal[2], expanded, w, checks0, t0, 0);
		}
		List<NavPoint> path = reconstruct(endNode);
		long s0 = System.nanoTime();
		if (p.smoothing()) {
			path = smooth(w, req.startX(), req.startY(), req.startZ(), path, p.safetyMargin());
		}
		double smoothMs = (System.nanoTime() - s0) / 1.0E6;
		return result(req, complete ? PathResult.Status.COMPLETE : PathResult.Status.PARTIAL, path, false,
			goal[0], goal[1], goal[2], expanded, w, checks0, t0, smoothMs);
	}

	private static PathResult result(PathRequest req, PathResult.Status status, List<NavPoint> pts, boolean direct,
									 int gx, int gy, int gz, int nodes, NavWorld w, long checks0, long t0, double smoothMs) {
		long checks = (w instanceof NavGrid g ? g.checks() : 0) - checks0;
		return new PathResult(req.id(), req.generation(), status, List.copyOf(pts), direct, gx, gy, gz, nodes, checks,
			(System.nanoTime() - t0) / 1.0E6, smoothMs, System.currentTimeMillis());
	}

	private static void expand(NavWorld w, NavParams p, Node current, int dx, int dz, int[] goal, Set<Long> failed,
							   PriorityQueue<Node> open, Map<Long, Node> nodes, Map<Long, Double> penalties) {
		NavPoint base = current.pt;
		boolean diagonal = dx != 0 && dz != 0;
		double h = base.feetY(); // hauteur RÉELLE des pieds, jamais le numéro de cellule
		double bx = base.x();
		double bz = base.z();
		// Pas de coin coupé : les deux colonnes latérales doivent laisser passer le corps à cette hauteur.
		if (diagonal && (!passable(w, bx + dx, h, bz) || !passable(w, bx, h, bz + dz))) {
			return;
		}
		double sx = bx + dx;
		double sz = bz + dz;
		double cost = diagonal ? 1.414 : 1.0;
		double top = NavGeometry.feetHeightAt(w, sx, sz, h, p.maxClimb(), p.maxDrop() + 0.5, 0.0);
		if (Double.isNaN(top)) {
			return;
		}
		double rise = top - h;
		boolean jump = false;
		if (rise > WALK_RISE) {
			// Saut : la tête doit avoir la place de monter, et l'atterrissage est libre (vérifié par feetHeightAt).
			if (!NavGeometry.bodyFreeAt(w, bx, h + Math.min(rise, 1.0), bz, 0.0)) {
				return;
			}
			jump = true;
			cost += 0.5;
		} else if (rise < -WALK_RISE) {
			// Chute : le corps doit pouvoir descendre tout le long de la colonne voisine.
			for (double y = h; y > top; y -= 1.0) {
				if (!NavGeometry.bodyFreeAt(w, sx, y, sz, 0.0)) {
					return;
				}
			}
			cost += 0.5 * Math.ceil(-rise);
		}
		NavPoint next = new NavPoint(NavGeometry.floor(sx), NavGeometry.cellY(top), NavGeometry.floor(sz), sx, top, sz, jump);
		// Le trajet réel doit être praticable (coins, plafonds de marche...) : on balaie diagonales, montées et descentes.
		if ((diagonal || Math.abs(rise) > 0.05)
			&& !NavGeometry.segmentWalkable(w, bx, h, bz, sx, sz, 0.02, false, p.maxClimb(), p.maxDrop() + 0.5)) {
			return;
		}
		cost += Math.abs(rise) * 0.3; // coût vertical
		long key = next.cellKey();
		Node known = nodes.get(key);
		// Les pénalités ne font qu'AUGMENTER le coût : si le nœud est déjà fermé ou déjà atteint moins cher, inutile de les calculer.
		if (known != null && (known.closed || known.g <= current.g + cost)) {
			return;
		}
		Double cached = penalties.get(key);
		if (cached == null) {
			double pen = clearancePenalty(w, next) * p.clearanceWeight() / 1.5;
			boolean isGoal = next.cx() == goal[0] && next.cy() == goal[1] && next.cz() == goal[2];
			if (!isGoal) {
				pen += deadEndPenalty(w, next, p);
			}
			if (!failed.isEmpty() && failed.contains(key)) {
				pen += 8.0;
			}
			cached = pen;
			penalties.put(key, cached);
		}
		cost += cached;
		double g = current.g + cost;
		if (known != null && known.g <= g) {
			return;
		}
		Node node = new Node(next, current, g, g + heuristic(next.cx(), next.cy(), next.cz(), goal));
		nodes.put(key, node);
		open.add(node);
	}

	private static boolean passable(NavWorld w, double x, double h, double z) {
		return NavGeometry.bodyFreeAt(w, x, h, z, 0.0)
			|| !Double.isNaN(NavGeometry.feetHeightAt(w, x, z, h, WALK_RISE, WALK_RISE, 0.0));
	}

	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private static int exits(NavWorld w, double x, double z, double h, double maxClimb) {
		int exits = 0;
		for (int[] d : SIDES) {
			double sx = x + d[0];
			double sz = z + d[1];
			if (NavGeometry.bodyFreeAt(w, sx, h, sz, 0.0)
				|| !Double.isNaN(NavGeometry.feetHeightAt(w, sx, sz, h, maxClimb, WALK_RISE, 0.0))) {
				exits++;
			}
		}
		return exits;
	}

	/** Cellule sans véritable issue (une seule sortie ou aucune). */
	public static boolean isDeadEnd(NavWorld w, int cx, int cy, int cz, double maxClimb) {
		return exits(w, cx + 0.5, cz + 0.5, NavGeometry.standHeight(w, cx, cy, cz), maxClimb) <= 1;
	}

	/** 0 sortie = très mauvais, 1 sortie = pénalisé, 2+ = normal. */
	private static double deadEndPenalty(NavWorld w, NavPoint pt, NavParams p) {
		int e = exits(w, pt.x(), pt.z(), pt.feetY(), p.maxClimb());
		return e == 0 ? p.deadEndPenalty() * 2.0 : e == 1 ? p.deadEndPenalty() : 0.0;
	}

	/** Surcoût des points collés à un mur, dans un angle ou au bord du vide (une demi-dalle n'est pas un mur). */
	private static double clearancePenalty(NavWorld w, NavPoint pt) {
		double h = pt.feetY();
		double penalty = 0;
		int solidSides = 0;
		for (int[] d : SIDES) {
			double x = pt.x() + d[0];
			double z = pt.z() + d[1];
			if (!NavGeometry.bodyFreeAt(w, x, h, z, 0.0)) {
				penalty += 0.5;
				solidSides++;
			} else if (Double.isNaN(NavGeometry.feetHeightAt(w, x, z, h, WALK_RISE, WALK_RISE, 0.0))) {
				penalty += 0.4; // bord du vide
			}
		}
		if (solidSides >= 2) {
			penalty += 0.5; // angle ou couloir
		}
		for (int[] d : DIRECTIONS) {
			if (d[0] != 0 && d[1] != 0 && !NavGeometry.bodyFreeAt(w, pt.x() + d[0], h, pt.z() + d[1], 0.0)) {
				penalty += 0.2;
			}
		}
		return penalty;
	}

	private static double heuristic(int x, int y, int z, int[] goal) {
		int dx = Math.abs(x - goal[0]);
		int dz = Math.abs(z - goal[2]);
		int dy = Math.abs(y - goal[1]);
		return (dx + dz) + (1.414 - 2) * Math.min(dx, dz) + dy;
	}

	/** Cellule praticable la plus proche du but (rayon horizontal {@code radius}, +/-2 en hauteur), ou null. */
	public static int[] nearestStandable(NavWorld w, int cx, int cy, int cz, int radius) {
		if (NavGeometry.canStandAt(w, cx, cy, cz)) {
			return new int[] {cx, cy, cz};
		}
		int[] best = null;
		int bestScore = Integer.MAX_VALUE;
		for (int dy = -2; dy <= 2; dy++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					int score = dx * dx + dz * dz + dy * dy * 2;
					if (score < bestScore && NavGeometry.canStandAt(w, cx + dx, cy + dy, cz + dz)) {
						bestScore = score;
						best = new int[] {cx + dx, cy + dy, cz + dz};
					}
				}
			}
		}
		return best;
	}

	private static List<NavPoint> reconstruct(Node end) {
		List<NavPoint> path = new ArrayList<>();
		for (Node n = end; n.parent != null; n = n.parent) {
			path.add(n.pt);
		}
		Collections.reverse(path);
		return path;
	}

	// ========================================
	// LISSAGE
	// ========================================

	/**
	 * Supprime les points intermédiaires tant que le tronçon est praticable à pied avec marge (hauteur des pieds suivant le
	 * vrai sol). Les points de saut ou de chute sont conservés.
	 */
	public static List<NavPoint> smooth(NavWorld w, double sx, double sy, double sz, List<NavPoint> path, double margin) {
		if (path.size() < 2) {
			return path;
		}
		double m = Math.max(margin, LINE_MARGIN);
		List<NavPoint> out = new ArrayList<>();
		double fx = sx;
		double fy = sy;
		double fz = sz;
		int i = 0;
		while (i < path.size()) {
			int best = i;
			int limit = Math.min(path.size() - 1, i + SMOOTH_MAX_SKIP);
			for (int j = limit; j > i; j--) {
				if (canSkipTo(w, fx, fy, fz, path, i, j, m)) {
					best = j;
					break;
				}
			}
			NavPoint kept = path.get(best);
			out.add(kept);
			fx = kept.x();
			fy = kept.feetY();
			fz = kept.z();
			i = best + 1;
		}
		return out;
	}

	private static boolean canSkipTo(NavWorld w, double fx, double fy, double fz, List<NavPoint> path, int i, int j, double margin) {
		for (int k = i; k < j; k++) {
			if (path.get(k).requiresJump()) {
				return false;
			}
		}
		NavPoint t = path.get(j);
		return !t.requiresJump() && NavGeometry.segmentWalkable(w, fx, fy, fz, t.x(), t.z(), margin, true, 0, 0);
	}
}
