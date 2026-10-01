package com.valafre.automod.debug;

import com.valafre.automod.core.Debug;

import java.util.Locale;

/**
 * Instantané de navigation pour le HUD (lignes NAV) et la trace {@code [NAV]} / {@code [NAV REFUSE]}. Écrit par
 * MovementController et AttackTargetTask, lu par le HUD ; aucune logique de décision ici.
 */
public final class NavDebug {

	private NavDebug() {}

	public static volatile int targetId = -1;
	public static volatile int pathNodes;
	public static volatile String local = "—";
	public static volatile double heading = Double.NaN;
	public static volatile double clear = Double.NaN;
	public static volatile double ground = Double.NaN;
	public static volatile boolean jump;
	public static volatile String camera = "—";
	public static volatile String movement = "—";
	public static volatile String reason = "-";
	public static volatile int replanTicks = 2;
	public static volatile long lastTick = -1000;
	private static long lastLine = -1000;
	private static String lastLocal = "";

	/** Ligne [NAV] périodique (toutes les 10 ticks, ou à chaque changement d'état SAFE/REFUS). */
	public static void trace(long tick, Object globalWaypoint) {
		if (!Debug.enabled()) {
			return;
		}
		boolean changed = !local.equals(lastLocal);
		if (!changed && tick - lastLine < 10) {
			return;
		}
		lastLine = tick;
		lastLocal = local;
		String line = String.format(Locale.ROOT,
			"[NAV] target=%s globalWaypoint=%s path=%d localHeading=%s safe=%s clear=%s feetY=%s jump=%s camera=%s movement=%s replan=%d",
			targetId < 0 ? "—" : String.valueOf(targetId), globalWaypoint, pathNodes,
			Double.isNaN(heading) ? "—" : String.format(Locale.ROOT, "%.0f", heading), local.equals("SAFE"),
			Double.isNaN(clear) ? "—" : String.format(Locale.ROOT, "%.1f", clear),
			Double.isNaN(ground) ? "—" : String.format(Locale.ROOT, "%.3f", ground), jump, camera, movement, replanTicks);
		Debug.log("NAV", () -> line);
	}

	/** Trajectoire refusée : raison, case bloquée, cap, distance libre. */
	public static void refuse(String why, Object blocked, double headingDeg, double clearBlocks) {
		if (!Debug.enabled()) {
			return;
		}
		String line = String.format(Locale.ROOT, "[NAV REFUSE] reason=%s blocked=%s heading=%.0f clear=%.1f", why, blocked, headingDeg, clearBlocks);
		Debug.log("NAV", () -> line);
	}
}
