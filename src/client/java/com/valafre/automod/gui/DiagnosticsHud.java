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
		// Élément appelé à chaque image : fait avancer la rotation en douceur entre deux ticks.
		HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("automod", "rotation_frames"), (HudElement) (graphics, delta) -> {
			Minecraft mc = framework.minecraft();
			if (mc.player != null) {
				framework.rotation().frameUpdate(mc.player, delta.getGameTimeDeltaPartialTick(false));
			}
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("automod", "diagnostics"),
			(HudElement) (graphics, delta) -> render(framework, graphics));
	}

	private static void render(Framework f, GuiGraphicsExtractor graphics) {
		if (!f.modules().isEnabled(VoidgloomModule.ID)) {
			return;
		}
		Minecraft mc = f.minecraft();
		Task task = f.tasks().current();
		String[] lines = {
			"[AutoMod] Voidgloom : " + f.modules().status(VoidgloomModule.ID),
			"Tâche : " + (task == null ? "aucune" : task.name()) + " | Mouvement : " + f.movement().lastStatus(),
			"Touches : " + f.input().describeApplied() + " | Sécurité : " + f.safety().lastProblem()
		};
		int y = mc.getWindow().getGuiScaledHeight() / 2 - 20;
		for (String line : lines) {
			graphics.text(mc.font, line, 4, y, 0xFFFFFF55);
			y += 10;
		}
	}
}
