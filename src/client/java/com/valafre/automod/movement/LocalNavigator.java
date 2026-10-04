package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.nav.LocalPlanner;
import com.valafre.automod.nav.LocalRequest;
import com.valafre.automod.nav.LocalResult;
import com.valafre.automod.nav.NavGeometry;
import com.valafre.automod.nav.NavGrid;
import com.valafre.automod.nav.NavParams;
import com.valafre.automod.nav.NavWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Navigation locale prédictive, côté thread Minecraft. La planification lourde (trajectoires candidates simulées sur la vraie
 * hauteur du sol, score progression / marge / continuité / mobilité / stabilité / combat) est faite par {@link LocalPlanner} dans
 * le worker, sur un snapshot. Ici on ne fait que : déposer la demande (retour immédiat, au plus une en cours + la plus récente),
 * lire le dernier résultat sans attendre, et le VALIDER par un contrôle rapide (un seul rollout sur la géométrie en cache) avant de
 * l'exécuter. Sans résultat exploitable : repli léger (cap vers le guide s'il est libre, sinon cap précédent), sinon attente
 * brève — jamais un cap vers un obstacle connu.
 *
 * <p>Si AUCUNE trajectoire n'est sûre : {@code noSafeTrajectory}, point null.
 */
public final class LocalNavigator {

	/** Contexte de combat : point à garder en vue (cible) et distance souhaitée. */
	public record Combat(Vec3 aim, double range) {}

	/**
	 * Résultat. Si {@code noSafeTrajectory} : AUCUNE trajectoire n'est sûre ; {@code point} est alors null et il ne faut exécuter
	 * aucun cap vers l'obstacle ({@code safe} est faux). {@code pending} : le planificateur n'a pas encore répondu et aucun repli
	 * sûr n'existe : on attend quelques ticks (pas un refus). {@code reason} : WALL / CLEARANCE / HEIGHT / VOID.
	 */
	public record Steering(Vec3 point, double headingDeg, boolean safe, int clearSteps, boolean jump, boolean sprintOk,
						   BlockPos blockedCell, boolean noSafeTrajectory, Vec3 nudgePoint, String reason,
						   double clearDistance, double feetY, boolean pending) {}

	private static final double LOOK_DISTANCE = 3.5;
	/** Âge maximal (ticks, depuis le dépôt de la demande) d'un résultat encore exploitable. */
	private static final int MAX_AGE_TICKS = 8;
	private static final int NO_SAFE_MAX_AGE_TICKS = 5;

	private final NavService service = NavService.get();
	private final Map<Long, Long> requestTicks = new HashMap<>();
	private long generation = 1;
	private long sequence;
	private long requestTick = Long.MIN_VALUE / 2;
	private Vec3 guideAtRequest;
	private LocalResult latest;
	private long latestRequestTick;
	private double prevHeading = Double.NaN;

	// Engagement de cap : évite qu'une petite variation de score toutes les 2 ticks fasse alterner gauche/droite.
	// On garde un cap tant qu'il reste praticable ; seul un vrai avantage ou une perte de sécurité force le changement.
	private static final int HEADING_HOLD_TICKS = 6;
	private static final double HEADING_SWITCH_DEGREES = 18.0;
	private static final int HEADING_CLEAR_ADVANTAGE_STEPS = 2;
	private static final double HEADING_ALIGNMENT_ADVANTAGE_DEGREES = 28.0;
	private double committedHeading = Double.NaN;
	private long committedUntilTick = Long.MIN_VALUE / 2;

