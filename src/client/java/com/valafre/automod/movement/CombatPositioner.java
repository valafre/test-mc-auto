package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.PlayerState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Choisit la POSITION DE COMBAT : l'endroit, autour de la cible, où le joueur peut se tenir, voir la cible, la frapper et
 * continuer à bouger. La destination logique n'est pas la case de la cible (souvent inaccessible ou au-dessus d'un bloc)
 * mais cette position. Le choix est conservé (hystérésis) tant qu'il reste bon et n'est refait que si la cible a
 * sensiblement bougé, si la position devient invalide ou périodiquement.
 *
 * <p>Critères : praticable, à portée d'attaque, cible visible depuis la position, issue de sortie, éloignée des parois,
 * du côté d'où l'on vient (pas de détour inutile), atteignable en ligne droite si possible.
 */
public final class CombatPositioner {

	/** Dernière hauteur de pieds choisie par un positionneur (diagnostic uniquement). */
	public static volatile double debugLastStandY = Double.NaN;

	private static final int ANGLE_STEPS = 12;
	private static final int REEVAL_TICKS = 10;
	private static final double TARGET_MOVED_SQ = 2.0 * 2.0;
	private static final double SAME_CELL_BONUS = 2.5;

	private BlockPos chosenCell;
	private Vec3 chosen;
	private Vec3 targetAtEval;
	private int ticksSinceEval = 99;
	private boolean reevaluatedThisCall;

	public void reset() {
		chosenCell = null;
		chosen = null;
		targetAtEval = null;
		ticksSinceEval = 99;
	}

	/** @return la position de combat à rejoindre, ou null si aucune n'est viable (on retombe alors sur la position de la cible). */
	public Vec3 choose(PlayerState state, Entity target) {
		Level level = state.level();
		reevaluatedThisCall = false;
		boolean stale = chosen == null || ++ticksSinceEval >= REEVAL_TICKS
			|| targetAtEval == null || targetAtEval.distanceToSqr(target.position()) > TARGET_MOVED_SQ;
		if (!stale && !Walkability.canStandAt(level, chosenCell)) {
			stale = true;
		}
		if (stale) {
			reevaluatedThisCall = true;
			evaluate(state, target);
			ticksSinceEval = 0;
			targetAtEval = target.position();
		}
		return chosen;
	}

	// Diagnostic (lecture seule)
	public Vec3 debugChosen() {
		return chosen;
	}

	public int debugAgeTicks() {
		return ticksSinceEval;
	}

	public boolean debugReevaluated() {
		return reevaluatedThisCall;
	}

	public Vec3 debugTargetAtEval() {
		return targetAtEval;
	}

	private void evaluate(PlayerState state, Entity target) {
		ModConfig cfg = ModConfig.get();
		Level level = state.level();
		Vec3 player = state.position();
		Vec3 tpos = target.position();
		AABB box = target.getBoundingBox();
		Vec3 aim = new Vec3(box.getCenter().x, box.minY + box.getYsize() * 0.7, box.getCenter().z);
		double[] radii = {cfg.combatMinDistance + 0.3, cfg.approachDistance};
		double toPlayerAngle = Math.atan2(player.z - tpos.z, player.x - tpos.x);

		double bestScore = -1e9;
		BlockPos bestCell = null;
		Vec3 bestPos = null;
		for (int a = 0; a < ANGLE_STEPS; a++) {
			double angle = a * (Math.PI * 2 / ANGLE_STEPS);
			for (double r : radii) {
				double x = tpos.x + Math.cos(angle) * r;
				double z = tpos.z + Math.sin(angle) * r;
				BlockPos cell = standableCell(level, x, tpos.y, z);
				if (cell == null) {
					continue;
				}
				double standY = Walkability.standHeight(level, cell);
				Vec3 pos = new Vec3(cell.getX() + 0.5, standY, cell.getZ() + 0.5);
				double distToTarget = Math.hypot(pos.x - tpos.x, pos.z - tpos.z);
				if (distToTarget > cfg.attackDistance - 0.2 || Math.abs(standY - tpos.y) > 1.6) {
					continue; // trop loin / trop de dénivelé pour frapper
				}
				double score = 0;
				Vec3 eye = new Vec3(pos.x, standY + 1.62, pos.z);
				if (Walkability.rayClear(level, eye, aim, state.player())) {
					score += 6.0; // on voit (et donc on peut frapper) la cible depuis ici
				} else {
					continue;
				}
				if (PathController.isDeadEnd(level, cell)) {
					score -= 4.0; // renfoncement : on s'y coince et on ne peut plus se repositionner
				}
				score -= 0.6 * solidSides(level, cell);
				score -= 0.35 * Math.hypot(pos.x - player.x, pos.z - player.z);
				score -= 0.8 * Math.abs(standY - player.y);
				score += 1.5 * Math.cos(angle - toPlayerAngle); // du côté d'où l'on vient : pas de détour inutile
				if (Math.abs(standY - player.y) <= 0.6
					&& Walkability.segmentWalkable(level, player, pos, 0.12, true)) {
					score += 1.0; // atteignable en ligne droite
				}
				if (cell.equals(chosenCell)) {
					score += SAME_CELL_BONUS; // hystérésis : on garde la position tant qu'elle reste bonne
				}
				if (score > bestScore) {
					bestScore = score;
					bestCell = cell;
					bestPos = pos;
				}
			}
		}
		chosenCell = bestCell;
		chosen = bestPos;
		debugLastStandY = bestPos == null ? Double.NaN : bestPos.y; // diagnostic [NAV HEIGHT]
	}

	/** Case praticable à (x, z) proche de la hauteur réelle {@code y} (pieds de la cible), ou null. Même source de hauteur que le reste. */
	private static BlockPos standableCell(Level level, double x, double y, double z) {
		double h = Walkability.feetHeightAt(level, x, z, y, Walkability.JUMP_HEIGHT, Walkability.JUMP_HEIGHT, 0.0);
		if (Double.isNaN(h)) {
			return null;
		}
		BlockPos cell = Walkability.cellOf(new Vec3(Math.floor(x) + 0.5, h, Math.floor(z) + 0.5));
		return Walkability.canStandAt(level, cell) ? cell : null;
	}

	private static int solidSides(Level level, BlockPos pos) {
		double h = Walkability.standHeight(level, pos);
		int n = 0;
		for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			if (!Walkability.bodyFreeAt(level, pos.getX() + 0.5 + dir.getStepX(), h, pos.getZ() + 0.5 + dir.getStepZ(), 0.0)) {
				n++;
			}
		}
		return n;
	}
}
