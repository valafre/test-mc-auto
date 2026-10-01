import com.valafre.automod.nav.*;

import java.util.*;

/**
 * Banc hors jeu du moteur de navigation (aucune dépendance Minecraft) : monde synthétique irrégulier (piliers, murs, demi-dalles,
 * escaliers, fosses). Mesure A* + lissage et planification locale, avec compteurs de tests de collision.
 * Lancer : tools/run-navbench.sh
 */
public final class NavBench {

	static final int SIZE = 96;      // X et Z
	static final int YMIN = 56, YMAX = 80;

	static final class World implements NavWorld {
		final byte[][][] kind = new byte[SIZE][YMAX - YMIN][SIZE]; // 0 air, 1 plein, 2 demi-dalle basse, 3 marche (escalier simplifié)
		long checks;
		static final float[] SLAB = {0, 0, 0, 1, 0.5f, 1};
		static final float[] STAIR = {0, 0, 0, 1, 0.5f, 1, 0.5f, 0.5f, 0, 1, 1, 1};

		boolean in(int x, int y, int z) { return x >= 0 && z >= 0 && x < SIZE && z < SIZE && y >= YMIN && y < YMAX; }
		public int flags(int x, int y, int z) { return in(x, y, z) ? KNOWN : 0; }
		public float[] boxes(int x, int y, int z) {
			if (!in(x, y, z)) return FULL;
			return switch (kind[x][y - YMIN][z]) { case 1 -> FULL; case 2 -> SLAB; case 3 -> STAIR; default -> NONE; };
		}
		public void countCheck() { checks++; }
	}

