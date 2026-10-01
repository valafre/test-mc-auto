package com.valafre.automod.movement;

import com.valafre.automod.core.PlayerState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Navigation locale prédictive. Le chemin global (A*) dit OÙ aller ; ce navigateur décide COMMENT, à chaque instant :
 * il génère plusieurs trajectoires candidates (cap droit vers le point guide, puis déviations de plus en plus fortes à
 * gauche et à droite), simule chacune quelques blocs en avant avec la vraie boîte du joueur (largeur, hauteur de passage,
 * marche, vide, marge de sécurité), élimine celles qui finissent contre un obstacle ou dans un cul-de-sac, et retient la
 * meilleure : celle qui progresse vers le guide, reste dégagée et laisse la possibilité de continuer après le contournement.
 * Le joueur commence donc à dévier AVANT l'obstacle ; la trajectoire revient d'elle-même vers le guide dès que la voie est libre.
 */
public final class LocalNavigator {

	/** Résultat : point de cap à regarder / rejoindre (toujours à {@link #LOOK_DISTANCE} devant) et indications d'allure. */
	public record Steering(Vec3 point, double headingDeg, boolean safe, int clearSteps, boolean jump, boolean sprintOk,
						   BlockPos blockedCell) {}

	private record Rollout(int clear, Vec3 end, int jumpStep, double comfort, BlockPos blocked) {}

	private static final int HORIZON_STEPS = 16;
	private static final int MIN_SAFE_STEPS = 6;
	private static final double WALK = 0.215;
	/** Marge de sécurité de chaque côté du corps (0,6) : un passage doit avoir 0,84 de large. */
	private static final double MARGIN = 0.12;
	/** Marge « confortable » : mesure combien la trajectoire s'éloigne des parois. */
	private static final double COMFORT_MARGIN = 0.3;
	private static final double JUMP_REACH = 1.15;
	private static final double LOOK_DISTANCE = 3.5;
	private static final int REPLAN_TICKS = 2;
	private static final double[] OFFSETS = {0, 12, -12, 25, -25, 40, -40, 60, -60, 85, -85, 120, -120};

	private Steering last;
	private Vec3 guideAtPlan;
	private int ticksSincePlan = 99;
	private double prevHeading = Double.NaN;

	public void reset() {
		last = null;
		guideAtPlan = null;
		ticksSincePlan = 99;
		prevHeading = Double.NaN;
	}

	/** @return le cap à suivre vers {@code guide}, ou null si la navigation locale ne s'applique pas (marche, chute, très près). */
	public Steering steer(PlayerState state, Vec3 guide) {
		Vec3 pos = state.position();
		Level level = state.level();
		if (!state.onGround() || guide.y > pos.y + JUMP_REACH + 0.1 || guide.y < pos.y - 0.6) {
			reset();
			return null; // en l'air, ou le guide implique une montée / une chute délibérée : le chemin global s'en charge
		}
		double gdist = Math.hypot(guide.x - pos.x, guide.z - pos.z);
		if (gdist < 0.4) {
			return null;
		}
		boolean replan = last == null || ++ticksSincePlan >= REPLAN_TICKS
			|| guideAtPlan == null || guideAtPlan.distanceToSqr(guide) > 1.0;
		if (replan) {
			last = plan(level, pos, guide, gdist);
			guideAtPlan = guide;
			ticksSincePlan = 0;
			prevHeading = last.headingDeg();
		}
		// Le point de cap est recalculé chaque tick le long du cap retenu : il reste stable même quand le joueur avance.
		double rad = Math.toRadians(last.headingDeg());
		Vec3 point = new Vec3(pos.x - Math.sin(rad) * LOOK_DISTANCE, pos.y, pos.z + Math.cos(rad) * LOOK_DISTANCE);
		return new Steering(point, last.headingDeg(), last.safe(), last.clearSteps(), last.jump(), last.sprintOk(), last.blockedCell());
	}

	// ========================================
	// PLANIFICATION
	// ========================================

	private Steering plan(Level level, Vec3 pos, Vec3 guide, double gdist) {
		float guideYaw = RotationController.computeYaw(pos, guide);
		int steps = Mth.clamp((int) (gdist / WALK) + 3, 6, HORIZON_STEPS);

		// Voie directe : la plupart du temps c'est la bonne, inutile d'évaluer les autres.
		Rollout straight = rollout(level, pos, guideYaw, steps, MARGIN, true);
		if (straight.clear() == steps && straight.comfort() >= 0.9 && canContinue(level, straight.end(), guideYaw)) {
			return finish(guideYaw, 0, steps, straight);
		}

		double bestScore = -1e9;
		double bestOffset = 0;
		Rollout best = straight;
		int bestSteps = steps;
		for (double offset : OFFSETS) {
			double heading = guideYaw + offset;
			Rollout r = offset == 0 ? straight : rollout(level, pos, heading, steps, MARGIN, true);
			double endDist = Math.hypot(guide.x - r.end().x, guide.z - r.end().z);
			double progress = gdist - endDist;
			double frac = r.clear() / (double) steps;
			double score = frac * 10.0 + progress * 2.0 + r.comfort() * 1.5 - Math.abs(offset) / 90.0
				- (Double.isNaN(prevHeading) ? 0 : Math.abs(Mth.wrapDegrees((float) (heading - prevHeading))) / 90.0 * 1.5);
			if (r.clear() < Math.min(MIN_SAFE_STEPS, steps)) {
				score -= 6.0; // se bloque presque tout de suite : à éviter absolument
			} else if (r.clear() == steps && !canContinue(level, r.end(), heading)) {
				score -= 5.0; // atteindrait un cul-de-sac : on n'y entre pas
			}
			if (score > bestScore) {
				bestScore = score;
				bestOffset = offset;
				best = r;
				bestSteps = steps;
			}
		}
		return finish(guideYaw, bestOffset, bestSteps, best);
	}

