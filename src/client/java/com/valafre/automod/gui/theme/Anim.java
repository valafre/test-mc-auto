package com.valafre.automod.gui.theme;

/** Animations légères : interpolation exponentielle indépendante du taux d'images. */
public final class Anim {

	private static long last = System.nanoTime();
	private static float dt = 0.016f;
	private static float time;

	private Anim() {}

	/** Appelé une fois par image par l'écran qui s'affiche. */
	public static void frame() {
		long now = System.nanoTime();
		dt = Math.min(0.1f, (now - last) / 1.0E9f);
		last = now;
		time += dt;
	}

	public static float time() {
		return time;
	}

	/** Rapproche {@code current} de {@code target} ; {@code rate} ≈ 14 donne ~150 ms. */
	public static float approach(float current, float target, float rate) {
		float speed = Theme.animSpeed();
		if (speed <= 0f) {
			return target;
		}
		float k = 1f - (float) Math.exp(-rate * speed * dt);
		float next = current + (target - current) * k;
		return Math.abs(target - next) < 0.002f ? target : next;
	}
}
