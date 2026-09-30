package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.PlayerState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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

	public record Candidate(BlockPos pos, Slot slot, double score, int pathLength) {
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
		ModConfig cfg = ModConfig.get();
		Level level = state.level();
		BlockPos playerPos = state.player().blockPosition();
		Candidate best = null;

		for (Slot slot : Slot.values()) {
			if (slot == Slot.ABOVE && !cfg.allowAbovePosition) {
				continue;
			}
			BlockPos base = slot == Slot.ABOVE
				? request.anchor().above()
				: request.anchor().offset(slot.dx * cfg.positionRingRadius, 0, slot.dz * cfg.positionRingRadius);
			// Sol non plat : on teste le niveau de la mécanique, puis une marche en haut, puis une marche en bas.
			int[] yOffsets = slot == Slot.ABOVE ? new int[] {0} : new int[] {0, 1, -1};
			for (int dy : yOffsets) {
				Candidate c = evaluate(state, level, playerPos, request, slot, base.above(dy));
				if (c != null && (best == null || c.score() > best.score())) {
					best = c;
				}
			}
		}
		Candidate chosen = best;
		Debug.log("Movement", () -> chosen == null
			? "Aucune position candidate valide"
			: "Position choisie " + chosen.pos() + " (" + chosen.slot() + ", score=" + String.format("%.1f", chosen.score()) + ")");
		return Optional.ofNullable(best);
	}

	/** @return la position notée, ou null si rejetée (la raison est loguée en debug). */
	private Candidate evaluate(PlayerState state, Level level, BlockPos playerPos, Request req, Slot slot, BlockPos pos) {
		ModConfig cfg = ModConfig.get();
		BlockPos anchor = req.anchor();

		// 1. Règle absolue : jamais directement SOUS la mécanique (Y inférieur ET dans son emprise horizontale).
		if (isUnder(pos, anchor, cfg.underMechanicRadius)) {
			return reject(slot, pos, "sous la mécanique");
		}
		// 2. Déjà essayée sans succès.
		if (req.excluded().contains(pos)) {
			return reject(slot, pos, "déjà essayée");
		}
		// 3. Dans le monde + libre de collision + sol solide.
		if (!Walkability.isInWorld(level, pos)) {
			return reject(slot, pos, "hors du monde / chunk non chargé");
		}
		if (!Walkability.canStandAt(level, pos)) {
			return reject(slot, pos, "occupée ou sans sol");
		}
		// 4. Compatible avec le combat : proche de la cible.
		double combatDist = 0;
		if (req.combatTarget() != null) {
			combatDist = req.combatTarget().distanceTo(new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5));
			if (combatDist > cfg.positionCombatMaxDistance) {
				return reject(slot, pos, "trop loin de la cible de combat");
			}
		}
		// 5. Accessible à pied (A* borné).
		List<BlockPos> path = paths.findPath(level, playerPos, pos, cfg.pathMaxNodes);
		if (path.isEmpty() && !pos.equals(playerPos)) {
			return reject(slot, pos, "inaccessible");
		}

		return new Candidate(pos, slot, score(state, level, req, slot, pos, path.size(), combatDist), path.size());
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
