package com.valafre.automod.nav;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Planification locale PRÉDICTIVE, indépendante de Minecraft. À partir du snapshot, simule des trajectoires candidates à
 * vraie hauteur de sol (surface réelle, corps 0,6 x 1,8 avec marge, plafond, support, danger, marge avec les parois, dénivelé)
 * et choisit la meilleure : progression + orientation + marge + continuité + mobilité future + stabilité du cap + visibilité
 * (combat) - impasse - virage - dénivelé - demi-tour. Une trajectoire s'arrête dès qu'elle devient impossible et son score
 * regarde la distance LIBRE (anticipation).
 *
 * <p>Candidates évaluées par paliers : d'abord proches de la direction voulue (0, ±15, ±30, ±45 et le cap précédent), puis
 * élargies (±60..90), puis les grands écarts (±120..180) seulement si rien de satisfaisant n'a été trouvé. Déterministe.
 */
public final class LocalPlanner {

	private static final int MAX_STEPS = 32;
	private static final int MIN_STEPS = 8;
	private static final double COMFORT_MARGIN = 0.3;
	private static final double[][] TIERS = {
		{0, 15, -15, 30, -30, 45, -45},
		{60, -60, 75, -75, 90, -90},
		{120, -120, 150, -150, 180}
	};
	private static final int DEEP_EVAL = 4;
	private static final double NUDGE_MIN_DISTANCE = 0.9;

	private LocalPlanner() {}

	private record Rollout(int clear, double ex, double ey, double ez, int jumpStep, double comfort, long blocked,
						   double vertical, String reason) {}

	private record Candidate(double heading, double offset, Rollout r, double score) {}

	public record DebugSnapshot(long requestId, double guideYaw, double chosenHeading, double chosenOffset,
		double bestScore, double secondScore, double straightScore, double leftScore, double rightScore,
		int candidateCount, int testedTiers, int rollouts, boolean noSafe, double deepestClear) {}

	private static volatile DebugSnapshot debugSnapshot =
		new DebugSnapshot(-1, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, false, 0);

	public static DebugSnapshot debugSnapshot() {
		return debugSnapshot;
	}

	private static double wrap(double deg) {
		double d = deg % 360.0;
		if (d >= 180) {
			d -= 360;
		}
		if (d < -180) {
			d += 360;
		}
		return d;
	}

	/** Cap Minecraft (0 = +Z, 90 = -X) de (x0, z0) vers (x1, z1). */
	public static double yawTo(double x0, double z0, double x1, double z1) {
		return Math.toDegrees(Math.atan2(-(x1 - x0), z1 - z0));
	}

	/** Nombre de pas libres (0..steps) le long de {@code headingDeg} : sert aux contrôles rapides du thread Minecraft. */
	public static int clearSteps(NavWorld w, double x, double y, double z, double headingDeg, int steps, NavParams p) {
		return rollout(w, x, y, z, headingDeg, steps, clampStep(p), p.safetyMargin(), p.jumpHeight(), false, null).clear;
	}

	private static double clampStep(NavParams p) {
		return Math.max(0.1, Math.min(0.5, p.sampleDistance()));
	}

