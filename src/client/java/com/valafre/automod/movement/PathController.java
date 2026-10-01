package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Planification GLOBALE (A*) sur des {@link NavPoint} : chaque nœud est une cellule ET la hauteur réelle des pieds (formes de
 * collision). Voisins : avant / arrière / côtés / diagonales (sans couper les coins), montée (marche ou saut avec
 * atterrissage vérifié libre) et descente (chute bornée). Le coût intègre la marge avec les parois, les culs-de-sac
 * (nombre de sorties) et une mémoire des échecs récents qui EXPIRE toujours. Le lissage est fait par {@link PathSmoother}.
 */
public final class PathController {

	/** Jusqu'à cette hauteur on monte en marchant, sans sauter. */
	private static final double WALK_RISE = Walkability.STEP_HEIGHT;
	private static final int[][] DIRECTIONS = {
		{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

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

	/** Cases où le joueur a été refusé / bloqué récemment -> instant d'expiration (ms). Surcoût temporaire, jamais une interdiction. */
	private final Map<BlockPos, Long> failures = new HashMap<>();

	/** Mémorise un échec sur {@code pos} : surcoût pendant {@code navFailureMemoryMs}, puis oubli. */
	public void avoid(BlockPos pos) {
		if (failures.size() > 96) {
			prune();
		}
		failures.put(pos.immutable(), System.currentTimeMillis() + ModConfig.get().navFailureMemoryMs);
	}

	/** Oublie les échecs expirés (les autres restent : l'expiration seule les efface). */
	public void clearAvoid() {
		prune();
	}

	private void prune() {
		long now = System.currentTimeMillis();
		failures.values().removeIf(t -> t < now);
	}

	private boolean recentlyFailed(BlockPos pos) {
		if (failures.isEmpty()) {
			return false;
		}
		Long t = failures.get(pos);
		return t != null && t >= System.currentTimeMillis();
	}

	/** Chemin trouvé : {@code complete} = atteint réellement le but ; sinon, meilleur chemin partiel (point exploré le plus proche du but). */
	public record PathResult(List<NavPoint> path, boolean complete) {
		public static final PathResult NONE = new PathResult(List.of(), false);
	}

	/** @return le chemin COMPLET (départ exclu, but inclus), ou une liste vide si le but est inaccessible (validation d'une position). */
	public List<NavPoint> findPath(Level level, BlockPos start, BlockPos goal, int maxNodes) {
		if (!Walkability.canStandAt(level, goal)) {
			return List.of();
		}
		NavPoint from = Walkability.navPointAt(level, start);
		PathResult result = search(level, from != null ? from : NavPoint.of(start, start.getY(), false), goal, maxNodes);
		return result.complete() ? result.path() : List.of();
	}

	/**
	 * Comme {@link #findPath} mais tolérant : un but non praticable est ramené à la cellule praticable la plus proche, et si
	 * aucun chemin complet n'existe dans la limite de nœuds, on renvoie le chemin vers le point exploré le plus proche du but.
	 * Le départ est la position RÉELLE des pieds.
	 */
	public PathResult findPathBestEffort(Level level, Vec3 feet, BlockPos goal, int maxNodes) {
		BlockPos target = nearestStandable(level, goal, 3);
		if (target == null) {
			return PathResult.NONE;
		}
		return search(level, NavPoint.of(Walkability.cellOf(feet), feet.y, false), target, maxNodes);
	}

	/** Cellule praticable la plus proche de {@code pos} (rayon horizontal {@code radius}, +/-2 en hauteur), ou null. */
	public BlockPos nearestStandable(Level level, BlockPos pos, int radius) {
		if (Walkability.canStandAt(level, pos)) {
			return pos;
		}
		BlockPos best = null;
		int bestScore = Integer.MAX_VALUE;
		for (int dy = -2; dy <= 2; dy++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos candidate = pos.offset(dx, dy, dz);
					int score = dx * dx + dz * dz + dy * dy * 2;
					if (score < bestScore && Walkability.canStandAt(level, candidate)) {
						bestScore = score;
						best = candidate;
					}
				}
			}
		}
		return best;
	}