	public void reset() {
		generation++;
		latest = null;
		guideAtRequest = null;
		requestTick = Long.MIN_VALUE / 2;
		prevHeading = Double.NaN;
		committedHeading = Double.NaN;
		committedUntilTick = Long.MIN_VALUE / 2;
		requestTicks.clear();
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
		long tick = level.getGameTime();
		NavParams params = NavService.params(false);

		// 1. Résultat du worker (non bloquant).
		LocalResult r = service.worker().local.poll();
		if (r != null) {
			service.recordLocal(r);
			if (r.generation() == generation) {
				latest = r;
				Long t = requestTicks.get(r.id());
				latestRequestTick = t == null ? tick : t;
				requestTicks.keySet().removeIf(id -> id <= r.id());
			}
		}

		// 2. Nouvelle demande si nécessaire (toutes les navReplanTicks, ou le guide a bougé de plus d'un bloc).
		boolean due = latest == null && guideAtRequest == null
			|| tick - requestTick >= Math.max(1, cfg.navReplanTicks)
			|| guideAtRequest != null && guideAtRequest.distanceToSqr(guide) > 1.0;
		if (due) {
			submit(state, guide, combat, params, tick);
		}

		// 3. Choix : résultat du worker validé, sinon repli léger.
		int minSafe = (int) Math.ceil(params.minSafeDistance() / Mth.clamp(params.sampleDistance(), 0.1, 0.5));
		NavWorld live = service.live(level);
		Steering s = fromLatest(state, live, pos, params, minSafe, tick);
		if (s == null) {
			s = fallback(state, live, pos, guide, params, minSafe);
		}
		if (!s.pending() && !s.noSafeTrajectory()) {
			prevHeading = s.headingDeg();
		}
		return s;
	}

	private void submit(PlayerState state, Vec3 guide, Combat combat, NavParams params, long tick) {
		ModConfig cfg = ModConfig.get();
		Vec3 pos = state.position();
		int radius = Math.max(6, cfg.navLocalSnapshotRadius);
		NavigationWorldCache cache = service.cache();
		cache.begin(state.level(), cfg.navTileTtlTicks);
		int minX = (int) Math.floor(pos.x) - radius;
		int minY = (int) Math.floor(pos.y) - 4;
		int minZ = (int) Math.floor(pos.z) - radius;
		int maxX = (int) Math.floor(pos.x) + radius;
		int maxY = (int) Math.floor(pos.y) + 5;
		int maxZ = (int) Math.floor(pos.z) + radius;
		cache.prepareRegion(minX, minY, minZ, maxX, maxY, maxZ, pos.x, pos.z, Math.min(0.9, Math.max(0.15, cfg.navSnapshotBudgetMs * 0.35)));
		NavGrid grid = cache.snapshot(minX, minY, minZ, maxX, maxY, maxZ, pos.x, pos.z, cfg.navSnapshotBudgetMs);
		long id = ++sequence;
		LocalRequest req = new LocalRequest(id, generation, grid, pos.x, pos.y, pos.z, guide.x, guide.y, guide.z,
			state.horizontalSpeed(), prevHeading, combat != null,
			combat == null ? 0 : combat.aim().x, combat == null ? 0 : combat.aim().y, combat == null ? 0 : combat.aim().z,
			combat == null ? 0 : combat.range(), params);
		requestTicks.put(id, tick);
		service.worker().local.submit(req);
		requestTick = tick;
		guideAtRequest = guide;
	}

	/** Résultat du worker encore exploitable, validé par un contrôle rapide sur la position ACTUELLE ; sinon null. */
	private Steering fromLatest(PlayerState state, NavWorld live, Vec3 pos, NavParams params, int minSafe, long tick) {
		if (latest == null) {
			return null;
		}
		long age = tick - latestRequestTick;
		if (latest.noSafe()) {
			if (age > NO_SAFE_MAX_AGE_TICKS) {
				return null;
			}
			Vec3 nudge = Double.isNaN(latest.nudgeHeading()) ? null : pointAlong(pos, latest.nudgeHeading(), 1.2);
			return new Steering(null, latest.heading(), false, latest.clearSteps(), false, false, cell(latest.blockedCell()), true,
				nudge, latest.reason(), latest.clearDistance(), pos.y, false);
		}
		if (age > MAX_AGE_TICKS) {
			return null;
		}
		// Le joueur a avancé depuis la demande : le cap engagé est-il TOUJOURS libre depuis ici ?
		double heading = effectiveHeading(live, pos, guideAtRequest, latest.heading(), params, minSafe, tick);
		int clear = LocalPlanner.clearSteps(live, pos.x, pos.y, pos.z, heading, minSafe, params);
		if (clear < minSafe) {
			return null;
		}
		return new Steering(pointAlong(pos, heading, LOOK_DISTANCE), heading, true, clear,
			latest.jump(), latest.sprintOk() && age <= 3, cell(latest.blockedCell()), false, null, "-",
			clear * params.sampleDistance(), pos.y, false);
	}