	public static LocalResult plan(LocalRequest req) {
		long t0 = System.nanoTime();
		NavWorld w = req.world();
		long checks0 = w instanceof NavGrid g ? g.checks() : 0;
		NavParams p = req.params();
		double stepLen = clampStep(p);
		int minSafe = (int) Math.ceil(p.minSafeDistance() / stepLen);
		double margin = p.safetyMargin();
		double gdist = Math.hypot(req.guideX() - req.x(), req.guideZ() - req.z());
		double guideYaw = yawTo(req.x(), req.z(), req.guideX(), req.guideZ());
		double horizon = p.horizon() + 8.0 * req.speed();
		int steps = Math.max(MIN_STEPS, Math.min(MAX_STEPS, (int) Math.round(Math.min(horizon, gdist + 0.8) / stepLen)));
		int safeSteps = Math.min(minSafe, steps);
		int[] rollouts = {0};
		long deadline = t0 + (long) (p.localBudgetMs() * 1_000_000.0);

		Rollout straight = rollout(w, req.x(), req.y(), req.z(), guideYaw, steps, stepLen, margin, p.jumpHeight(), true, null);
		rollouts[0]++;
		boolean hasPrev = !Double.isNaN(req.prevHeading());
		boolean prevCompatible = !hasPrev || Math.abs(wrap(guideYaw - req.prevHeading())) < 25;
		if (prevCompatible && straight.clear == steps && straight.comfort >= 0.9
			&& mobility(w, straight.ex, straight.ey, straight.ez, guideYaw, stepLen, p) > 0) {
			debugSnapshot = new DebugSnapshot(req.id(), guideYaw, guideYaw, 0,
				baseScore(req, p, steps, gdist, guideYaw, 0, straight), Double.NaN,
				baseScore(req, p, steps, gdist, guideYaw, 0, straight), Double.NaN, Double.NaN,
				1, 1, rollouts[0], false, straight.clear);
			return finish(req, guideYaw, steps, straight, t0, checks0, rollouts[0]);
		}

		List<Candidate> cands = new ArrayList<>();
		Rollout deepest = straight;
		List<Double> tried = new ArrayList<>();
		int testedTiers = 0;
		tried.add(0.0);
		if (straight.clear >= safeSteps) {
			cands.add(new Candidate(guideYaw, 0, straight, baseScore(req, p, steps, gdist, guideYaw, 0, straight)));
		}
		for (int tier = 0; tier < TIERS.length; tier++) {
			testedTiers = tier + 1;
			List<Double> headings = new ArrayList<>();
			for (double off : TIERS[tier]) {
				headings.add(guideYaw + off);
			}
			if (tier == 0 && hasPrev) {
				headings.add(req.prevHeading()); // le cap précédent est toujours candidat (stabilité)
			}
			for (double heading : headings) {
				double offset = wrap(heading - guideYaw);
				Rollout r = rollout(w, req.x(), req.y(), req.z(), heading, steps, stepLen, margin, p.jumpHeight(), true, null);
				rollouts[0]++;
				if (r.clear > deepest.clear) {
					deepest = r;
				}
				if (r.clear >= safeSteps) {
					cands.add(new Candidate(heading, offset, r, baseScore(req, p, steps, gdist, heading, offset, r)));
				}
			}
			// On n'élargit que si rien de satisfaisant n'a été trouvé (ou si le temps manque : on s'arrête ici).
			boolean satisfied = cands.stream().anyMatch(c -> c.r.clear == steps && c.r.comfort >= 0.6);
			if (satisfied || System.nanoTime() > deadline) {
				break;
			}
		}
		if (!cands.isEmpty()) {
			cands.sort((a, b) -> Double.compare(b.score, a.score));
			Candidate best = null;
			double bestScore = -1e9;
			for (int i = 0; i < Math.min(DEEP_EVAL, cands.size()); i++) {
				Candidate c = cands.get(i);
				double s = c.score + futureScore(req, p, steps, c, stepLen);
				if (s > bestScore) {
					bestScore = s;
					best = c;
				}
			}
			if (hasPrev && best != null) { // hystérésis : le cap précédent reste choisi tant qu'il n'est pas nettement battu
				for (Candidate c : cands) {
					if (Math.abs(wrap(c.heading - req.prevHeading())) < 1.0E-6) {
						double s = c.score + futureScore(req, p, steps, c, stepLen);
						if (s >= bestScore - p.turnPenalty() * 0.35) {
							best = c;
						}
						break;
					}
				}
			}
			double secondScore = -1e9;
			double straightScore = Double.NaN;
			double leftScore = Double.NaN;
			double rightScore = Double.NaN;
			for (Candidate c : cands) {
				double s = c.score + futureScore(req, p, steps, c, stepLen);
				if (best == null || c != best) secondScore = Math.max(secondScore, s);
				if (Math.abs(c.offset) < 1.0E-6) straightScore = Math.max(Double.isNaN(straightScore) ? -1e9 : straightScore, s);
				else if (c.offset < 0) leftScore = Math.max(Double.isNaN(leftScore) ? -1e9 : leftScore, s);
				else rightScore = Math.max(Double.isNaN(rightScore) ? -1e9 : rightScore, s);
			}
			if (best != null) bestScore = best.score + futureScore(req, p, steps, best, stepLen);
			debugSnapshot = new DebugSnapshot(req.id(), guideYaw, best == null ? Double.NaN : best.heading,
				best == null ? Double.NaN : best.offset, bestScore, secondScore, straightScore, leftScore, rightScore,
				cands.size(), testedTiers, rollouts[0], false, deepest.clear);
			return finish(req, best.heading - best.offset, steps, best.r, best.offset, t0, checks0, rollouts[0]);
		}

		// AUCUNE trajectoire sûre : on refuse toujours d'avancer vers le danger,
		// mais on ne doit pas pour autant rester planté au bord du vide.
		// Pour VOID, on cherche une petite trajectoire d'échappement locale, même si elle
		// ne remplit pas la marge normale : l'objectif est de sortir progressivement de
		// la zone dangereuse, pas de considérer le bord comme un mur définitif.
		int needed = (int) Math.ceil(NUDGE_MIN_DISTANCE / stepLen);
		int bestClear = 0;
		double bestEscapeScore = -Double.MAX_VALUE;
		double nudge = Double.NaN;

		if ("VOID".equals(deepest.reason)) {
			double blockedAway = guideYaw;
			if (deepest.blocked != NavGeometry.NO_BLOCK) {
				BlockPos blockedPos = BlockPos.of(deepest.blocked);
				double bx = blockedPos.getX() + 0.5;
				double bz = blockedPos.getZ() + 0.5;
				double towardBlocked = yawTo(req.x(), req.z(), bx, bz);
				blockedAway = towardBlocked + 180.0;
			}
			// 24 directions, mais uniquement dans le cas réellement utile (bord du vide).
			// On favorise le côté opposé au bord, puis la progression vers le guide et enfin
			// la continuité avec le cap précédent.
			for (int i = 0; i < 24; i++) {
				double heading = blockedAway + i * 15.0;
				Rollout n = rollout(w, req.x(), req.y(), req.z(), heading, Math.max(needed + 2, 4),
					stepLen, margin, p.jumpHeight(), false, null);
				rollouts[0]++;
				if (n.clear <= 0) {
					continue;
				}
				double progress = Math.cos(Math.toRadians(wrap(heading - guideYaw)));
				double continuity = Double.isNaN(req.prevHeading()) ? 0.0
					: Math.cos(Math.toRadians(wrap(heading - req.prevHeading())));
				double score = n.clear * 4.0 + progress * 1.5 + continuity * 0.75;
				if (n.clear > bestClear || (n.clear == bestClear && score > bestEscapeScore)) {
					bestClear = n.clear;
					bestEscapeScore = score;
					nudge = heading;
				}
			}
		} else {
			for (double off : new double[] {85, -85, 120, -120, 150, -150, 180}) {
				double heading = guideYaw + off;
				Rollout n = rollout(w, req.x(), req.y(), req.z(), heading, needed + 2, stepLen, margin, p.jumpHeight(), false, null);
				rollouts[0]++;
				if (n.clear >= needed && n.clear > bestClear) {
					bestClear = n.clear;
					nudge = heading;
				}
			}
		}

		debugSnapshot = new DebugSnapshot(req.id(), guideYaw, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
			Double.NaN, Double.NaN, Double.NaN, cands.size(), testedTiers, rollouts[0], true, deepest.clear);
		DebugSnapshot snap = debugSnapshot;
		String recoveryReason = !Double.isNaN(nudge) && "VOID".equals(deepest.reason) ? "VOID_ESCAPE" : deepest.reason;
		return new LocalResult(req.id(), req.generation(), true, guideYaw, deepest.clear, deepest.clear * stepLen, false, false,
			deepest.blocked, recoveryReason, nudge, req.y(), (System.nanoTime() - t0) / 1.0E6,
			(w instanceof NavGrid g ? g.checks() : 0) - checks0, rollouts[0], System.currentTimeMillis(),
			snap.bestScore(), snap.secondScore(), snap.straightScore(), snap.leftScore(), snap.rightScore(),
			snap.candidateCount(), snap.testedTiers());
	}

