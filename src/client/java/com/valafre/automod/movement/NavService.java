package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Debug;
import com.valafre.automod.nav.LocalResult;
import com.valafre.automod.nav.NavParams;
import com.valafre.automod.nav.NavWorker;
import com.valafre.automod.nav.NavWorld;
import com.valafre.automod.nav.PathResult;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * Point d'entrée unique de la navigation asynchrone (thread Minecraft) : possède le worker (calculs lourds hors thread
 * principal), le cache de géométrie, la fabrique de paramètres et les statistiques {@code [NAV PERF]} agrégées.
 */
public final class NavService {

	private static final NavService INSTANCE = new NavService();

	public static NavService get() {
		return INSTANCE;
	}

	private final NavWorker worker = new NavWorker();
	private final NavigationWorldCache cache = new NavigationWorldCache();

	// Profilage agrégé (thread Minecraft)
	private long mainNanos;
	private long mainMaxNanos;
	private int mainCalls;
	private long lastReport;
	private long globalJobs;
	private double globalMsTotal;
	private double globalMsMax;
	private long globalNodes;
	private long globalChecks;
	private double smoothMsTotal;
	private int complete;
	private int partial;
	private int failed;
	private int discarded;
	private long localJobs;
	private double localMsTotal;
	private double localMsMax;
	private long localChecks;
	private long lastSnapNanos;
	private long lastSnapCount;
	private long lastBuildNanos;
	private long lastBuilt;

	private NavService() {}

	public NavWorker worker() {
		return worker;
	}

	public NavigationWorldCache cache() {
		return cache;
	}

	/** Synchronise le cache avec le monde / tick courant et renvoie la vue live (thread Minecraft). */
	public NavWorld live(Level level) {
		cache.begin(level, ModConfig.get().navTileTtlTicks);
		return cache.live();
	}

	/** Paramètres de navigation copiés de la configuration (immuables). */
	public static NavParams params(boolean light) {
		ModConfig c = ModConfig.get();
		int nodes = light ? Math.max(200, c.pathMaxNodes / 3) : c.pathMaxNodes;
		return new NavParams(c.navMaxClimb, c.maxDropBlocks, c.navClearanceWeight, c.navDeadEndPenalty, nodes,
			light ? c.navMaxComputeTimeMs / 2.0 : c.navMaxComputeTimeMs, c.navSafetyMargin, c.navPathSmoothing, c.navSampleDistance,
			c.navLocalHorizonBlocks, c.navJumpHeight, c.navMinSafeDistance, c.navProgressWeight, c.navTurnPenalty,
			c.navTargetAlignWeight, c.navCombatVisibilityWeight, c.navLocalComputeTimeMs);
	}

	// ========================================
	// PROFILAGE
	// ========================================

	public void addMainNanos(long nanos) {
		mainNanos += nanos;
		mainMaxNanos = Math.max(mainMaxNanos, nanos);
		mainCalls++;
	}

	public void recordGlobal(PathResult r, boolean wasDiscarded) {
		globalJobs++;
		globalMsTotal += r.computeMs();
		globalMsMax = Math.max(globalMsMax, r.computeMs());
		globalNodes += r.nodes();
		globalChecks += r.checks();
		smoothMsTotal += r.smoothMs();
		if (wasDiscarded) {
			discarded++;
		}
		switch (r.status()) {
			case COMPLETE -> complete++;
			case PARTIAL -> partial++;
			default -> failed++;
		}
	}

	public void recordLocal(LocalResult r) {
		localJobs++;
		localMsTotal += r.computeMs();
		localMsMax = Math.max(localMsMax, r.computeMs());
		localChecks += r.checks();
	}

	/** Ligne agrégée [NAV PERF] toutes les 100 ticks (debug) : jamais une ligne par nœud ou par job. */
	public void report(Level level) {
		long tick = level.getGameTime();
		if (!Debug.enabled() || tick - lastReport < 100) {
			return;
		}
		lastReport = tick;
		long snapN = cache.snapshots - lastSnapCount;
		double snapMs = snapN == 0 ? 0 : (cache.snapshotNanos - lastSnapNanos) / 1.0E6 / snapN;
		long built = cache.tilesBuilt - lastBuilt;
		double buildMs = (cache.buildNanos - lastBuildNanos) / 1.0E6;
		lastSnapNanos = cache.snapshotNanos;
		lastSnapCount = cache.snapshots;
		lastBuildNanos = cache.buildNanos;
		lastBuilt = cache.tilesBuilt;
		String line = String.format(Locale.ROOT,
			"[NAV PERF] mainThread(avg=%.3f max=%.3f ms/appel, %d appels) snapshot(avg=%.3f ms, %d, tuiles construites=%d en %.2f ms, tronqués=%d)"
				+ " global(jobs=%d avg=%.2f max=%.2f ms nodes avg=%d checks avg=%d smooth avg=%.2f ms C/P/F=%d/%d/%d obsolètes=%d)"
				+ " local(jobs=%d avg=%.3f max=%.3f ms checks avg=%d) file(global: run=%s attente=%s déposés=%d remplacés=%d | local: run=%s attente=%s déposés=%d remplacés=%d)",
			mainCalls == 0 ? 0 : mainNanos / 1.0E6 / mainCalls, mainMaxNanos / 1.0E6, mainCalls,
			snapMs, snapN, built, buildMs, cache.snapshotTruncated,
			globalJobs, globalJobs == 0 ? 0 : globalMsTotal / globalJobs, globalMsMax, globalJobs == 0 ? 0 : globalNodes / globalJobs,
			globalJobs == 0 ? 0 : globalChecks / globalJobs, globalJobs == 0 ? 0 : smoothMsTotal / globalJobs, complete, partial, failed, discarded,
			localJobs, localJobs == 0 ? 0 : localMsTotal / localJobs, localMsMax, localJobs == 0 ? 0 : localChecks / localJobs,
			worker.global.isRunning(), worker.global.hasPending(), worker.global.submittedCount(), worker.global.droppedCount(),
			worker.local.isRunning(), worker.local.hasPending(), worker.local.submittedCount(), worker.local.droppedCount());
		Debug.log("NAV PERF", () -> line);
		mainNanos = 0;
		mainMaxNanos = 0;
		mainCalls = 0;
		globalJobs = 0;
		globalMsTotal = 0;
		globalMsMax = 0;
		globalNodes = 0;
		globalChecks = 0;
		smoothMsTotal = 0;
		complete = 0;
		partial = 0;
		failed = 0;
		discarded = 0;
		localJobs = 0;
		localMsTotal = 0;
		localMsMax = 0;
		localChecks = 0;
	}
}
