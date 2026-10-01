package com.valafre.automod.movement;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.debug.CameraRecorder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Caméra automatique. Les appelants déposent UNE demande de regard par tick ({@link #lookAt}) ; {@link #update}
 * la transforme en mouvement.
 *
 * <p>Modèle (mode naturel) : un ressort amorti critique sur le vecteur d'erreur (lacet, tangage) :
 * <ul>
 *   <li>les deux axes forment UN seul mouvement : même dynamique, donc ils arrivent ensemble ;</li>
 *   <li>montée et descente progressives (accélération puis décélération), vitesse plafonnée ;</li>
 *   <li>la durée dépend de la situation : angle à parcourir et taille angulaire de la cible (petite/loin = plus précis = plus long) ;</li>
 *   <li>la vitesse de la caméra est conservée d'un tick à l'autre, y compris quand la cible change : pas de redémarrage à zéro ;</li>
 *   <li>zone de tolérance proportionnelle à la taille apparente de la hitbox, avec hystérésis : tant que le viseur est bien
 *       sur la cible, on ne fait que suivre son mouvement, sans corrections constantes.</li>
 * </ul>
 */
public final class RotationController {

	/** Saut de direction (degrés) au-delà duquel on considère qu'il s'agit d'une nouvelle cible / d'une téléportation. */
	private static final float RETARGET_JUMP_DEG = 25.0f;
	private static final float COAST_DECAY = 0.6f;
	private static final float COAST_MIN = 0.05f;
	/** Vitesse max quand la caméra suit seulement le chemin (marche) : plus posée que pour viser un ennemi. */
	private static final float WALK_PEAK_DEG = 10.0f;
	private static final double EYE_SMOOTHING = 0.3;
	/** Cible (bord de hitbox) plus proche que ça en horizontal : l'angle visé n'a plus de sens, la caméra se fige. */
	private static final double NEAR_ENEMY_RANGE = 0.9;
	private static final double NEAR_POINT_FREE = 0.8;
	private static final double NEAR_POINT_RANGE = 1.0;

	private record Gaze(Vec3 point, AABB box, String source) {}

	private final CameraRecorder recorder;
	private Gaze gaze;

	// Vitesse de la caméra (degrés/tick), conservée entre les ticks.
	private float vYaw;
	private float vPitch;
	// Direction désirée au tick précédent et vitesse angulaire LISSÉE de la cible (anticipation de son mouvement).
	private boolean hasLast;
	private float lastDesYaw;
	private float lastDesPitch;
	private float targetVYaw;
	private float targetVPitch;
	private boolean locked;
	// Hauteur d'oeil lissée : s'accroupir / sauter ne doit pas faire sauter le tangage visé de plusieurs dizaines de degrés.
	private boolean eyeValid;
	private double eyeYSmooth;

	// Pas du tick courant, appliqué progressivement par frameUpdate (une fois par image rendue).
	private float pendingYaw;
	private float pendingPitch;
	private float applied = 1.0f;

	// Micro-mouvements : suite de sinusoïdes lentes aux phases propres à la session (organique, sans à-coups).
	private static final java.util.Random PHASES = new java.util.Random();
	private final float phaseYawA = PHASES.nextFloat() * 6.2832f;
	private final float phaseYawB = PHASES.nextFloat() * 6.2832f;
	private final float phasePitchA = PHASES.nextFloat() * 6.2832f;
	private final float phasePitchB = PHASES.nextFloat() * 6.2832f;
	private long tremorTick;
	private float lastTremorYaw;
	private float lastTremorPitch;

	public RotationController(CameraRecorder recorder) {
		this.recorder = recorder;
	}

	// ========================================
	// DEMANDES
	// ========================================

	/** Regarder {@code point} (taille de cible inconnue). */
	public void lookAt(Vec3 point) {
		gaze = new Gaze(point, null, "OTHER");
	}

	/**
	 * Regarder {@code point}, qui appartient à la hitbox {@code box} (sert à la taille apparente et à la tolérance).
	 * @param source étiquette (ENEMY, PATH...) pour l'enregistreur de debug
	 */
	public void lookAt(Vec3 point, AABB box, String source) {
		gaze = new Gaze(point, box, source);
	}

	/** Plus de demande : la caméra finit son mouvement en décélérant (pas d'arrêt sec). */
	public void cancel() {
		gaze = null;
	}

	/** Remise à zéro complète (arrêt d'urgence). */
	public void reset() {
		gaze = null;
		vYaw = 0;
		vPitch = 0;
		hasLast = false;
		locked = false;
		pendingYaw = 0;
		pendingPitch = 0;
		applied = 1.0f;
	}

	public boolean isActive() {
		return gaze != null;
	}

	// ========================================
	// MISE À JOUR (une fois par tick)
	// ========================================

	public void update(PlayerState state) {
		flushPending(state.player()); // le pas du tick précédent doit être complet avant de calculer l'écart restant
		ModConfig cfg = ModConfig.get();
		LocalPlayer player = state.player();

		if (gaze == null) {
			coast();
			hasLast = false;
			locked = false;
			eyeValid = false;
			return;
		}

		Vec3 realEye = state.eyePosition();
		eyeYSmooth = eyeValid ? eyeYSmooth + (realEye.y - eyeYSmooth) * EYE_SMOOTHING : realEye.y;
		eyeValid = true;
		Vec3 eye = new Vec3(realEye.x, eyeYSmooth, realEye.z);
		float yaw = player.getYRot();
		float pitch = player.getXRot();
		Vec3 point = gaze.point();
		float errYaw = yawDelta(eye, point, yaw);
		float errPitch = pitchDelta(eye, point, pitch);
		double dist = Math.max(0.3, eye.distanceTo(point));

		// Très près de la cible (ou d'un point de chemin), l'angle jusqu'au point change de 100° pour quelques centimètres :
		// on atténue puis on fige la caméra plutôt que de la laisser balayer d'un côté à l'autre.
		float gain = nearGain(eye, point);
		if (gain < 0.02f) {
			hold(player, yaw, pitch, errYaw, errPitch, dist);
			return;
		}
		errYaw *= gain;
		errPitch *= gain;

		float stepYaw;
		float stepPitch;
		float sizeYaw = 3.0f;
		if (cfg.humanize) {
			float desYaw = computeYaw(eye, point);
			float desPitch = computePitch(eye, point);
			updateTargetMotion(desYaw, desPitch);

			// Taille angulaire apparente de la cible : demi-largeur en lacet, demi-hauteur de la bande haute en tangage.
			sizeYaw = gaze.box() == null ? 3.0f
				: Mth.clamp((float) Math.toDegrees(Math.atan2(gaze.box().getXsize() * 0.5, dist)), 1.5f, 20.0f);
			float sizePitch = gaze.box() == null ? 4.0f
				: Mth.clamp((float) Math.toDegrees(Math.atan2(gaze.box().getYsize() * 0.25, dist)), 2.0f, 25.0f);

			// Zone de tolérance avec hystérésis : dedans, on suit seulement le mouvement de la cible.
			float normalized = Math.max(Math.abs(errYaw) / sizeYaw, Math.abs(errPitch) / sizePitch);
			if (!locked && normalized < cfg.camLockIn) {
				locked = true;
			} else if (locked && normalized > cfg.camLockOut) {
				locked = false;
			}

			// Durée du mouvement selon la situation (angle à parcourir / précision demandée) -> pulsation du ressort.
			double angular = Math.hypot(errYaw * Math.cos(Math.toRadians(pitch)), errPitch);
			double settle = cfg.camMinSettleTicks + cfg.camSettleSlope * log2(1.0 + angular / sizeYaw);
			float omega = (float) (4.0 / settle);
			float follow = locked ? cfg.camLockedFollow : 1.0f; // part du mouvement de la cible reproduite
			// Micro-mouvements : on ajoute leur variation par tick à la vitesse "suivie" (visible même dans la zone de tolérance).
			float tremorYaw = 0;
			float tremorPitch = 0;
			if (cfg.camTremor) {
				tremorTick++;
				float curYaw = cfg.camTremorDeg * (float) (Math.sin(0.41 * tremorTick + phaseYawA) + 0.6 * Math.sin(0.72 * tremorTick + phaseYawB));
				float curPitch = 0.7f * cfg.camTremorDeg * (float) (Math.sin(0.37 * tremorTick + phasePitchA) + 0.6 * Math.sin(0.83 * tremorTick + phasePitchB));
				tremorYaw = curYaw - lastTremorYaw;
				tremorPitch = curPitch - lastTremorPitch;
				lastTremorYaw = curYaw;
				lastTremorPitch = curPitch;
			}
			float ffYaw = follow * targetVYaw + tremorYaw;
			float ffPitch = follow * targetVPitch + tremorPitch;
			float zeta = cfg.camDamping;
			float exYaw = locked ? 0.0f : errYaw;
			float exPitch = locked ? 0.0f : errPitch;

			float moveYaw = 0;
			float movePitch = 0;
			for (int i = 0; i < 2; i++) { // 2 sous-pas : intégration stable
				float dvYaw = (omega * omega * exYaw - 2 * zeta * omega * (vYaw - ffYaw)) * 0.5f;
				float dvPitch = (omega * omega * exPitch - 2 * zeta * omega * (vPitch - ffPitch)) * 0.5f;
				// Accélération plafonnée (sur la norme, donc les deux axes restent cohérents) : pas de démarrage brutal.
				float dv = (float) Math.hypot(dvYaw, dvPitch);
				float maxDv = cfg.camMaxAccelDeg * 0.5f;
				if (dv > maxDv) {
					dvYaw *= maxDv / dv;
					dvPitch *= maxDv / dv;
				}
				vYaw += dvYaw;
				vPitch += dvPitch;
				float speed = (float) Math.hypot(vYaw, vPitch);
				float peak = "ENEMY".equals(gaze.source()) ? cfg.camPeakSpeedDeg : Math.min(cfg.camPeakSpeedDeg, WALK_PEAK_DEG);
				if (speed > peak) { // plafond sur la norme : les deux axes restent cohérents
					float scale = peak / speed;
					vYaw *= scale;
					vPitch *= scale;
				}
				moveYaw += vYaw * 0.5f;
				movePitch += vPitch * 0.5f;
			}
			// Amortissement >= 1 : jamais au-delà de la cible. En dessous, le léger dépassement est voulu.
			if (zeta >= 1.0f && !locked && Math.signum(moveYaw) == Math.signum(errYaw) && Math.abs(moveYaw) > Math.abs(errYaw)) {
				moveYaw = errYaw;
				vYaw = errYaw;
			}
			if (zeta >= 1.0f && !locked && Math.signum(movePitch) == Math.signum(errPitch) && Math.abs(movePitch) > Math.abs(errPitch)) {
				movePitch = errPitch;
				vPitch = errPitch;
			}
			stepYaw = moveYaw;
			stepPitch = movePitch;
		} else {
			// Mode direct (humanisation désactivée) : pas proportionnel borné, sans état.
			stepYaw = directStep(errYaw, cfg.minYawSpeed, cfg.maxYawSpeed, cfg.rotationEaseFactor);
			stepPitch = directStep(errPitch, cfg.minPitchSpeed, cfg.maxPitchSpeed, cfg.rotationEaseFactor);
		}

		apply(player, stepYaw, stepPitch, cfg.smoothFrameRotation);
		recorder.record(gaze.source(), yaw, pitch, errYaw, errPitch, dist, sizeYaw, locked, vYaw, vPitch, stepYaw, stepPitch);
		gaze = null;
	}

	private float nearGain(Vec3 eye, Vec3 point) {
		double horizontal = Math.hypot(point.x - eye.x, point.z - eye.z);
		double t;
		if (gaze.box() != null) {
			t = Math.max(0, horizontal - gaze.box().getXsize() * 0.5) / NEAR_ENEMY_RANGE;
		} else {
			t = (horizontal - NEAR_POINT_FREE) / NEAR_POINT_RANGE;
		}
		t = Mth.clamp(t, 0.0, 1.0);
		return (float) (t * t * (3 - 2 * t));
	}

	/** Cible collée : on garde l'orientation actuelle (la vitesse restante s'éteint doucement). */
	private void hold(LocalPlayer player, float yaw, float pitch, float errYaw, float errPitch, double dist) {
		vYaw *= COAST_DECAY;
		vPitch *= COAST_DECAY;
		targetVYaw = 0;
		targetVPitch = 0;
		hasLast = false;
		locked = true;
		apply(player, vYaw, vPitch, ModConfig.get().smoothFrameRotation);
		recorder.record(gaze.source(), yaw, pitch, errYaw, errPitch, dist, 3.0f, true, vYaw, vPitch, vYaw, vPitch);
		gaze = null;
	}

	/** Vitesse angulaire apparente de la cible (lissée) ; remise à zéro quand la direction saute (nouvelle cible, téléportation). */
	private void updateTargetMotion(float desYaw, float desPitch) {
		if (hasLast) {
			float dy = Mth.wrapDegrees(desYaw - lastDesYaw);
			float dp = desPitch - lastDesPitch;
			if (Math.hypot(dy, dp) > RETARGET_JUMP_DEG) {
				targetVYaw = 0;
				targetVPitch = 0;
				locked = false;
			} else {
				targetVYaw += (dy - targetVYaw) * 0.5f;
				targetVPitch += (dp - targetVPitch) * 0.5f;
			}
		}
		lastDesYaw = desYaw;
		lastDesPitch = desPitch;
		hasLast = true;
	}

	/** Sans demande, la caméra continue sur sa lancée en ralentissant, puis s'arrête. */
	private void coast() {
		vYaw *= COAST_DECAY;
		vPitch *= COAST_DECAY;
		if (Math.hypot(vYaw, vPitch) < COAST_MIN) {
			vYaw = 0;
			vPitch = 0;
		}
		targetVYaw = 0;
		targetVPitch = 0;
		if (vYaw != 0 || vPitch != 0) {
			pendingYaw = vYaw;
			pendingPitch = vPitch;
			applied = 0.0f;
		}
	}

	private void apply(LocalPlayer player, float stepYaw, float stepPitch, boolean perFrame) {
		if (perFrame) {
			pendingYaw = stepYaw;
			pendingPitch = stepPitch;
			applied = 0.0f;
		} else {
			rotateBy(player, stepYaw, stepPitch);
		}
	}

	private static float directStep(float delta, float minSpeed, float maxSpeed, float ease) {
		float abs = Math.abs(delta);
		float speed = Mth.clamp(abs * ease, minSpeed, maxSpeed);
		return Math.copySign(Math.min(abs, speed), delta);
	}

	private static double log2(double x) {
		return Math.log(x) / Math.log(2.0);
	}

	// ========================================
	// APPLICATION PAR IMAGE
	// ========================================

	/**
	 * Appelé à chaque IMAGE rendue (événement de rendu du monde, donc même si l'interface est masquée) : applique la part
	 * du pas du tick correspondant à la progression {@code partialTick} (0..1). yRotO/xRotO suivent pour que
	 * l'interpolation de la caméra reste exacte.
	 */
	public void frameUpdate(LocalPlayer player, float partialTick) {
		float progress = Mth.clamp(partialTick, 0.0f, 1.0f);
		if (applied >= 1.0f || progress <= applied) {
			return;
		}
		float share = progress - applied;
		rotateBy(player, pendingYaw * share, pendingPitch * share);
		applied = progress;
	}

	private void flushPending(LocalPlayer player) {
		if (applied < 1.0f) {
			float rest = 1.0f - applied;
			rotateBy(player, pendingYaw * rest, pendingPitch * rest);
			applied = 1.0f;
		}
	}

	private static void rotateBy(LocalPlayer player, float yaw, float pitch) {
		player.setYRot(player.getYRot() + yaw);
		player.setXRot(Mth.clamp(player.getXRot() + pitch, -90.0f, 90.0f));
		player.yRotO = player.getYRot();
		player.xRotO = player.getXRot();
	}

	// ========================================
	// CALCUL YAW / PITCH
	// ========================================

	/**
	 * Yaw Minecraft : 0° regarde vers +Z (sud), 90° vers -X (ouest). Le vecteur oeil->cible a pour composantes (dx, dz) ;
	 * atan2(-dx, dz) donne donc directement le yaw dans cette convention.
	 */
	public static float computeYaw(Vec3 eye, Vec3 to) {
		double dx = to.x - eye.x;
		double dz = to.z - eye.z;
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	/**
	 * Pitch Minecraft : positif = regarder vers le bas. Une cible plus haute (dy > 0) donne un pitch négatif,
	 * d'où le signe moins devant atan2(dy, distance horizontale).
	 */
	public static float computePitch(Vec3 eye, Vec3 to) {
		double dx = to.x - eye.x;
		double dz = to.z - eye.z;
		double dy = to.y - eye.y;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
	}

	/** Écart de yaw normalisé dans [-180, 180] (chemin le plus court). */
	public static float yawDelta(Vec3 eye, Vec3 to, float currentYaw) {
		return Mth.wrapDegrees(computeYaw(eye, to) - currentYaw);
	}

	public static float pitchDelta(Vec3 eye, Vec3 to, float currentPitch) {
		return computePitch(eye, to) - currentPitch;
	}

	/** Vrai si l'orientation actuelle pointe vers {@code point} dans les tolérances configurées. */
	public boolean isAligned(PlayerState state, Vec3 point) {
		ModConfig cfg = ModConfig.get();
		Vec3 eye = state.eyePosition();
		return Math.abs(yawDelta(eye, point, state.yaw())) <= cfg.alignToleranceYaw
			&& Math.abs(pitchDelta(eye, point, state.pitch())) <= cfg.alignTolerancePitch;
	}
}