	private Steering finish(float guideYaw, double offset, int steps, Rollout r) {
		boolean safe = r.clear() >= Math.min(MIN_SAFE_STEPS, steps);
		boolean jump = r.jumpStep() > 0 && r.jumpStep() <= 4;
		boolean sprintOk = r.clear() == steps && steps >= 12 && r.comfort() >= 0.85 && Math.abs(offset) <= 15 && r.jumpStep() < 0;
		return new Steering(null, guideYaw + offset, safe, r.clear(), jump, sprintOk, r.blocked());
	}

	/** Après le déplacement simulé, peut-on encore avancer (tout droit ou en biais) ? Sinon c'est un piège. */
	private boolean canContinue(Level level, Vec3 end, double heading) {
		for (double off : new double[] {0, 35, -35}) {
			if (rollout(level, end, heading + off, 5, MARGIN, false).clear() >= 5) {
				return true;
			}
		}
		return false;
	}

	// ========================================
	// SIMULATION
	// ========================================

	/**
	 * Avance la boîte du joueur pas à pas (0,215 bloc / tick) le long de {@code headingDeg}. À chaque pas : la place doit
	 * suffire (marge comprise) ; sinon une marche franchissable (monter d'au plus 1,15) est tentée ; le sol doit exister
	 * (une descente de 1 bloc max est acceptée, pas le vide).
	 */
	private Rollout rollout(Level level, Vec3 start, double headingDeg, int steps, double margin, boolean comfort) {
		double rad = Math.toRadians(headingDeg);
		double dx = -Math.sin(rad);
		double dz = Math.cos(rad);
		double x = start.x;
		double y = start.y;
		double z = start.z;
		int clear = 0;
		int jumpStep = -1;
		int comfy = 0;
		int comfyCount = 0;
		BlockPos blocked = null;
		for (int i = 1; i <= steps; i++) {
			double nx = x + dx * WALK;
			double nz = z + dz * WALK;
			double ny = y;
			if (!Walkability.bodyFreeAt(level, nx, ny, nz, margin)) {
				double rise = Walkability.riseAhead(level, new Vec3(x, y, z), dx, dz);
				if (rise > 0.05 && rise <= JUMP_REACH) {
					ny = y + rise;
					if (!Walkability.bodyFreeAt(level, nx, ny, nz, margin)) {
						blocked = BlockPos.containing(nx, y + 0.05, nz);
						break;
					}
					if (rise > 0.6 && jumpStep < 0) {
						jumpStep = i;
					}
				} else {
					blocked = BlockPos.containing(nx, y + 0.05, nz);
					break;
				}
			}
			if (!Walkability.supportedAt(level, nx, ny, nz)) {
				double top = groundTop(level, nx, nz, ny, ny - 1.1);
				if (Double.isNaN(top) || !Walkability.bodyFreeAt(level, nx, top, nz, margin)) {
					blocked = BlockPos.containing(nx, ny - 0.5, nz); // vide devant : bord de plateforme
					break;
				}
				ny = top;
			}
			x = nx;
			y = ny;
			z = nz;
			clear = i;
			if (comfort && i % 2 == 0) {
				comfyCount++;
				if (Walkability.bodyFreeAt(level, x, y, z, COMFORT_MARGIN)) {
					comfy++;
				}
			}
		}
		return new Rollout(clear, new Vec3(x, y, z), jumpStep, comfyCount == 0 ? 1.0 : (double) comfy / comfyCount, blocked);
	}

	/** Hauteur de la surface praticable la plus haute dans [yLo, yHi] sous le point (x, z), ou NaN. */
	private static double groundTop(Level level, double x, double z, double yHi, double yLo) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		for (int by = Mth.floor(yHi + 0.01); by >= Mth.floor(yLo) - 1; by--) {
			BlockPos p = new BlockPos(bx, by, bz);
			BlockState state = level.getBlockState(p);
			VoxelShape shape = state.getCollisionShape(level, p);
			if (shape.isEmpty() || Walkability.isHazard(state)) {
				continue;
			}
			double top = by + shape.max(Direction.Axis.Y);
			if (top <= yHi + 0.01 && top >= yLo) {
				return top;
			}
		}
		return Double.NaN;
	}
}
