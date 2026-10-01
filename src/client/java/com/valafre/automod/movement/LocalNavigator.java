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
 * Navigation locale prédictive. Le chemin global (A*) ou la position de combat dit OÙ aller ; ce navigateur décide
 * COMMENT, à chaque instant : il génère plusieurs trajectoires candidates (cap droit vers le guide, puis déviations
 * croissantes à gauche et à droite), simule chacune en avant avec la vraie boîte du joueur (largeur, hauteur de passage,
 * marche, vide, marge de sécurité), élimine celles qui finissent contre un obstacle ou dans un cul-de-sac, et retient la
 * meilleure : elle progresse vers le guide, reste dégagée, laisse la possibilité de continuer après le contournement et,
 * en combat, garde la cible visible et à portée.
 *
 * <p>La portée d'analyse dépend de la vitesse (plus on va vite, plus on regarde loin). Le cap retenu est conservé tant
 * qu'il reste viable (contrôle rapide à chaque tick, replanification complète seulement si nécessaire) : pas de gauche/droite
 * décidé à chaque tick.
 */
public final class LocalNavigator {

	/** Contexte de combat : point à garder en vue (cible) et distance souhaitée. */
	public record Combat(Vec3 aim, double range) {}

	/**
	 * Résultat. Si {@code noSafeTrajectory} : AUCUNE trajectoire candidate n'est sûre ; {@code point} est alors null et
	 * il ne faut exécuter aucun cap vers l'obstacle ({@code safe} est faux = cap REFUSÉ). {@code nudgePoint} (peut être
	 * null) est une petite correction de position sûre (latérale ou arrière) disponible malgré tout.
	 */
	public record Steering(Vec3 point, double headingDeg, boolean safe, int clearSteps, boolean jump, boolean sprintOk,
						   BlockPos blockedCell, boolean noSafeTrajectory, Vec3 nudgePoint) {}

	private record Rollout(int clear, Vec3 end, int jumpStep, double comfort, BlockPos blocked) {}

	private static final int MAX_STEPS = 24;
	private static final int MIN_STEPS = 8;
	/** Distance minimale (blocs) qu'un cap doit pouvoir parcourir sans obstacle pour être jugé sûr. */
	private static final double MIN_SAFE_DISTANCE = 1.4;
	/** Marge de sécurité de chaque côté du corps (0,6) : un passage doit avoir 0,84 de large. */
	private static final double MARGIN = 0.12;
	/** Marge « confortable » : mesure combien la trajectoire s'éloigne des parois. */
	private static final double COMFORT_MARGIN = 0.3;
	private static final double JUMP_REACH = 1.15;
	private static final double LOOK_DISTANCE = 3.5;
	private static final int REPLAN_TICKS = 4;
	private static final double[] OFFSETS = {0, 12, -12, 25, -25, 40, -40, 60, -60, 85, -85, 120, -120};

	private Steering last;
	private Vec3 guideAtPlan;
	private int ticksSincePlan = 99;
	private double prevHeading = Double.NaN;
	private double lastNudgeHeading = Double.NaN;

	/** Distance minimale (blocs) d'une correction de position de secours. */
	private static final double NUDGE_MIN_DISTANCE = 0.9;
	private static final double NUDGE_POINT_DISTANCE = 1.2;

	public void reset() {
		last = null;
		guideAtPlan = null;
		ticksSincePlan = 99;
		prevHeading = Double.NaN;
	}

	public Steering steer(PlayerState state, Vec3 guide) {
		return steer(state, guide, null);
	}