	static World build(long seed) {
		World w = new World();
		Random r = new Random(seed);
		int ground = 64;
		for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
			int h = ground - 1 + (int) Math.round(Math.sin(x / 7.0) * Math.cos(z / 9.0) * 1.4);
			for (int y = YMIN; y <= h; y++) w.kind[x][y - YMIN][z] = 1;
			if (r.nextInt(9) == 0) w.kind[x][h + 1 - YMIN][z] = 2;
			if (r.nextInt(30) == 0) for (int y = h + 1; y < h + 4; y++) w.kind[x][y - YMIN][z] = 1; // pilier
		}
		for (int i = 0; i < 6; i++) { // murs avec passage
			int wx = 10 + r.nextInt(SIZE - 20), gap = 5 + r.nextInt(SIZE - 10);
			for (int z = 0; z < SIZE; z++) if (Math.abs(z - gap) > 1) for (int y = 64; y < 68; y++) w.kind[wx][y - YMIN][z] = 1;
		}
		return w;
	}

	static NavParams params(int maxNodes, double budget) {
		return new NavParams(1.2, 6, 1.5, 4.0, maxNodes, budget, 0.12, true, 0.25, 4.5, 1.15, 1.4, 2.0, 1.5, 1.0, 2.0, 8.0);
	}

	static double standY(World w, int x, int z) {
		for (int y = YMAX - 2; y > YMIN; y--) { double s = NavGeometry.surfaceY(w, x, y, z); if (!Double.isNaN(s)) return s; }
		return Double.NaN;
	}

	public static void main(String[] a) {
		int runs = 200;
		World w = build(42);
		Random r = new Random(7);
		for (int budget : new int[] {1500, 3000}) {
			double[] ms = new double[runs]; long nodes = 0, checks = 0; int complete = 0, partial = 0, failed = 0, direct = 0;
			double smooth = 0; int n = 0;
			// échauffement JIT
			for (int i = 0; i < 40; i++) one(w, r, budget, false);
			w.checks = 0;
			for (int i = 0; i < runs; i++) {
				PathResult res = one(w, r, budget, false);
				if (res == null) continue;
				ms[n++] = res.computeMs(); nodes += res.nodes(); checks += w.checks; w.checks = 0; smooth += res.smoothMs();
				switch (res.status()) { case COMPLETE -> complete++; case PARTIAL -> partial++; default -> failed++; }
				if (res.directLine()) direct++;
			}
			double[] m = Arrays.copyOf(ms, n); Arrays.sort(m);
			System.out.printf(Locale.ROOT, "[BENCH A*] maxNodes=%d jobs=%d complete=%d partial=%d failed=%d direct=%d | ms p50=%.2f p95=%.2f max=%.2f | nodes avg=%d checks avg=%d smooth avg=%.2f ms%n",
				budget, n, complete, partial, failed, direct, m[n / 2], m[(int) (n * 0.95)], m[n - 1], nodes / n, checks / n, smooth / n);
		}
		workerTest(w);
		// planification locale
		double[] lm = new double[runs]; long lchecks = 0; int lr = 0, nosafe = 0, n = 0;
		for (int i = 0; i < 300; i++) local(w, r);
		w.checks = 0;
		for (int i = 0; i < runs; i++) {
			LocalResult res = local(w, r);
			if (res == null) continue;
			lm[n++] = res.computeMs(); lchecks += w.checks; w.checks = 0; lr += res.rollouts(); if (res.noSafe()) nosafe++;
		}
		double[] m = Arrays.copyOf(lm, n); Arrays.sort(m);
		System.out.printf(Locale.ROOT, "[BENCH LOCAL] jobs=%d noSafe=%d | ms p50=%.3f p95=%.3f max=%.3f | rollouts avg=%d checks avg=%d%n",
			n, nosafe, m[n / 2], m[(int) (n * 0.95)], m[n - 1], lr / n, lchecks / n);
	}

	/** Le thread appelant (« Minecraft ») dépose 2000 demandes en rafale : submit() ne doit jamais bloquer, rien ne s'empile. */
	static void workerTest(World w) {
		NavWorker worker = new NavWorker();
		Random r = new Random(11);
		long maxSubmitNs = 0;
		int polled = 0;
		long start = System.nanoTime();
		for (int i = 0; i < 2000; i++) {
			int sx = 3 + r.nextInt(SIZE - 6), sz = 3 + r.nextInt(SIZE - 6), gx = 3 + r.nextInt(SIZE - 6), gz = 3 + r.nextInt(SIZE - 6);
			double sy = standY(w, sx, sz), gy = standY(w, gx, gz);
			if (Double.isNaN(sy) || Double.isNaN(gy)) continue;
			PathRequest req = new PathRequest(i, 1, w, sx + 0.5, sy, sz + 0.5, gx, NavGeometry.cellY(gy), gz, gx + 0.5, gy, gz + 0.5,
				params(1500, 25.0), new long[0]);
			long t = System.nanoTime();
			worker.global.submit(req);
			maxSubmitNs = Math.max(maxSubmitNs, System.nanoTime() - t);
			if (worker.global.poll() != null) polled++;
			try { Thread.sleep(0, 200_000); } catch (InterruptedException e) { }
		}
		try { Thread.sleep(200); } catch (InterruptedException e) { }
		if (worker.global.poll() != null) polled++;
		System.out.printf(Locale.ROOT, "[BENCH WORKER] déposées=%d remplacées(jetées avant calcul)=%d calculées=%d lues=%d | submit max=%.4f ms (jamais bloquant) | durée=%.0f ms%n",
			worker.global.submittedCount(), worker.global.droppedCount(), worker.global.completedCount(), polled, maxSubmitNs / 1.0E6, (System.nanoTime() - start) / 1.0E6);
		worker.shutdown();
	}

	static PathResult one(World w, Random r, int maxNodes, boolean unused) {
		int sx = 3 + r.nextInt(SIZE - 6), sz = 3 + r.nextInt(SIZE - 6), gx = 3 + r.nextInt(SIZE - 6), gz = 3 + r.nextInt(SIZE - 6);
		double sy = standY(w, sx, sz), gy = standY(w, gx, gz);
		if (Double.isNaN(sy) || Double.isNaN(gy)) return null;
		PathRequest req = new PathRequest(1, 1, w, sx + 0.5, sy, sz + 0.5, gx, NavGeometry.cellY(gy), gz, gx + 0.5, gy, gz + 0.5,
			params(maxNodes, 25.0), new long[0]);
		return PathEngine.search(req);
	}

	static LocalResult local(World w, Random r) {
		int sx = 5 + r.nextInt(SIZE - 10), sz = 5 + r.nextInt(SIZE - 10);
		double sy = standY(w, sx, sz);
		if (Double.isNaN(sy)) return null;
		double ang = r.nextDouble() * Math.PI * 2;
		LocalRequest req = new LocalRequest(1, 1, w, sx + 0.5, sy, sz + 0.5, sx + 0.5 + Math.cos(ang) * 8, sy, sz + 0.5 + Math.sin(ang) * 8,
			0.2, Double.NaN, false, 0, 0, 0, 0, params(1500, 25.0));
		return LocalPlanner.plan(req);
	}
}
