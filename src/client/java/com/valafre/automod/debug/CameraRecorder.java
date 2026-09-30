package com.valafre.automod.debug;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Enregistreur de la caméra : une ligne CSV par tick (yaw, pitch, erreurs, source du regard, vitesses...).
 * Activé/désactivé par une touche ; le fichier est écrit dans le dossier du jeu. Sert à analyser les mouvements réels.
 */
public final class CameraRecorder {

	private static final Logger LOGGER = LoggerFactory.getLogger("automod");
	private static final String HEADER =
		"tick,source,yaw,pitch,errYaw,errPitch,dist,sizeYaw,locked,vYaw,vPitch,stepYaw,stepPitch";

	private BufferedWriter writer;
	private Path file;
	private long tick;

	public boolean isRecording() {
		return writer != null;
	}

	/** @return le chemin du fichier créé quand l'enregistrement démarre, sinon null. */
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
			tick = 0;
			return file;
		} catch (IOException e) {
			LOGGER.warn("Impossible de démarrer l'enregistrement caméra", e);
			writer = null;
			return null;
		}
	}

	public Path file() {
		return file;
	}

	public void stop() {
		if (writer == null) {
			return;
		}
		try {
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
			writer.write(String.format(java.util.Locale.ROOT, "%d,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%.2f,%.2f,%.2f,%.2f",
				tick++, source, yaw, pitch, errYaw, errPitch, dist, sizeYaw, locked ? 1 : 0, vYaw, vPitch, stepYaw, stepPitch));
			writer.newLine();
		} catch (IOException e) {
			stop();
		}
	}
}
