package com.valafre.automod.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Recherche de chemin locale (A*) sur la grille de blocs, bornée en nœuds explorés.
 * Sert à la fois à vérifier l'accessibilité d'une position et à guider le déplacement quand la ligne droite est bloquée.
 * Un nœud = case des pieds où le joueur peut se tenir ; pas de montée de 1 bloc, chute jusqu'à 3 blocs.
 */
public final class PathController {

	/** Dénivelé maximal franchissable en sautant (le saut monte d'environ 1,25). */
	private static final double MAX_RISE = 1.2;
	/** Jusqu'à cette hauteur on monte en marchant, sans sauter. */
	private static final double WALK_RISE = 0.6;
	private static final int[][] DIRECTIONS = {
		{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private static final class Node {
		final BlockPos pos;
		Node parent;
		double g;
		double f;
		boolean closed;

		Node(BlockPos pos, Node parent, double g, double f) {
			this.pos = pos;
			this.parent = parent;
			this.g = g;
			this.f = f;
		}
	}

	/** Chemin trouvé : {@code complete} = atteint réellement le but ; sinon, meilleur chemin partiel (case explorée la plus proche du but). */
	/** Cases à éviter (où le joueur s'est bloqué) : fort surcoût, jamais interdites pour ne pas rendre le chemin impossible. */
	private final java.util.Set<BlockPos> avoid = new java.util.HashSet<>();

	public void avoid(BlockPos pos) {
		if (avoid.size() > 64) {
			avoid.clear();
		}
		avoid.add(pos.immutable());
	}

	public void clearAvoid() {
		avoid.clear();
	}

	public record PathResult(List<BlockPos> path, boolean complete) {
		public static final PathResult NONE = new PathResult(List.of(), false);
	}

	/** @return le chemin COMPLET (départ exclu, but inclus), ou une liste vide si le but est inaccessible (utilisé pour valider une position). */
	public List<BlockPos> findPath(Level level, BlockPos start, BlockPos goal, int maxNodes) {
		if (!Walkability.canStandAt(level, goal)) {
			return List.of();
		}
		PathResult result = search(level, start, goal, maxNodes);
		return result.complete() ? result.path() : List.of();
	}

	/**
	 * Comme {@link #findPath} mais tolérant : un but non praticable est ramené à la case praticable la plus proche, et si
	 * aucun chemin complet n'existe dans la limite de nœuds, on renvoie le chemin vers la case explorée la plus proche du but
	 * (le joueur se rapproche puis un nouveau calcul prend le relais).
	 */
	public PathResult findPathBestEffort(Level level, BlockPos start, BlockPos goal, int maxNodes) {
		BlockPos target = nearestStandable(level, goal, 3);
		if (target == null) {
			return PathResult.NONE;
		}
		return search(level, start, target, maxNodes);
	}

	/** Case praticable la plus proche de {@code pos} (rayon horizontal {@code radius}, +/-2 en hauteur), ou null. */
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

	private PathResult search(Level level, BlockPos start, BlockPos goal, int maxNodes) {
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Map<Long, Node> nodes = new HashMap<>();
		Node first = new Node(start, null, 0, heuristic(start, goal));
		open.add(first);
		nodes.put(start.asLong(), first);

		Node closest = first;
		double closestH = heuristic(start, goal);
		int expanded = 0;
		while (!open.isEmpty() && expanded < maxNodes) {
			Node current = open.poll();
			if (current.closed) {
				continue;
			}
			current.closed = true;
			expanded++;
			if (current.pos.equals(goal)) {
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
		// But non atteint : chemin partiel vers la case la plus proche du but (s'il y a eu un vrai progrès).
		return closest == first ? PathResult.NONE : new PathResult(reconstruct(closest), false);
	}

	private void expand(Level level, Node current, int dx, int dz, BlockPos goal,
						PriorityQueue<Node> open, Map<Long, Node> nodes) {
		BlockPos base = current.pos;
		boolean diagonal = dx != 0 && dz != 0;
		// Interdit de "couper" un coin : les deux cases orthogonales doivent être libres.
		if (diagonal && (!Walkability.isBodyFree(level, base.offset(dx, 0, 0))
			|| !Walkability.isBodyFree(level, base.offset(0, 0, dz)))) {
			return;
		}
		BlockPos side = base.offset(dx, 0, dz);
		double cost = diagonal ? 1.414 : 1.0;
		BlockPos next = null;

		if (Walkability.canStandAt(level, side)) {
			// Hauteur réelle des surfaces (demi-dalles...) : un "bloc de plus" peut valoir 0,5 ou 1,5.
			double rise = Walkability.standHeight(level, side) - Walkability.standHeight(level, base);
			if (rise <= MAX_RISE) {
				next = side;
				if (rise > WALK_RISE) {
					cost += 0.5; // il faudra sauter
				}
			}
		} else if (Walkability.isBodyFree(level, side)) {
			// Vide devant : descente (chute jusqu'à MAX_DROP blocs).
			for (int k = 1; k <= com.valafre.automod.config.ModConfig.get().maxDropBlocks; k++) {
				BlockPos lower = side.below(k);
				if (Walkability.canStandAt(level, lower)) {
					next = lower;
					cost += 0.5 * k;
					break;
				}
				if (!Walkability.isBodyFree(level, lower)) {
					break;
				}
			}
		} else {
			// Obstacle devant : montée d'un bloc si la tête a la place de sauter.
			BlockPos up = side.above();
			if (Walkability.canStandAt(level, up) && Walkability.isBodyFree(level, base.above())
				&& Walkability.standHeight(level, up) - Walkability.standHeight(level, base) <= MAX_RISE) {
				next = up;
				cost += 0.5;
			}
		}
		if (next == null) {
			return;
		}
		cost += wallPenalty(level, next);
		if (!avoid.isEmpty() && avoid.contains(next)) {
			cost += 8.0;
		}
		double g = current.g + cost;
		Node known = nodes.get(next.asLong());
		if (known != null && (known.closed || known.g <= g)) {
			return;
		}
		Node node = new Node(next, current, g, g + heuristic(next, goal));
		nodes.put(next.asLong(), node);
		open.add(node);
	}

	/** Petit surcoût pour les cases collées à un mur : les chemins restent naturellement à distance des parois. */
	private static double wallPenalty(Level level, BlockPos pos) {
		double penalty = 0;
		for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos side = pos.relative(dir);
			if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
				penalty += 0.3;
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

	private static List<BlockPos> reconstruct(Node end) {
		List<BlockPos> path = new ArrayList<>();
		for (Node n = end; n.parent != null; n = n.parent) {
			path.add(n.pos);
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
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dz * dz);
		int samples = Math.max(1, (int) Math.ceil(length / 0.4));
		for (int i = 1; i <= samples; i++) {
			double t = (double) i / samples;
			BlockPos feet = BlockPos.containing(from.x + dx * t, from.y + 0.05, from.z + dz * t);
			if (!Walkability.isInWorld(level, feet) || !Walkability.isBodyFree(level, feet)) {
				return false;
			}
			BlockPos floor = feet.below();
			if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) {
				return false;
			}
		}
		return true;
	}
}