	private static LocalResult finish(LocalRequest req, double guideYaw, int steps, Rollout r, long t0, long checks0, int rollouts) {
		return finish(req, guideYaw, steps, r, 0, t0, checks0, rollouts);
	}

	private static LocalResult finish(LocalRequest req, double guideYaw, int steps, Rollout r, double offset, long t0,
									  long checks0, int rollouts) {
		boolean jump = r.jumpStep > 0 && r.jumpStep <= 4;
		boolean sprintOk = r.clear == steps && steps >= 12 && r.comfort >= 0.85 && Math.abs(offset) <= 15 && r.jumpStep < 0;
		NavWorld w = req.world();
		DebugSnapshot snap = debugSnapshot;
		return new LocalResult(req.id(), req.generation(), false, guideYaw + offset, r.clear, r.clear * clampStep(req.params()),
			jump, sprintOk, r.blocked, "-", Double.NaN, r.ey, (System.nanoTime() - t0) / 1.0E6,
			(w instanceof NavGrid g ? g.checks() : 0) - checks0, rollouts, System.currentTimeMillis(),
			snap.bestScore(), snap.secondScore(), snap.straightScore(), snap.leftScore(), snap.rightScore(),
			snap.candidateCount(), snap.testedTiers());
	}

	private static double baseScore(LocalRequest req, NavParams p, int steps, double gdist, double heading, double offset, Rollout r) {
		double endDist = Math.hypot(req.guideX() - r.ex, req.guideZ() - r.ez);
		double progressScore = (gdist - endDist) * p.progressWeight();
		double alignScore = Math.cos(Math.toRadians(offset)) * p.alignWeight();
		double frac = r.clear / (double) steps;
		double clearanceScore = frac * 10.0 + r.comfort * p.clearanceWeight();
		double continuity = r.clear == steps ? 1.0 : 0.0;
		double previousBonus = 0;
		double excessiveTurn = 0;
		if (!Double.isNaN(req.prevHeading())) {
			double dev = Math.abs(wrap(heading - req.prevHeading()));
			previousBonus = Math.max(0, 1.0 - dev / 60.0) * p.turnPenalty();
			excessiveTurn = dev / 90.0 * p.turnPenalty() * 0.5;
		}
		double verticalCost = r.vertical * 0.4 + (r.jumpStep > 0 ? 0.5 : 0);
		double reversePenalty = Math.abs(offset) >= 120 ? 1.5 : 0;
		double wallProximity = (1.0 - r.comfort) * 0.5;
		return progressScore + alignScore + clearanceScore + continuity + previousBonus
			- excessiveTurn - verticalCost - reversePenalty - wallProximity;
	}

