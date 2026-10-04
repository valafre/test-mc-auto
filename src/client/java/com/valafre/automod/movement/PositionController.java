package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.nav.NavPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Recherche d'une position valide autour d'un point d'ancrage (générique : mécanique, bloc, entité...).
 * Teste 8 directions horizontales (+ "au-dessus"), rejette les positions invalides puis note les survivantes.
 */
public final class PositionController {

	public enum Slot {
		NORTH(0, 0, -1), SOUTH(0, 0, 1), EAST(1, 0, 0), WEST(-1, 0, 0),
		NORTH_EAST(1, 0, -1), NORTH_WEST(-1, 0, -1), SOUTH_EAST(1, 0, 1), SOUTH_WEST(-1, 0, 1),
		ABOVE(0, 1, 0);

		final int dx;
		final int dy;
		final int dz;

		Slot(int dx, int dy, int dz) {
			this.dx = dx;
			this.dy = dy;
			this.dz = dz;
		}
	}

	/**
	 * @param anchor       bloc autour duquel se placer
	 * @param combatTarget position de la cible de combat (nullable) : la position doit rester à portée de combat
	 * @param excluded     positions déjà essayées et échouées
	 */
	public record Request(BlockPos anchor, Vec3 combatTarget, Set<BlockPos> excluded) {}

	/** @param touch la position touche une face du bloc d'ancrage (le joueur doit s'y coller) */
	public record Candidate(BlockPos pos, Slot slot, double score, int pathLength, boolean touch) {
		public Vec3 center() {
			return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		}
	}

	private final PathController paths;

	public PositionController(PathController paths) {
		this.paths = paths;
	}

	// ========================================
	// REPOSITIONING
	// ========================================

	public Optional<Candidate> choose(PlayerState state, Request request) {
		// 1. Positions COLLÉES à une face du bloc (4 côtés + dessus) : le joueur doit vraiment toucher la mécanique.
		Candidate best = search(state, request, true);
		// 2. Repli si aucune n'est possible : diagonales et anneau plus large (sans contact garanti).
		if (best == null) {
			best = search(state, request, false);
		}
		Candidate chosen = best;
		Debug.log("Movement", () -> chosen == null
			? "Aucune position candidate valide"
			: "Position choisie " + chosen.pos() + " (" + chosen.slot() + (chosen.touch() ? ", collée" : "")
				+ ", score=" + String.format("%.1f", chosen.score()) + ")");
		return Optional.ofNullable(best);
	}

	private record Draft(BlockPos pos, Slot slot, double combatDist, double roughScore) {}

	/**
	 * Deux phases pour éviter les rafales d'A* pendant un repositionnement :
	 * 1) filtrage et chemin direct bon marché ; 2) au maximum trois candidats non directs soumis au petit A* synchrone.
	 */
	private Candidate search(PlayerState state, Request request, boolean touchOnly) {
		ModConfig cfg = ModConfig.get();
		Level level = state.level();
		BlockPos playerPos = Walkability.cellOf(state.position());
		List<Draft> drafts = new ArrayList<>();
		Candidate bestDirect = null;
		
		for (Slot slot : Slot.values()) {
			if (slot == Slot.ABOVE && !cfg.allowAbovePosition) {
				continue;
			}
			boolean diagonal = slot.dx != 0 && slot.dz != 0;
			if (touchOnly && diagonal) {
				continue;
			}
			int maxRing = touchOnly || slot == Slot.ABOVE ? 1 : Math.max(1, cfg.positionRingRadius);
			for (int ring = 1; ring <= maxRing; ring++) {
				BlockPos base = slot == Slot.ABOVE
					? request.anchor().above()
					: request.anchor().offset(slot.dx * ring, 0, slot.dz * ring);
				int[] yOffsets = slot == Slot.ABOVE ? new int[] {0} : touchOnly ? new int[] {0, -1} : new int[] {0, 1, -1};
				for (int dy : yOffsets) {
					BlockPos pos = base.above(dy);
					Draft d = draft(state, level, request, slot, pos);
					if (d == null) {
						continue;
					}
					Vec3 player = state.position();
					double standY = Walkability.standHeight(level, pos);
					if (Double.isNaN(standY)) {
						continue;
					}
					Vec3 candidatePos = new Vec3(pos.getX() + 0.5, standY, pos.getZ() + 0.5);
					boolean direct = Math.abs(standY - player.y) <= 0.6
						&& Walkability.segmentWalkable(level, player, candidatePos, 0.12, true);
					if (direct) {
						double score = score(state, level, request, slot, pos,
							Math.max(1, (int) Math.ceil(Math.hypot(candidatePos.x - player.x, candidatePos.z - player.z))), d.combatDist());
						Candidate c = new Candidate(pos, slot, score, Math.max(1, (int) Math.ceil(Math.hypot(candidatePos.x - player.x, candidatePos.z - player.z))), touchOnly);
						if (bestDirect == null || c.score() > bestDirect.score()) {
							bestDirect = c;
						}
					} else {
						drafts.add(d);
					}
				}
			}
		}

		if (bestDirect != null) {
			return bestDirect;
		}

		// Le pathfinding synchrone est uniquement un secours de repositionnement, jamais une boucle A* par candidate.
		drafts.sort(Comparator.comparingDouble(Draft::roughScore).reversed());
		Candidate best = null;
		int pathAttempts = 0;
		for (Draft d : drafts) {
			if (pathAttempts >= 3) {
				break;
			}
			pathAttempts++;
			List<NavPoint> path = paths.findPath(level, playerPos, d.pos(), Math.min(cfg.pathMaxNodes, 350));
			if (path.isEmpty() && !d.pos().equals(playerPos)) {
				continue;
			}
			Candidate c = new Candidate(d.pos(), d.slot(), score(state, level, request, d.slot(), d.pos(), path.size(), d.combatDist()), path.size(), touchOnly);
			if (best == null || c.score() > best.score()) {
				best = c;
			}
		}
		return best;
	}

