package com.valafre.automod.gui;

import com.valafre.automod.core.Framework;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;

/**
 * Applique la rotation de la caméra à CHAQUE image rendue (et non seulement à chaque tick), même si l'interface est
 * masquée : c'est ce qui donne un mouvement continu au lieu de 20 sauts par seconde.
 */
public final class CameraFrameHook {

	private CameraFrameHook() {}

	public static void register(Framework framework) {
		LevelRenderEvents.START_MAIN.register(context -> {
			Minecraft mc = framework.minecraft();
			if (mc.player != null) {
				framework.rotation().frameUpdate(mc.player, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			}
		});
	}
}