	private PathResult search(Level level, NavPoint start, BlockPos goal, int maxNodes) {
		prune();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Map<Long, Node> nodes = new HashMap<>();
		Node first = new Node(start, null, 0, heuristic(start.cell(), goal));
		open.add(first);
		nodes.put(start.cell().asLong(), first);

		Node closest = first;
		double closestH = first.f;
		int expanded = 0;
		while (!open.isEmpty() && expanded < maxNodes) {
			Node current = open.poll();
			if (current.closed) {
				continue;
			}
			current.closed = true;
			expanded++;
			if (current.pt.cell().equals(goal)) {
				return new PathResult(reconstruct(current), true);
			}
			double h = current.f - current.g;
			if (h < closestH) {
				closestH = h;
				closest = current;
			}
			for (int[] dir : DIRECTIONS) {
				expand(level, current, dir[0], dir[1], goal, open, nodes);
			}
		}
		// But non atteint : chemin partiel vers le point le plus proche du but (s'il y a eu un vrai progrès).
		return closest == first ? PathResult.NONE : new PathResult(reconstruct(closest), false);
	}

	private void expand(Level level, Node current, int dx, int dz, BlockPos goal,
						PriorityQueue<Node> open, Map<Long, Node> nodes) {
		ModConfig cfg = ModConfig.get();
		NavPoint base = current.pt;
		boolean diagonal = dx != 0 && dz != 0;
		double h = base.feetY(); // hauteur RÉELLE des pieds, jamais le numéro de cellule
		double bx = base.x();
		double bz = base.z();
		// Pas de coin coupé : les deux colonnes latérales doivent laisser passer le corps à cette hauteur.
		if (diagonal && (!passable(level, bx + dx, h, bz) || !passable(level, bx, h, bz + dz))) {
			return;
		}
		double sx = bx + dx;
		double sz = bz + dz;
		double cost = diagonal ? 1.414 : 1.0;
		// Surface praticable de la colonne voisine la plus proche de la hauteur actuelle (marche, demi-dalle, saut, chute).
		double top = Walkability.feetHeightAt(level, sx, sz, h, cfg.navMaxClimb, cfg.maxDropBlocks + 0.5, 0.0);
		if (Double.isNaN(top)) {
			return;
		}
		double rise = top - h;
		boolean jump = false;
		if (rise > WALK_RISE) {
			// Saut : la tête doit avoir la place de monter, et l'atterrissage est libre (vérifié par feetHeightAt).
			if (!Walkability.bodyFreeAt(level, bx, h + Math.min(rise, 1.0), bz, 0.0)) {
				return;
			}
			jump = true;
			cost += 0.5;
		} else if (rise < -WALK_RISE) {
			// Chute : le corps doit pouvoir descendre tout le long de la colonne voisine.
			for (double y = h; y > top; y -= 1.0) {
				if (!Walkability.bodyFreeAt(level, sx, y, sz, 0.0)) {
					return;
				}
			}
			cost += 0.5 * Math.ceil(-rise);
		}
		NavPoint next = new NavPoint(Walkability.cellOf(new Vec3(sx, top, sz)), sx, top, sz, jump);
		// Le trajet réel doit être praticable (coins, plafonds de marche...) : on balaie diagonales, montées et descentes.
		if (diagonal || Math.abs(rise) > 0.05) {
			if (!Walkability.segmentWalkable(level, base.vec(), next.vec(), 0.02, false)) {
				com.valafre.automod.debug.StepTrace.astarReject(level, base.cell(), next.cell(), base.vec(), next.vec());
				return;
			}
		}
		cost += Math.abs(rise) * 0.3; // coût vertical
		cost += clearancePenalty(level, next) * cfg.navClearanceWeight / 1.5;
		if (!next.cell().equals(goal)) {
			cost += deadEndPenalty(level, next, cfg.navDeadEndPenalty);
		}
		if (recentlyFailed(next.cell())) {
			cost += 8.0;
		}
		double g = current.g + cost;
		Node known = nodes.get(next.cell().asLong());
		if (known != null && (known.closed || known.g <= g)) {
			return;
		}
		Node node = new Node(next, current, g, g + heuristic(next.cell(), goal));
		nodes.put(next.cell().asLong(), node);
		open.add(node);
	}