	/**
	 * Stabilise le choix du côté. Deux directions quasi équivalentes ne doivent pas se remplacer toutes les 100 ms.
	 * Le cap courant est conservé pendant une courte fenêtre et n'est remplacé que s'il devient dangereux ou si le nouveau
	 * cap apporte un avantage net (ou rapproche nettement du guide).
	 */
	private double effectiveHeading(NavWorld live, Vec3 pos, Vec3 guide, double candidateHeading, NavParams params, int minSafe, long tick) {
		if (Double.isNaN(committedHeading)) {
			committedHeading = candidateHeading;
			committedUntilTick = tick + HEADING_HOLD_TICKS;
			prevHeading = candidateHeading;
			return candidateHeading;
		}

		int currentClear = LocalPlanner.clearSteps(live, pos.x, pos.y, pos.z, committedHeading, minSafe, params);
		double candidateDelta = Math.abs(wrap(candidateHeading - committedHeading));

		// Si le cap actuel devient réellement dangereux, on change immédiatement.
		if (currentClear < minSafe) {
			committedHeading = candidateHeading;
			committedUntilTick = tick + HEADING_HOLD_TICKS;
			prevHeading = committedHeading;
			return committedHeading;
		}

		// Petite variation : pas de changement de côté.
		if (candidateDelta < HEADING_SWITCH_DEGREES) {
			prevHeading = committedHeading;
			return committedHeading;
		}

		// Fenêtre d'engagement : on garde la décision, sauf nécessité de sécurité traitée ci-dessus.
		if (tick < committedUntilTick) {
			prevHeading = committedHeading;
			return committedHeading;
		}

		int candidateClear = Math.max(0, latest == null ? 0 : latest.clearSteps());
		double guideYaw = guide == null ? candidateHeading : LocalPlanner.yawTo(pos.x, pos.z, guide.x, guide.z);
		double currentAlign = Math.abs(wrap(guideYaw - committedHeading));
		double candidateAlign = Math.abs(wrap(guideYaw - candidateHeading));
		boolean clearAdvantage = candidateClear >= currentClear + HEADING_CLEAR_ADVANTAGE_STEPS;
		boolean alignmentAdvantage = candidateAlign + HEADING_ALIGNMENT_ADVANTAGE_DEGREES <= currentAlign && candidateClear >= minSafe;

		if (clearAdvantage || alignmentAdvantage) {
			committedHeading = candidateHeading;
			committedUntilTick = tick + HEADING_HOLD_TICKS;
		}
		prevHeading = committedHeading;
		return committedHeading;
	}

	/** Repli léger sur le thread Minecraft (un ou deux rollouts en cache) : cap vers le guide s'il est libre, sinon cap précédent. */
	private Steering fallback(PlayerState state, NavWorld live, Vec3 pos, Vec3 guide, NavParams params, int minSafe) {
		double guideYaw = LocalPlanner.yawTo(pos.x, pos.z, guide.x, guide.z);
		int need = minSafe + 2;
		if (LocalPlanner.clearSteps(live, pos.x, pos.y, pos.z, guideYaw, need, params) >= need) {
			return new Steering(pointAlong(pos, guideYaw, LOOK_DISTANCE), guideYaw, true, need, false, false, null, false, null,
				"-", need * params.sampleDistance(), pos.y, false);
		}
		if (!Double.isNaN(prevHeading)
			&& LocalPlanner.clearSteps(live, pos.x, pos.y, pos.z, prevHeading, minSafe, params) >= minSafe) {
			return new Steering(pointAlong(pos, prevHeading, LOOK_DISTANCE), prevHeading, true, minSafe, false, false, null, false,
				null, "-", minSafe * params.sampleDistance(), pos.y, false);
		}
		return new Steering(null, guideYaw, false, 0, false, false, null, false, null, "PENDING", 0, pos.y, true);
	}

	private static BlockPos cell(long packed) {
		return packed == NavGeometry.NO_BLOCK ? null : BlockPos.of(packed);
	}

	/** Normalise un angle en degrés dans l'intervalle [-180, 180). */
	private static double wrap(double degrees) {
		double wrapped = degrees % 360.0;
		if (wrapped >= 180.0) {
			wrapped -= 360.0;
		}
		if (wrapped < -180.0) {
			wrapped += 360.0;
		}
		return wrapped;
	}

	private static Vec3 pointAlong(Vec3 pos, double headingDeg, double distance) {
		double rad = Math.toRadians(headingDeg);
		return new Vec3(pos.x - Math.sin(rad) * distance, pos.y, pos.z + Math.cos(rad) * distance);
	}
}
