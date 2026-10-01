package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.PlayerState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Navigation locale PRÉDICTIVE. Le chemin global (A*) ou la position de combat dit OÙ aller ; ce navigateur décide COMMENT,
 * en simulant plusieurs trajectoires candidates (cap vers le guide, puis déviations croissantes) à vraie hauteur de sol :
 * à chaque point simulé on cherche la surface réelle, on teste la boîte du joueur (marge comprise), le support, le plafond,
 * le danger, et on mesure la marge avec les parois et le dénivelé. Une trajectoire s'arrête dès qu'elle devient impossible :
 * son score regarde la distance LIBRE (anticipation), pas seulement la collision actuelle.
 *
 * <p>Score = progression + orientation vers le guide + marge + continuité + mobilité future (sorties après l'horizon) +
 * visibilité de la cible (combat) + stabilité du cap précédent − risque d'impasse − virage − dénivelé − demi-tour.
 * Une trajectoire qui mène à une impasse est mauvaise, même longue ; une qui s'ouvre sur plusieurs issues est bonne.
 * Le cap précédent est gardé tant qu'il reste viable et quasi équivalent (hystérésis), mais abandonné vite s'il devient
 * mauvais. Aucune part de hasard : le résultat ne dépend que du terrain, du guide et de l'état courant.
 *
 * <p>Si AUCUNE trajectoire n'est sûre : {@code noSafeTrajectory}, point null, jamais de cap vers l'obstacle.
 */
public final class LocalNavigator {

	/** Contexte de combat : point à garder en vue (cible) et distance souhaitée. */
	public record Combat(Vec3 aim, double range) {}

	/**
	 * Résultat. Si {@code noSafeTrajectory} : AUCUNE trajectoire candidate n'est sûre ; {@code point} est alors null et
	 * il ne faut exécuter aucun cap vers l'obstacle ({@code safe} est faux = cap REFUSÉ). {@code nudgePoint} (peut être
	 * null) est une petite correction de position sûre (latérale ou arrière) disponible malgré tout.
	 * {@code reason} : WALL / CLEARANCE / HEIGHT / VOID (cause principale du refus, "-" si sûr).
	 */
	public record Steering(Vec3 point, double headingDeg, boolean safe, int clearSteps, boolean jump, boolean sprintOk,
						   BlockPos blockedCell, boolean noSafeTrajectory, Vec3 nudgePoint, String reason,
						   double clearDistance, double feetY) {}

	private record Rollout(int clear, Vec3 end, int jumpStep, double comfort, BlockPos blocked, double vertical, String reason) {}

	private static final int MAX_STEPS = 32;
	private static final int MIN_STEPS = 8;
	/** Marge « confortable » : mesure combien la trajectoire s'éloigne des parois. */
	private static final double COMFORT_MARGIN = 0.3;
	private static final double LOOK_DISTANCE = 3.5;
	private static final double[] OFFSETS = {0, 10, -10, 20, -20, 30, -30, 45, -45, 60, -60, 75, -75, 90, -90, 120, -120, 150, -150, 180};
	/** Nombre de meilleures candidates pour lesquelles on évalue la mobilité future (coûteuse). */
	private static final int DEEP_EVAL = 4;

	private Steering last;
	private Vec3 guideAtPlan;
	private int ticksSincePlan = 99;
	private double prevHeading = Double.NaN;
	private double lastNudgeHeading = Double.NaN;

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
		ModConfig cfg = ModConfig.get();
		Vec3 pos = state.position();
		Level level = state.level();
		if (!state.onGround() || guide.y > pos.y + cfg.navJumpHeight + 0.1 || guide.y < pos.y - 0.6) {
			reset();
			return null; // en l'air, ou le guide implique une montée / une chute délibérée : le chemin global s'en charge
		}
		double gdist = Math.hypot(guide.x - pos.x, guide.z - pos.z);
		if (gdist < 0.4) {
			return null;
		}
		double stepLen = Mth.clamp(cfg.navSampleDistance, 0.1, 0.5);
		int minSafe = (int) Math.ceil(cfg.navMinSafeDistance / stepLen);
		double margin = cfg.navSafetyMargin;
		boolean due = last == null || ++ticksSincePlan >= Math.max(1, cfg.navReplanTicks)
			|| guideAtPlan == null || guideAtPlan.distanceToSqr(guide) > 1.0
			|| (last.noSafeTrajectory() && ticksSincePlan >= 2);
		if (!due && !last.noSafeTrajectory()) {
			// Contrôle à chaque tick : le cap actuel mène-t-il encore quelque part ? Sinon on replanifie tout de suite.
			Rollout quick = rollout(level, pos, last.headingDeg(), minSafe + 2, stepLen, margin, false);
			due = quick.clear() < minSafe + 2;
		}
		if (due) {
			last = plan(state, guide, gdist, combat, stepLen, minSafe);
			guideAtPlan = guide;
			ticksSincePlan = 0;
			prevHeading = last.headingDeg();
		}
		if (last.noSafeTrajectory()) {
			// Aucune trajectoire sûre : AUCUN point de cap (rien à exécuter), seulement l'éventuelle petite correction.
			Vec3 nudge = Double.isNaN(lastNudgeHeading) ? null : pointAlong(pos, lastNudgeHeading, NUDGE_POINT_DISTANCE);
			return new Steering(null, last.headingDeg(), false, last.clearSteps(), false, false, last.blockedCell(), true, nudge,
				last.reason(), last.clearDistance(), pos.y);
		}
		// Le point de cap est recalculé chaque tick le long du cap retenu : il reste stable même quand le joueur avance.
		Vec3 point = pointAlong(pos, last.headingDeg(), LOOK_DISTANCE);
		return new Steering(point, last.headingDeg(), true, last.clearSteps(), last.jump(), last.sprintOk(), last.blockedCell(),
			false, null, "-", last.clearDistance(), pos.y);
	}

	private static Vec3 pointAlong(Vec3 pos, double headingDeg, double distance) {
		double rad = Math.toRadians(headingDeg);
		return new Vec3(pos.x - Math.sin(rad) * distance, pos.y, pos.z + Math.cos(rad) * distance);
	}

	// ========================================
	// PLANIFICATION
	// ========================================

	private record Candidate(double heading, double offset, Rollout r, double score) {}

	private Steering plan(PlayerState state, Vec3 guide, double gdist, Combat combat, double stepLen, int minSafe) {
		ModConfig cfg = ModConfig.get();
		Level level = state.level();
		Vec3 pos = state.position();
		double margin = cfg.navSafetyMargin;
		float guideYaw = RotationController.computeYaw(pos, guide);
		// Portée d'analyse : l'horizon configuré + ce que l'on parcourt en ~8 ticks (plus on va vite, plus on regarde loin).
		double horizon = cfg.navLocalHorizonBlocks + 8.0 * state.horizontalSpeed();
		int steps = Mth.clamp((int) Math.round(Math.min(horizon, gdist + 0.8) / stepLen), MIN_STEPS, MAX_STEPS);
		int safeSteps = Math.min(minSafe, steps);

		// Voie directe : la plupart du temps c'est la bonne, inutile d'évaluer les autres.
		Rollout straight = rollout(level, pos, guideYaw, steps, stepLen, margin, true);
		boolean prevCompatible = Double.isNaN(prevHeading) || Math.abs(Mth.wrapDegrees((float) (guideYaw - prevHeading))) < 25;
		if (prevCompatible && straight.clear() == steps && straight.comfort() >= 0.9 && mobility(level, straight.end(), guideYaw, stepLen) > 0) {
			return finish(guideYaw, 0, steps, straight);
		}

		List<Candidate> cands = new ArrayList<>();
		Rollout deepest = straight; // le plus profond même s'il n'est pas sûr (sert à signaler l'obstacle)
		List<Double> headings = new ArrayList<>();
		for (double offset : OFFSETS) {
			headings.add(guideYaw + offset);
		}
		if (!Double.isNaN(prevHeading)) {
			headings.add(prevHeading); // le cap précédent est toujours candidat : on le garde s'il reste aussi bon
		}
		for (double heading : headings) {
			double offset = Mth.wrapDegrees((float) (heading - guideYaw));
			Rollout r = Math.abs(offset) < 1.0E-6 ? straight : rollout(level, pos, heading, steps, stepLen, margin, true);
			if (r.clear() > deepest.clear()) {
				deepest = r;
			}
			if (r.clear() < safeSteps) {
				continue; // trajectoire REFUSÉE : elle se bloque trop tôt, on ne la considère jamais
			}
			cands.add(new Candidate(heading, offset, r, baseScore(cfg, guide, gdist, steps, heading, offset, r)));
		}
		if (!cands.isEmpty()) {
			cands.sort((a, b) -> Double.compare(b.score, a.score));
			// Évaluation approfondie des meilleures : mobilité future (impasse ?) et, en combat, visibilité / distance.
			Candidate best = null;
			double bestScore = -1e9;
			for (int i = 0; i < Math.min(DEEP_EVAL, cands.size()); i++) {
				Candidate c = cands.get(i);
				double s = c.score + futureScore(state, cfg, combat, steps, c);
				if (s > bestScore) {
					bestScore = s;
					best = c;
				}
			}
			// Hystérésis : le cap précédent (s'il est viable) reste choisi tant qu'il n'est pas nettement battu.
			if (!Double.isNaN(prevHeading) && best != null) {
				for (Candidate c : cands) {
					if (Math.abs(Mth.wrapDegrees((float) (c.heading - prevHeading))) < 1.0E-6) {
						double s = c.score + futureScore(state, cfg, combat, steps, c);
						if (s >= bestScore - cfg.navTurnPenalty * 0.35) {
							best = c;
						}
						break;
					}
				}
			}
			lastNudgeHeading = Double.NaN;
			return finish((float) best.heading - (float) best.offset, best.offset, steps, best.r);
		}
		// AUCUNE trajectoire sûre : on le dit explicitement. Pas de cap vers l'obstacle ; on cherche seulement une petite
		// correction de position sûre (côté ou arrière) pour sortir de la situation.
		lastNudgeHeading = Double.NaN;
		int needed = (int) Math.ceil(NUDGE_MIN_DISTANCE / stepLen);
		int bestClear = 0;
		for (double off : new double[] {85, -85, 120, -120, 150, -150, 180}) {
			Rollout n = rollout(level, pos, guideYaw + off, needed + 2, stepLen, margin, false);
			if (n.clear() >= needed && n.clear() > bestClear) {
				bestClear = n.clear();
				lastNudgeHeading = guideYaw + off;
			}
		}
		return new Steering(null, guideYaw, false, deepest.clear(), false, false, deepest.blocked(), true, null,
			deepest.reason(), deepest.clear() * stepLen, pos.y);
	}

	/** Score sans simulation supplémentaire : progression, orientation, marge, continuité, stabilité, dénivelé, demi-tour. */
	private double baseScore(ModConfig cfg, Vec3 guide, double gdist, int steps, double heading, double offset, Rollout r) {
		double endDist = Math.hypot(guide.x - r.end().x, guide.z - r.end().z);
		double progressScore = (gdist - endDist) * cfg.navProgressWeight;
		double alignScore = Math.cos(Math.toRadians(offset)) * cfg.navTargetAlignWeight;
		double frac = r.clear() / (double) steps;
		double clearanceScore = frac * 10.0 + r.comfort() * cfg.navClearanceWeight;
		double continuity = r.clear() == steps ? 1.0 : 0.0;
		double previousBonus = 0;
		double excessiveTurn = 0;
		if (!Double.isNaN(prevHeading)) {
			double dev = Math.abs(Mth.wrapDegrees((float) (heading - prevHeading)));
			previousBonus = Math.max(0, 1.0 - dev / 60.0) * cfg.navTurnPenalty;
			excessiveTurn = dev / 90.0 * cfg.navTurnPenalty * 0.5;
		}
		double verticalCost = r.vertical() * 0.4 + (r.jumpStep() > 0 ? 0.5 : 0);
		double reversePenalty = Math.abs(offset) >= 120 ? 1.5 : 0;
		double wallProximity = (1.0 - r.comfort()) * 0.5;
		return progressScore + alignScore + clearanceScore + continuity + previousBonus
			- excessiveTurn - verticalCost - reversePenalty - wallProximity;
	}

	/** Mobilité future (sorties après l'horizon) et critères de combat, pour les meilleures candidates seulement. */
	private double futureScore(PlayerState state, ModConfig cfg, Combat combat, int steps, Candidate c) {
		Level level = state.level();
		double score = 0;
		if (c.r.clear() == steps) {
			int mobility = mobility(level, c.r.end(), c.heading, Mth.clamp(cfg.navSampleDistance, 0.1, 0.5));
			score += mobility / 5.0 * 2.0;
			if (mobility == 0) {
				score -= cfg.navDeadEndPenalty; // impasse : on n'y entre pas
			} else if (mobility == 1) {
				score -= cfg.navDeadEndPenalty * 0.4; // une seule issue
			}
		}
		if (combat != null) {
			// Combat : de là où cette trajectoire nous amène, voit-on la cible, et à bonne distance ?
			Vec3 eyeEnd = new Vec3(c.r.end().x, c.r.end().y + 1.62, c.r.end().z);
			if (Walkability.rayClear(level, eyeEnd, combat.aim(), state.player())) {
				score += cfg.navCombatVisibilityWeight;
			}
			double d = Math.hypot(combat.aim().x - c.r.end().x, combat.aim().z - c.r.end().z);
			score -= Math.min(2.0, Math.abs(d - combat.range()) * 0.6);
		}
		return score;
	}

	private Steering finish(float guideYaw, double offset, int steps, Rollout r) {
		boolean jump = r.jumpStep() > 0 && r.jumpStep() <= 4;
		boolean sprintOk = r.clear() == steps && steps >= 12 && r.comfort() >= 0.85 && Math.abs(offset) <= 15 && r.jumpStep() < 0;
		double stepLen = Mth.clamp(ModConfig.get().navSampleDistance, 0.1, 0.5);
		return new Steering(null, guideYaw + offset, true, r.clear(), jump, sprintOk, r.blocked(), false, null, "-",
			r.clear() * stepLen, r.end().y);
	}

	/** Après le déplacement simulé, combien de caps (0, ±35, ±70) permettent encore d'avancer d'1 bloc ? 0 = piège. */
	private int mobility(Level level, Vec3 end, double heading, double stepLen) {
		int need = (int) Math.ceil(1.0 / stepLen);
		double margin = ModConfig.get().navSafetyMargin;
		int n = 0;
		for (double off : new double[] {0, 35, -35, 70, -70}) {
			if (rollout(level, end, heading + off, need, stepLen, margin, false).clear() >= need) {
				n++;
			}
		}
		return n;
	}

	// ========================================
	// SIMULATION
	// ========================================

	/**
	 * Avance la boîte du joueur pas à pas ({@code stepLen} bloc) le long de {@code headingDeg}. À chaque pas : surface réelle
	 * du sol (marche, demi-dalle, saut jusqu'à la hauteur de saut, descente d'au plus 1,1), corps libre à cette hauteur
	 * (plafond compris, marge comprise), support, danger. La simulation s'arrête au premier pas impossible.
	 */
	private Rollout rollout(Level level, Vec3 start, double headingDeg, int steps, double stepLen, double margin, boolean comfort) {
		double jumpReach = ModConfig.get().navJumpHeight;
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
		double vertical = 0;
		BlockPos blocked = null;
		String reason = "-";
		for (int i = 1; i <= steps; i++) {
			double nx = x + dx * stepLen;
			double nz = z + dz * stepLen;
			double ny;
			if (Walkability.bodyFreeAt(level, nx, y, nz, margin) && Walkability.supportedAt(level, nx, y, nz)) {
				ny = y; // chemin rapide : même hauteur, corps libre, sol dessous
			} else {
				ny = Walkability.feetHeightAt(level, nx, nz, y, jumpReach, 1.1, margin);
			}
			if (Double.isNaN(ny)) {
				blocked = BlockPos.containing(nx, y + 0.05, nz);
				reason = classify(level, x, y, z, nx, nz, dx, dz, margin, jumpReach);
				com.valafre.automod.debug.StepTrace.rolloutBlock(level, reason, i, new Vec3(x, y, z), nx, y, nz, headingDeg,
					Walkability.riseAhead(level, new Vec3(x, y, z), dx, dz), Double.NaN);
				break;
			}
			if (ny > y + Walkability.STEP_HEIGHT && jumpStep < 0) {
				jumpStep = i;
			}
			vertical += Math.abs(ny - y);
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
		return new Rollout(clear, new Vec3(x, y, z), jumpStep, comfyCount == 0 ? 1.0 : (double) comfy / comfyCount, blocked, vertical, reason);
	}

	/** Cause du blocage d'un pas : CLEARANCE (passe sans marge), HEIGHT (marche trop haute), WALL (obstacle), VOID (pas de sol). */
	private static String classify(Level level, double x, double y, double z, double nx, double nz, double dx, double dz,
								   double margin, double jumpReach) {
		if (Walkability.bodyFreeAt(level, nx, y, nz, 0.0) && !Walkability.bodyFreeAt(level, nx, y, nz, margin)) {
			return "CLEARANCE";
		}
		if (Walkability.bodyFreeAt(level, nx, y, nz, margin)) {
			return "VOID";
		}
		double rise = Walkability.riseAhead(level, new Vec3(x, y, z), dx, dz);
		return Double.isInfinite(rise) || rise > jumpReach ? (hasTallStep(level, nx, nz, y, jumpReach) ? "HEIGHT" : "WALL") : "WALL";
	}

	/** Y a-t-il devant une surface praticable mais trop haute pour être franchie (marche de 1,5 par exemple) ? */
	private static boolean hasTallStep(Level level, double nx, double nz, double y, double jumpReach) {
		return !Walkability.surfaceTops(level, nx, nz, y + jumpReach + 0.01, y + 2.2).isEmpty();
	}
}