	/** Le corps passe-t-il dans la colonne (x, z) à la hauteur h, ou y a-t-il une marche praticable à cet endroit ? */
	private static boolean passable(Level level, double x, double h, double z) {
		return Walkability.bodyFreeAt(level, x, h, z, 0.0)
			|| !Double.isNaN(Walkability.feetHeightAt(level, x, z, h, WALK_RISE, WALK_RISE, 0.0));
	}

	/** Nombre de sorties d'un point (avancer à plat, monter ou descendre dans une des 4 directions). */
	private static int exits(Level level, double x, double z, double h) {
		int exits = 0;
		for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			double sx = x + dir.getStepX();
			double sz = z + dir.getStepZ();
			if (Walkability.bodyFreeAt(level, sx, h, sz, 0.0)
				|| !Double.isNaN(Walkability.feetHeightAt(level, sx, sz, h, ModConfig.get().navMaxClimb, WALK_RISE, 0.0))) {
				exits++;
			}
		}
		return exits;
	}

	/** Cellule sans véritable issue (une seule sortie ou aucune) : renfoncement, bout de couloir. */
	public static boolean isDeadEnd(Level level, BlockPos pos) {
		return exits(level, pos.getX() + 0.5, pos.getZ() + 0.5, Walkability.standHeight(level, pos)) <= 1;
	}

	/** 0 sortie = très mauvais, 1 sortie = pénalisé, 2+ = normal. */
	private static double deadEndPenalty(Level level, NavPoint p, double weight) {
		int e = exits(level, p.x(), p.z(), p.feetY());
		return e == 0 ? weight * 2.0 : e == 1 ? weight : 0.0;
	}

	/**
	 * Marge de sécurité : surcoût pour les points collés à un mur, dans un angle ou au bord du vide. Les chemins restent
	 * ainsi à distance des obstacles quand il y a de la place, et un couloir étroit n'est pris que faute de mieux.
	 * « Mur » = colonne où le corps ne passe pas à la hauteur réelle des pieds (une demi-dalle n'est pas un mur).
	 */
	private static double clearancePenalty(Level level, NavPoint p) {
		double h = p.feetY();
		double penalty = 0;
		int solidSides = 0;
		for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			double x = p.x() + dir.getStepX();
			double z = p.z() + dir.getStepZ();
			if (!Walkability.bodyFreeAt(level, x, h, z, 0.0)) {
				penalty += 0.5;
				solidSides++;
			} else if (Double.isNaN(Walkability.feetHeightAt(level, x, z, h, WALK_RISE, WALK_RISE, 0.0))) {
				penalty += 0.4; // bord du vide : on évite de longer un précipice
			}
		}
		if (solidSides >= 2) {
			penalty += 0.5; // angle ou couloir : on s'y coince plus facilement
		}
		for (int[] d : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
			if (!Walkability.bodyFreeAt(level, p.x() + d[0], h, p.z() + d[1], 0.0)) {
				penalty += 0.2;
			}
		}
		return penalty;
	}

	private static double heuristic(BlockPos a, BlockPos b) {
		int dx = Math.abs(a.getX() - b.getX());
		int dz = Math.abs(a.getZ() - b.getZ());
		int dy = Math.abs(a.getY() - b.getY());
		// Distance octile + pénalité verticale.
		return (dx + dz) + (1.414 - 2) * Math.min(dx, dz) + dy;
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
	// LIGNE DROITE
	// ========================================

	/**
	 * La ligne droite from -> to est-elle franchissable à plat ? Échantillonne tous les 0.4 bloc : corps sans collision
	 * et sol présent sous les pieds (pas de trou). Une différence de hauteur > 0.6 impose de passer par le pathfinding.
	 */
	public boolean isClearLine(Level level, Vec3 from, Vec3 to) {
		if (Math.abs(to.y - from.y) > 0.6) {
			return false;
		}
		// Boîte réelle du joueur à chaque point (plus une marge de sécurité) : une ligne qui frôle un coin n'est pas "claire".
		return Walkability.segmentWalkable(level, from, to, Math.max(LINE_MARGIN, com.valafre.automod.config.ModConfig.get().navSafetyMargin), true);
	}

	private static final double LINE_MARGIN = 0.12;
}
