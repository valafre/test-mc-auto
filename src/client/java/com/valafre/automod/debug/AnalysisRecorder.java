package com.valafre.automod.debug;

import com.valafre.automod.core.Framework;
import com.valafre.automod.input.InputController;
import com.valafre.automod.movement.MovementController;
import com.valafre.automod.movement.RotationController;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Trace complète, une ligne par tick pendant l'enregistrement PageUp.
 * Aucune décision de gameplay : collecte uniquement l'état réellement produit par le pipeline.
 */
public final class AnalysisRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("automod");
    private static final String HEADER = String.join(",",
        "tick","time_ms",
        "px","py","pz","yaw","pitch","vx","vy","vz","on_ground","horizontal_collision",
        "input_owner","input_applied","input_intent",
        "target_id","target_type","tx","ty","tz","target_dist","target_dx","target_dy","target_dz","target_step",
        "target_valid","target_visible","target_stable_sight","target_intent",
        "target_changed","target_teleport",
        "look_enemy","camera_locked","camera_source","camera_applied_source","camera_locked_internal",
        "camera_des_yaw","camera_des_pitch","camera_err_yaw","camera_err_pitch","camera_v_yaw","camera_v_pitch",
        "camera_step_yaw","camera_step_pitch","camera_target_v_yaw","camera_target_v_pitch",
        "camera_tremor_yaw","camera_tremor_pitch","camera_pending_fraction","camera_point_yaw_error",
        "nav_status","nav_target_id","nav_path_generation","nav_path_size","nav_path_index","nav_path_complete",
        "nav_global_x","nav_global_y","nav_global_z","nav_exec_x","nav_exec_y","nav_exec_z",
        "nav_waypoint_valid","nav_safe","nav_no_safe","nav_refusal","nav_last_call_age",
        "nav_local_heading","nav_committed_heading","nav_candidate_heading","nav_candidate_score","nav_straight_score",
        "nav_left_score","nav_right_score","nav_best_score","nav_second_score","nav_candidate_count","nav_hold_remaining",
        "nav_clear_steps","nav_clear_distance","nav_jump","nav_sprint_ok","nav_reason","nav_ground_y",
        "nav_movement_mode","nav_camera_mode","nav_job",
        "target_to_camera_yaw_error","movement_to_target_yaw_error","movement_heading","movement_speed",
        "floor_block","floor_shape_top","feet_height_at","rise_ahead","ahead_block","ahead_shape_top","ahead_segment_clear","ahead_supported",
        "rotation_overwritten","rotation_writes","input_rejected"
    );

    private BufferedWriter writer;
    private Path file;
    private long row;
    private Entity previousTarget;
    private Vec3 previousTargetPosition;

    public boolean isRecording() {
        return writer != null;
    }

    public Path file() {
        return file;
    }

    public Path start() {
        stop();
        try {
            file = FabricLoader.getInstance().getGameDir().resolve("automod-analysis-" + System.currentTimeMillis() + ".csv");
            writer = Files.newBufferedWriter(file);
            writer.write(HEADER);
            writer.newLine();
            writer.flush();
            row = 0;
            previousTarget = null;
            previousTargetPosition = null;
            return file;
        } catch (IOException e) {
            LOGGER.warn("Impossible de démarrer l'analyse complète", e);
            writer = null;
            return null;
        }
    }

    public void stop() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            LOGGER.warn("Erreur à la fermeture de l'analyse complète", e);
        } finally {
            writer = null;
        }
    }

    /**
     * Capture un snapshot logique du tick APRÈS les décisions de tâche, rotation et touches,
     * mais AVANT le reset des diagnostics du tick.
     */
    public void recordTick(Framework f) {
        if (writer == null || f.player().player() == null || f.player().level() == null) {
            return;
        }
        try {
            var ps = f.player();
            var player = ps.player();
            long tick = ps.level().getGameTime();
            Vec3 p = ps.position();
            Vec3 vel = player.getDeltaMovement();

            Entity target = CombatTrace.currentTaskTarget();
            if (target == null) {
                target = CombatTrace.currentModuleTarget();
            }

            Vec3 tpos = target == null ? null : target.position();
            double targetStep = target != null && target == previousTarget && previousTargetPosition != null
                ? tpos.distanceTo(previousTargetPosition) : 0.0;
            boolean targetChanged = target != previousTarget;
            boolean targetTeleport = targetStep > 1.5;
            boolean rawVisible = target != null && f.combat().hasLineOfSight(ps, target);

            MovementController.DebugState nav = f.movement().debugState();
            RotationController.DebugState rot = f.rotation().debugState();

            double targetDist = target == null ? Double.NaN : p.distanceTo(tpos);
            double targetYawError = Double.NaN;
            if (target != null) {
                targetYawError = RotationController.yawDelta(ps.eyePosition(), target.getBoundingBox().getCenter(), player.getYRot());
            }
            double movementHeading = Math.hypot(vel.x, vel.z) > 0.01
                ? Math.toDegrees(Math.atan2(-vel.x, vel.z)) : Double.NaN;
            double movementTargetError = Double.NaN;
            if (target != null && !Double.isNaN(movementHeading)) {
                double targetHeading = RotationController.computeYaw(p, tpos);
                movementTargetError = wrap(movementHeading - targetHeading);
            }

            var level = ps.level();
            var floorCell = com.valafre.automod.movement.Walkability.cellOf(p);
            var floorState = level.getBlockState(floorCell);
            double floorTop = com.valafre.automod.movement.Walkability.surfaceY(level, floorCell);
            double feetHeight = com.valafre.automod.movement.Walkability.feetHeightAt(level, p.x, p.z, p.y, com.valafre.automod.movement.Walkability.JUMP_HEIGHT, 1.1, 0.12);
            double riseAhead = Double.NaN;
            String aheadBlock = "";
            double aheadTop = Double.NaN;
            boolean aheadClear = false;
            boolean aheadSupported = false;
            double dxAhead;
            double dzAhead;
            if (!Double.isNaN(movementHeading)) {
                double rad = Math.toRadians(movementHeading);
                dxAhead = -Math.sin(rad);
                dzAhead = Math.cos(rad);
            } else {
                double rad = Math.toRadians(player.getYRot());
                dxAhead = -Math.sin(rad);
                dzAhead = Math.cos(rad);
            }
            riseAhead = com.valafre.automod.movement.Walkability.riseAhead(level, p, dxAhead, dzAhead);
            var aheadPos = net.minecraft.core.BlockPos.containing(p.x + dxAhead * 0.9, p.y - 0.05, p.z + dzAhead * 0.9);
            var aheadState = level.getBlockState(aheadPos);
            aheadBlock = String.valueOf(aheadState.getBlock());
            var aheadShape = aheadState.getCollisionShape(level, aheadPos);
            aheadTop = aheadShape.isEmpty() ? Double.NaN : aheadShape.max(net.minecraft.core.Direction.Axis.Y);
            Vec3 ahead = new Vec3(p.x + dxAhead * 1.0, p.y, p.z + dzAhead * 1.0);
            aheadClear = com.valafre.automod.movement.Walkability.segmentWalkable(level, p, ahead, 0.0, false);
            aheadSupported = com.valafre.automod.movement.Walkability.supportedAt(level, ahead.x, ahead.y, ahead.z);

            String appliedPointYawError = "";
            if (rot.appliedPoint() != null && target != null) {
                double pyaw = RotationController.computeYaw(ps.eyePosition(), rot.appliedPoint());
                double tyaw = RotationController.computeYaw(ps.eyePosition(), target.getBoundingBox().getCenter());
                appliedPointYawError = fmt(wrap(pyaw - tyaw));
            }

            String targetType = target == null ? "" : safeEntityType(target);
            String[] inputRejected = CombatTrace.inputRejectedText();
            String rotationWrites = CombatTrace.rotationWritesText();

            write(
                tick, System.currentTimeMillis(),
                p.x, p.y, p.z, player.getYRot(), player.getXRot(), vel.x, vel.y, vel.z, ps.onGround(), player.horizontalCollision,
                f.input().owner(), f.input().describeApplied(), f.input().describeIntent(),
                target == null ? "" : Integer.toString(target.getId()), targetType,
                target == null ? "" : fmt(tpos.x), target == null ? "" : fmt(tpos.y), target == null ? "" : fmt(tpos.z),
                target == null ? "" : fmt(targetDist), target == null ? "" : fmt(tpos.x - p.x), target == null ? "" : fmt(tpos.y - p.y),
                target == null ? "" : fmt(tpos.z - p.z), fmt(targetStep),
                target != null && TargetSelectorSafe.isValid(target), rawVisible, CombatTrace.currentStableSight(), CombatTrace.currentIntent(),
                targetChanged, targetTeleport,
                CombatTrace.lookEnemy(), nav.cameraMode().equals("ENEMY"), rot.gazeSource(), rot.appliedSource(), rot.locked(),
                fmt(rot.desiredYaw()), fmt(rot.desiredPitch()), fmt(rot.errorYaw()), fmt(rot.errorPitch()), fmt(rot.vYaw()), fmt(rot.vPitch()),
                fmt(rot.stepYaw()), fmt(rot.stepPitch()), fmt(rot.targetVYaw()), fmt(rot.targetVPitch()),
                fmt(rot.tremorYaw()), fmt(rot.tremorPitch()), fmt(rot.pendingFraction()), appliedPointYawError,
                nav.status(), nav.targetId(), nav.pathGeneration(), nav.pathSize(), nav.pathIndex(), nav.pathComplete(),
                vecX(nav.globalWaypoint()), vecY(nav.globalWaypoint()), vecZ(nav.globalWaypoint()),
                vecX(nav.executedPoint()), vecY(nav.executedPoint()), vecZ(nav.executedPoint()),
                nav.waypointValid(), nav.safe(), nav.noSafe(), nav.refusal(), fmt(Math.max(0, tick - nav.lastCallTick())),
                fmt(nav.localHeading()), fmt(nav.committedHeading()), fmt(nav.candidateHeading()), fmt(nav.candidateScore()), fmt(nav.straightScore()),
                fmt(nav.leftScore()), fmt(nav.rightScore()), fmt(nav.bestScore()), fmt(nav.secondScore()), nav.candidateCount(), nav.headingHoldRemaining(),
                nav.clearSteps(), fmt(nav.clearDistance()), nav.jump(), nav.sprintOk(), nav.reason(), fmt(nav.groundY()),
                nav.movementMode(), nav.cameraMode(), nav.job(),
                fmt(targetYawError), fmt(movementTargetError), fmt(movementHeading), fmt(Math.hypot(vel.x, vel.z)),
                floorState.getBlock().toString(), fmt(floorTop), fmt(feetHeight), fmt(riseAhead), aheadBlock, fmt(aheadTop), aheadClear, aheadSupported,
                CombatTrace.rotationOverwritten(), quote(rotationWrites), quote(String.join(" ", inputRejected))
            );

            previousTarget = target;
            previousTargetPosition = tpos;
            if ((row++ & 31) == 0) {
                writer.flush();
            }
        } catch (Exception e) {
            LOGGER.warn("Erreur d'écriture de l'analyse complète", e);
            stop();
        }
    }

    private void write(Object... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            writer.write(csv(values[i]));
        }
        writer.newLine();
    }

    private static String csv(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String quote(String value) {
        return value == null ? "" : value;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "" : String.format(Locale.ROOT, "%.4f", v);
    }

    private static String vecX(Vec3 v) { return v == null ? "" : fmt(v.x); }
    private static String vecY(Vec3 v) { return v == null ? "" : fmt(v.y); }
    private static String vecZ(Vec3 v) { return v == null ? "" : fmt(v.z); }

    private static double wrap(double v) {
        double r = v % 360.0;
        if (r >= 180) r -= 360;
        if (r < -180) r += 360;
        return r;
    }

    private static String safeEntityType(Entity entity) {
        try {
            return String.valueOf(EntityType.getKey(entity.getType()));
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Évite une dépendance circulaire avec TargetSelector dans ce fichier de debug. */
    private static final class TargetSelectorSafe {
        private static boolean isValid(Entity e) {
            return e != null && e.isAlive() && !e.isRemoved();
        }
    }
}
