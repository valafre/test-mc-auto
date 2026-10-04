package com.valafre.automod.debug;

import com.valafre.automod.core.Framework;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Enregistreur caméra + trace complète synchronisée.
 * PageUp démarre/arrête les deux fichiers : le CSV caméra historique et le CSV d'analyse par tick.
 */
public final class CameraRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("automod");
    private static final String HEADER =
        "tick,source,yaw,pitch,errYaw,errPitch,dist,sizeYaw,locked,vYaw,vPitch,stepYaw,stepPitch";

    private final AnalysisRecorder analysis = new AnalysisRecorder();
    private BufferedWriter writer;
    private Path file;
    private long tick;

    public boolean isRecording() {
        return writer != null;
    }

    /** @return le chemin du CSV caméra créé quand l'enregistrement démarre, sinon null. */
    public Path toggle() {
        if (writer != null) {
            stop();
            return null;
        }
        try {
            file = FabricLoader.getInstance().getGameDir().resolve("automod-camera-" + System.currentTimeMillis() + ".csv");
            writer = Files.newBufferedWriter(file);
            writer.write(HEADER);
            writer.newLine();
            writer.flush();
            tick = 0;
            analysis.start();
            return file;
        } catch (IOException e) {
            LOGGER.warn("Impossible de démarrer l'enregistrement caméra", e);
            writer = null;
            analysis.stop();
            return null;
        }
    }

    public Path file() {
        return file;
    }

    public Path analysisFile() {
        return analysis.file();
    }

    /** Appelé une fois par tick après l'application des touches. */
    public void analysisTick(Framework framework) {
        analysis.recordTick(framework);
    }

    public void stop() {
        analysis.stop();
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            LOGGER.warn("Erreur à la fermeture de l'enregistrement caméra", e);
        }
        writer = null;
    }

    public void record(String source, float yaw, float pitch, float errYaw, float errPitch, double dist, float sizeYaw,
                       boolean locked, float vYaw, float vPitch, float stepYaw, float stepPitch) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(String.format(java.util.Locale.ROOT,
                "%d,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%.2f,%.2f,%.2f,%.2f",
                tick++, source, yaw, pitch, errYaw, errPitch, dist, sizeYaw, locked ? 1 : 0, vYaw, vPitch, stepYaw, stepPitch));
            writer.newLine();
            if ((tick & 31) == 0) {
                writer.flush();
            }
        } catch (IOException e) {
            stop();
        }
    }
}
