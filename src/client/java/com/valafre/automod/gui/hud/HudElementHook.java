package com.valafre.automod.gui.hud;

import com.valafre.automod.core.Framework;
import com.valafre.automod.gui.screens.HudEditorScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/** Branche le {@link HudRenderer} sur le HUD du jeu. */
public final class HudElementHook {

	private HudElementHook() {}

	public static void register(Framework framework) {
		HudRegistry.registerDefaults();
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("automod", "hud"), (HudElement) (graphics, delta) -> {
			if (framework.minecraft().screen instanceof HudEditorScreen) {
				return; // l'éditeur dessine lui-même l'aperçu
			}
			HudRenderer.draw(graphics, framework.minecraft(), framework, false);
		});
	}
}