	private Draft draft(PlayerState state, Level level, Request req, Slot slot, BlockPos pos) {
		ModConfig cfg = ModConfig.get();
		BlockPos anchor = req.anchor();
		if (isUnder(pos, anchor, cfg.underMechanicRadius)) {
			reject(slot, pos, "sous la mécanique");
			return null;
		}
		if (req.excluded().contains(pos)) {
			reject(slot, pos, "déjà essayée");
			return null;
		}
		if (!Walkability.isInWorld(level, pos) || !Walkability.canStandAt(level, pos)) {
			reject(slot, pos, "occupée, sans sol ou hors monde");
			return null;
		}
		double combatDist = 0;
		if (req.combatTarget() != null) {
			Vec3 candidate = new Vec3(pos.getX() + 0.5, Walkability.standHeight(level, pos), pos.getZ() + 0.5);
			combatDist = req.combatTarget().distanceTo(candidate);
			if (combatDist > cfg.positionCombatMaxDistance) {
				reject(slot, pos, "trop loin de la cible de combat");
				return null;
			}
		}
		// Pré-score léger : assez précis pour classer quelques candidats avant l'A*.
		double rough = 100.0 - 1.5 * combatDist - 6.0 * Math.hypot(pos.getX() - anchor.getX(), pos.getZ() - anchor.getZ());
		if (slot == Slot.ABOVE) {
			rough -= 10.0;
		}
		if (pos.getY() < anchor.getY()) {
			rough -= 5.0;
		}
		return new Draft(pos, slot, combatDist, rough);
	}

	private static boolean isUnder(BlockPos pos, BlockPos anchor, double radius) {
		if (pos.getY() >= anchor.getY()) {
			return false;
		}
		double dx = pos.getX() - anchor.getX();
		double dz = pos.getZ() - anchor.getZ();
		return Math.sqrt(dx * dx + dz * dz) < radius;
	}

	/** Plus haut = meilleur. Distance à parcourir, hauteur, obstacles, sécurité, côté du joueur, proximité de la cible. */
	private double score(PlayerState state, Level level, Request req, Slot slot, BlockPos pos, int pathLength, double combatDist) {
		BlockPos anchor = req.anchor();
		double score = 100.0;
		score -= 2.0 * pathLength;                                   // trajet court
		score -= 3.0 * Math.abs(pos.getY() - anchor.getY());         // hauteur proche de la mécanique
		if (pos.getY() < anchor.getY()) {
			score -= 5.0;                                            // plus bas que la mécanique : moins sûr
		}
		if (slot == Slot.ABOVE) {
			score -= 10.0;
		}
		score -= 1.5 * combatDist;                                   // proche de la cible de combat
		score -= 6.0 * Math.hypot(pos.getX() - anchor.getX(), pos.getZ() - anchor.getZ()); // collé à la mécanique

		int walls = 0;
		int hazards = 0;
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos side = pos.relative(dir);
			if (!Walkability.isBodyFree(level, side)) {
				walls++;                                             // obstacle : manoeuvrabilité réduite
			}
			if (!level.getBlockState(side.below()).getFluidState().isEmpty()
				|| Walkability.isHazard(level.getBlockState(side.below()))) {
				hazards++;                                           // liquide/danger au bord de la position
			}
		}
		score -= 1.5 * walls + 8.0 * hazards;

		// Orientation : préfère le côté d'où le joueur arrive (moins de contournement).
		Vec3 toPlayer = state.position().subtract(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
		Vec3 toCandidate = new Vec3(pos.getX() - anchor.getX(), 0, pos.getZ() - anchor.getZ());
		double lp = Math.sqrt(toPlayer.x * toPlayer.x + toPlayer.z * toPlayer.z);
		double lc = toCandidate.length();
		if (lp > 1.0E-3 && lc > 1.0E-3) {
			score += 4.0 * ((toPlayer.x * toCandidate.x + toPlayer.z * toCandidate.z) / (lp * lc));
		}
		return score;
	}

	private static Candidate reject(Slot slot, BlockPos pos, String reason) {
		Debug.log("Movement", () -> "Position candidate rejetée " + slot + " " + pos + " : " + reason);
		return null;
	}
}