	private static double futureScore(LocalRequest req, NavParams p, int steps, Candidate c, double stepLen) {
		double score = 0;
		NavWorld w = req.world();
		if (c.r.clear == steps) {
			int mobility = mobility(w, c.r.ex, c.r.ey, c.r.ez, c.heading, stepLen, p);
			score += mobility / 5.0 * 2.0;
			if (mobility == 0) {
				score -= p.deadEndPenalty(); // impasse
			} else if (mobility == 1) {
				score -= p.deadEndPenalty() * 0.4; // une seule issue
			}
		}
		if (req.combat()) {
			if (NavGeometry.rayClear(w, c.r.ex, c.r.ey + 1.62, c.r.ez, req.aimX(), req.aimY(), req.aimZ())) {
				score += p.combatVisibilityWeight();
			}
			double d = Math.hypot(req.aimX() - c.r.ex, req.aimZ() - c.r.ez);
			score -= Math.min(2.0, Math.abs(d - req.range()) * 0.6);
		}
		return score;
	}

	/** Combien de caps (0, ±35, ±70) permettent encore d'avancer d'1 bloc après la trajectoire ? 0 = piège. */
	private static int mobility(NavWorld w, double ex, double ey, double ez, double heading, double stepLen, NavParams p) {
		int need = (int) Math.ceil(1.0 / stepLen);
		int n = 0;
		for (double off : new double[] {0, 35, -35, 70, -70}) {
			if (rollout(w, ex, ey, ez, heading + off, need, stepLen, p.safetyMargin(), p.jumpHeight(), false, null).clear >= need) {
				n++;
			}
		}
		return n;
	}

