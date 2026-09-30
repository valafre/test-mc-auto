package com.valafre.automod.gui;

import com.valafre.automod.core.Framework;
import com.valafre.automod.core.Task;
import com.valafre.automod.modules.slayer.voidgloom.VoidgloomModule;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Petit affichage en jeu (visible seulement si le module est actif) : état, tâche, mouvement, touches, sécurité. */
public final class DiagnosticsHud {

	private DiagnosticsHud() {}

	public static void register(Framework framework) {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("automod", "diagnostics"),
			(HudElement) (graphics, delta) -> render(framework, graphics));
	}

	private static void render(Framework f, GuiGraphicsExtractor graphics) {
		if (!f.modules().isEnabled(VoidgloomModule.ID)) {
			return;
		}
		if (!com.valafre.automod.config.ModConfig.get().hudEnabled) {
			return;
		}
		Minecraft mc = f.minecraft();
		String[] lines;
		if (com.valafre.automod.core.Debug.enabled()) {
			// Mode debug : détail complet.
			Task task = f.tasks().current();
			lines = new String[] {
				"[AutoMod] Voidgloom : " + f.modules().status(VoidgloomModule.ID),
				"Tâche : " + (task == null ? "aucune" : task.name()) + " | Mouvement : " + f.movement().lastStatus(),
				"Touches : " + f.input().describeApplied() + " | Sécurité : " + f.safety().lastProblem(),
				"Survie : " + f.support().status()
			};
		} else {
			// Mode normal : une seule ligne courte.
			lines = new String[] {
				"[AutoMod] " + f.modules().shortStatus(VoidgloomModule.ID) + " | " + f.support().shortStatus()
			};
		}
		int y = mc.getWindow().getGuiScaledHeight() / 2 - 20;
		for (String line : lines) {
			graphics.text(mc.font, line, 4, y, 0xFFFFFF55);
			y += 10;
		}
	}
}
