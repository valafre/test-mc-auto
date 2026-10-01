package com.valafre.automod.nav;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Worker de navigation : DEUX voies (chemin global, planification locale), chacune avec UN thread dédié, UN calcul en cours et UNE
 * seule demande en attente (la plus récente : une nouvelle demande remplace l'ancienne, rien ne s'empile). Le thread Minecraft
 * ne bloque jamais : {@link Lane#submit} rend la main immédiatement et {@link Lane#poll} lit sans attendre le dernier résultat.
 * Les requêtes et résultats sont immuables ; le worker ne touche que le snapshot contenu dans la requête.
 */
public final class NavWorker {

	/** Une voie : file d'une demande, un thread, un dernier résultat disponible. */
	public static final class Lane<Q, R> {
		private final Object lock = new Object();
		private final Function<Q, R> job;
		private final Function<Throwable, R> onError;
		private final AtomicReference<R> result = new AtomicReference<>();
		private final AtomicLong submitted = new AtomicLong();
		private final AtomicLong dropped = new AtomicLong();
		private final AtomicLong completed = new AtomicLong();
		private Q pending;
		private volatile boolean running;
		private volatile boolean alive = true;

		Lane(String name, Function<Q, R> job, Function<Throwable, R> onError) {
			this.job = job;
			this.onError = onError;
			Thread t = new Thread(this::loop, name);
			t.setDaemon(true);
			t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
			t.start();
		}

		/** Dépose la demande (remplace celle en attente, non encore démarrée). Ne bloque jamais le thread appelant. */
		public void submit(Q request) {
			synchronized (lock) {
				if (pending != null) {
					dropped.incrementAndGet();
				}
				pending = request;
				submitted.incrementAndGet();
				lock.notify();
			}
		}

		/** Dernier résultat terminé (une seule lecture), ou null. Ne bloque jamais. */
		public R poll() {
			return result.getAndSet(null);
		}

		/** Un calcul est en cours ou une demande attend. */
		public boolean busy() {
			synchronized (lock) {
				return running || pending != null;
			}
		}

		public boolean isRunning() {
			return running;
		}

		public boolean hasPending() {
			synchronized (lock) {
				return pending != null;
			}
		}

		public long submittedCount() {
			return submitted.get();
		}

		public long droppedCount() {
			return dropped.get();
		}

		public long completedCount() {
			return completed.get();
		}

		void shutdown() {
			alive = false;
			synchronized (lock) {
				lock.notifyAll();
			}
		}

		private void loop() {
			while (alive) {
				Q req;
				synchronized (lock) {
					while (pending == null && alive) {
						try {
							lock.wait();
						} catch (InterruptedException e) {
							return;
						}
					}
					if (!alive) {
						return;
					}
					req = pending;
					pending = null;
					running = true;
				}
				R res;
				try {
					res = job.apply(req);
				} catch (Throwable t) { // un calcul qui échoue ne doit jamais tuer le worker
					res = onError.apply(t);
				}
				if (res != null) {
					result.set(res);
					completed.incrementAndGet();
				}
				running = false;
			}
		}
	}

	public final Lane<PathRequest, PathResult> global;
	public final Lane<LocalRequest, LocalResult> local;

	public NavWorker() {
		global = new Lane<>("AutoMod-Nav-Global", PathEngine::search, t -> null);
		local = new Lane<>("AutoMod-Nav-Local", LocalPlanner::plan, t -> null);
	}

	public void shutdown() {
		global.shutdown();
		local.shutdown();
	}
}
