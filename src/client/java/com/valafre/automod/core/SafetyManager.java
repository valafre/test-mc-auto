package com.valafre.automod.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

/**
 * Coupe-circuit : vérifie à chaque tick que l'automatisation peut tourner et sait tout arrêter proprement
 * (tâches, rotation, mouvement, touches, propriété des inputs).
 */
public final class SafetyManager {

	private final Framework framework;
	private ClientLevel lastLevel;
	private boolean wasSafe;

	SafetyManager(Framework framework) {
		this.framework = framework;
	}

	// ========================================
	// SAFETY
	// ========================================

	/** @return true si l'automatisation peut agir ce tick. Sinon tout a déjà été arrêté et les touches relâchées. */
	public boolean check(Minecraft mc) {
		String problem = findProblem(mc);
		if (problem != null) {
			if (wasSafe) {
				stopAll(problem);
			} else {
				framework.input().releaseAll(mc); // garde-fou : jamais de touche restée enfoncée
			}
			wasSafe = false;
			lastLevel = mc.level;
			return false;
		}
		if (lastLevel != null && lastLevel != mc.level) {
			stopAll("changement de monde");
		}
		lastLevel = mc.level;
		wasSafe = true;
		return true;
	}

	private static String findProblem(Minecraft mc) {
		if (mc.player == null) {
			return "joueur absent";
		}
		if (mc.level == null) {
			return "monde absent";
		}
		if (mc.gameMode == null) {
			return "gameMode absent";
		}
		if (!mc.player.isAlive()) {
			return "joueur mort";
		}
		if (mc.player.isSpectator()) {
			return "mode spectateur";
		}
		if (mc.screen != null) {
			return "écran ouvert";
		}
		if (mc.isPaused()) {
			return "jeu en pause";
		}
		return null;
	}

	/** Arrête toute action automatique et remet le système dans un état propre. Les modules restent activés. */
	public void stopAll(String reason) {
		Debug.log("Safety", () -> "stopAll : " + reason);
		framework.tasks().cancelAll(framework);
		framework.movement().reset();
		framework.rotation().cancel();
		framework.combat().reset();
		framework.scoreboard().clear();
		framework.input().releaseAll(framework.minecraft());
		framework.input().clearClaim();
		framework.modules().safetyStop(framework);
	}

	/** Touche panique : stopAll + désactivation de tous les modules. */
	public void panic() {
		stopAll("panique");
		framework.modules().disableAll(framework);
		Minecraft mc = framework.minecraft();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[AutoMod] Arrêt d'urgence : tous les modules sont désactivés."));
		}
	}
}