	/** @return le cap à suivre vers {@code guide}, ou null si la navigation locale ne s'applique pas (en l'air, chute, très près). */
	public Steering steer(PlayerState state, Vec3 guide, Combat combat) {
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
		double stepLen = Mth.clamp(state.horizontalSpeed(), 0.2, 0.28);
		int minSafe = (int) Math.ceil(MIN_SAFE_DISTANCE / stepLen);
		boolean due = last == null || ++ticksSincePlan >= REPLAN_TICKS
			|| guideAtPlan == null || guideAtPlan.distanceToSqr(guide) > 1.0
			|| (last.noSafeTrajectory() && ticksSincePlan >= 2);
		if (!due && !last.noSafeTrajectory()) {
			// Contrôle rapide à chaque tick : le cap actuel mène-t-il encore quelque part ? Sinon on replanifie tout de suite.
			Rollout quick = rollout(level, pos, last.headingDeg(), minSafe + 2, stepLen, MARGIN, false);
			due = quick.clear() < minSafe + 2;
		}
		if (due) {
			last = plan(state, guide, gdist, combat, stepLen, minSafe);
			guideAtPlan = guide;
			ticksSincePlan = 0;
			prevHeading = last.headingDeg();
		}
		if (last.noSafeTrajectory()) {
			// Aucune trajectoire sûre : on ne renvoie AUCUN point de cap (rien à exécuter), seulement l'éventuelle petite correction.
			Vec3 nudge = Double.isNaN(lastNudgeHeading) ? null : pointAlong(pos, lastNudgeHeading, NUDGE_POINT_DISTANCE);
			return new Steering(null, last.headingDeg(), false, last.clearSteps(), false, false, last.blockedCell(), true, nudge);
		}
		// Le point de cap est recalculé chaque tick le long du cap retenu : il reste stable même quand le joueur avance.
		Vec3 point = pointAlong(pos, last.headingDeg(), LOOK_DISTANCE);
		return new Steering(point, last.headingDeg(), true, last.clearSteps(), last.jump(), last.sprintOk(), last.blockedCell(), false, null);
	}

	private static Vec3 pointAlong(Vec3 pos, double headingDeg, double distance) {
		double rad = Math.toRadians(headingDeg);
		return new Vec3(pos.x - Math.sin(rad) * distance, pos.y, pos.z + Math.cos(rad) * distance);
	}

	// ========================================
	// PLANIFICATION
	// ========================================

	private Steering plan(PlayerState state, Vec3 guide, double gdist, Combat combat, double stepLen, int minSafe) {
		Level level = state.level();
		Vec3 pos = state.position();
		float guideYaw = RotationController.computeYaw(pos, guide);
		// Portée d'analyse : 3 blocs + ce que l'on parcourt en ~8 ticks (plus on va vite, plus on regarde loin).
		double horizon = 3.0 + 8.0 * stepLen;
		int steps = Mth.clamp((int) Math.round(Math.min(horizon, gdist + 0.8) / stepLen), MIN_STEPS, MAX_STEPS);
		int safeSteps = Math.min(minSafe, steps);

		// Voie directe : la plupart du temps c'est la bonne, inutile d'évaluer les autres.
		Rollout straight = rollout(level, pos, guideYaw, steps, stepLen, MARGIN, true);
		if (straight.clear() == steps && straight.comfort() >= 0.9 && canContinue(level, straight.end(), guideYaw, stepLen)) {
			return finish(guideYaw, 0, steps, safeSteps, straight);
		}

		double bestScore = -1e9;
		double bestOffset = 0;
		Rollout best = null;
		Rollout deepest = straight; // le plus profond même s'il n'est pas sûr (sert à signaler l'obstacle)
		for (double offset : OFFSETS) {
			double heading = guideYaw + offset;
			Rollout r = offset == 0 ? straight : rollout(level, pos, heading, steps, stepLen, MARGIN, true);
			if (r.clear() > deepest.clear()) {
				deepest = r;
			}
			if (r.clear() < safeSteps) {
				continue; // trajectoire REFUSÉE : elle se bloque trop tôt, on ne la considère jamais
			}
			double endDist = Math.hypot(guide.x - r.end().x, guide.z - r.end().z);
			double progress = gdist - endDist;
			double frac = r.clear() / (double) steps;
			double score = frac * 10.0 + progress * 2.0 + r.comfort() * 1.5 - Math.abs(offset) / 90.0
				- (Double.isNaN(prevHeading) ? 0 : Math.abs(Mth.wrapDegrees((float) (heading - prevHeading))) / 90.0 * 1.5);
			if (r.clear() == steps && !canContinue(level, r.end(), heading, stepLen)) {
				score -= 5.0; // atteindrait un cul-de-sac : on n'y entre pas
			}
			if (combat != null) {
				// Combat : de là où cette trajectoire nous amène, voit-on la cible, et à bonne distance ?
				Vec3 eyeEnd = new Vec3(r.end().x, r.end().y + 1.62, r.end().z);
				if (Walkability.rayClear(level, eyeEnd, combat.aim(), state.player())) {
					score += 2.0;
				}
				double d = Math.hypot(combat.aim().x - r.end().x, combat.aim().z - r.end().z);
				score -= Math.min(2.0, Math.abs(d - combat.range()) * 0.6);
			}
			if (score > bestScore) {
				bestScore = score;
				bestOffset = offset;
				best = r;
			}
		}
		if (best != null) {
			lastNudgeHeading = Double.NaN;
			return finish(guideYaw, bestOffset, steps, safeSteps, best);
		}
		// AUCUNE trajectoire sûre : on le dit explicitement (NO_SAFE_TRAJECTORY). Pas de cap vers l'obstacle ; on cherche
		// seulement une petite correction de position sûre (côté ou arrière) pour sortir de la situation.
		lastNudgeHeading = Double.NaN;
		int needed = (int) Math.ceil(NUDGE_MIN_DISTANCE / stepLen);
		int bestClear = 0;
		for (double off : new double[] {85, -85, 120, -120, 150, -150, 180}) {
			Rollout n = rollout(level, pos, guideYaw + off, needed + 2, stepLen, MARGIN, false);
			if (n.clear() >= needed && n.clear() > bestClear) {
				bestClear = n.clear();
				lastNudgeHeading = guideYaw + off;
			}
		}
		return new Steering(null, guideYaw, false, deepest.clear(), false, false, deepest.blocked(), true, null);
	}