	// ========================================
	// SIMULATION
	// ========================================

	private static Rollout rollout(NavWorld w, double sx, double sy, double sz, double headingDeg, int steps, double stepLen,
								   double margin, double jumpReach, boolean comfort, Object unused) {
		double rad = Math.toRadians(headingDeg);
		double dx = -Math.sin(rad);
		double dz = Math.cos(rad);
		double x = sx;
		double y = sy;
		double z = sz;
		int clear = 0;
		int jumpStep = -1;
		int comfy = 0;
		int comfyCount = 0;
		double vertical = 0;
		long blocked = NavGeometry.NO_BLOCK;
		String reason = "-";
		for (int i = 1; i <= steps; i++) {
			double nx = x + dx * stepLen;
			double nz = z + dz * stepLen;
			double ny;
			if (NavGeometry.bodyFreeAt(w, nx, y, nz, margin) && NavGeometry.supportedAt(w, nx, y, nz)) {
				ny = y; // chemin rapide : même hauteur, corps libre, sol dessous
			} else {
				ny = NavGeometry.feetHeightAt(w, nx, nz, y, jumpReach, 1.1, margin);
			}
			if (Double.isNaN(ny)) {
				blocked = NavGeometry.pack(NavGeometry.floor(nx), NavGeometry.floor(y + 0.05), NavGeometry.floor(nz));
				reason = classify(w, nx, y, nz, margin, jumpReach);
				break;
			}
			if (ny > y + NavGeometry.STEP_HEIGHT && jumpStep < 0) {
				jumpStep = i;
			}
			vertical += Math.abs(ny - y);
			x = nx;
			y = ny;
			z = nz;
			clear = i;
			if (comfort && i % 2 == 0) {
				comfyCount++;
				if (NavGeometry.bodyFreeAt(w, x, y, z, COMFORT_MARGIN)) {
					comfy++;
				}
			}
		}
		return new Rollout(clear, x, y, z, jumpStep, comfyCount == 0 ? 1.0 : (double) comfy / comfyCount, blocked, vertical, reason);
	}

	/** Cause du blocage : CLEARANCE (passe sans marge), VOID (pas de sol), HEIGHT (marche trop haute) ou WALL. */
	private static String classify(NavWorld w, double nx, double y, double nz, double margin, double jumpReach) {
		if (NavGeometry.bodyFreeAt(w, nx, y, nz, 0.0) && !NavGeometry.bodyFreeAt(w, nx, y, nz, margin)) {
			return "CLEARANCE";
		}
		if (NavGeometry.bodyFreeAt(w, nx, y, nz, margin)) {
			return "VOID";
		}
		double[] tops = new double[8];
		return NavGeometry.collectTops(w, nx, nz, y + jumpReach + 0.01, y + 2.2, tops) > 0 ? "HEIGHT" : "WALL";
	}
}