	private Steering finish(float guideYaw, double offset, int steps, int safeSteps, Rollout r) {
		boolean jump = r.jumpStep() > 0 && r.jumpStep() <= 4;
		boolean sprintOk = r.clear() == steps && steps >= 12 && r.comfort() >= 0.85 && Math.abs(offset) <= 15 && r.jumpStep() < 0;
		return new Steering(null, guideYaw + offset, true, r.clear(), jump, sprintOk, r.blocked(), false, null);
	}

	/** Après le déplacement simulé, peut-on encore avancer (tout droit ou en biais) ? Sinon c'est un piège. */
	private boolean canContinue(Level level, Vec3 end, double heading, double stepLen) {
		int need = (int) Math.ceil(1.0 / stepLen);
		for (double off : new double[] {0, 35, -35}) {
			if (rollout(level, end, heading + off, need, stepLen, MARGIN, false).clear() >= need) {
				return true;
			}
		}
		return false;
	}

	// ========================================
	// SIMULATION
	// ========================================

	/**
	 * Avance la boîte du joueur pas à pas ({@code stepLen} bloc / tick) le long de {@code headingDeg}. À chaque pas : la
	 * place doit suffire (marge comprise) ; sinon une marche franchissable (monter d'au plus 1,15) est tentée ; le sol doit
	 * exister (une descente de 1 bloc max est acceptée, pas le vide).
	 */
	private Rollout rollout(Level level, Vec3 start, double headingDeg, int steps, double stepLen, double margin, boolean comfort) {
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
			double nx = x + dx * stepLen;
			double nz = z + dz * stepLen;
			double ny = y;
			if (!Walkability.bodyFreeAt(level, nx, ny, nz, margin)) {
				double rise = Walkability.riseAhead(level, new Vec3(x, y, z), dx, dz);
				if (rise > 0.05 && rise <= JUMP_REACH) {
					ny = y + rise;
					if (!Walkability.bodyFreeAt(level, nx, ny, nz, margin)) {
						blocked = BlockPos.containing(nx, y + 0.05, nz);
						com.valafre.automod.debug.StepTrace.rolloutBlock(level, "MARCHE_CORPS_BLOQUE_APRES_MONTEE", i, new Vec3(x, y, z), nx, ny, nz, headingDeg, rise, Double.NaN);
						break;
					}
					if (rise > 0.6 && jumpStep < 0) {
						jumpStep = i;
					}
				} else {
					blocked = BlockPos.containing(nx, y + 0.05, nz);
					com.valafre.automod.debug.StepTrace.rolloutBlock(level, "CORPS_BLOQUE_SANS_MONTEE_POSSIBLE", i, new Vec3(x, y, z), nx, ny, nz, headingDeg, rise, Double.NaN);
					break;
				}
			}
			if (!Walkability.supportedAt(level, nx, ny, nz)) {
				double top = groundTop(level, nx, nz, ny, ny - 1.1);
				if (Double.isNaN(top) || !Walkability.bodyFreeAt(level, nx, top, nz, margin)) {
					blocked = BlockPos.containing(nx, ny - 0.5, nz); // vide devant : bord de plateforme
					com.valafre.automod.debug.StepTrace.rolloutBlock(level, Double.isNaN(top) ? "SOL_ABSENT_SOUS_LE_CENTRE" : "CENTRE_SANS_APPUI_PUIS_CORPS_BLOQUE_A_LA_HAUTEUR_DU_SOL_INFERIEUR",
						i, new Vec3(x, y, z), nx, ny, nz, headingDeg, Double.NaN, top);
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
