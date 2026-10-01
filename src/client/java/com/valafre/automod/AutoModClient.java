package com.valafre.automod;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.InspectTool;
import com.valafre.automod.core.TickManager;
import com.valafre.automod.gui.CameraFrameHook;
import com.valafre.automod.gui.config.GuiModuleRegistry;
import com.valafre.automod.gui.config.Keybinds;
import com.valafre.automod.gui.hud.HudElementHook;
import com.valafre.automod.gui.screens.MainScreen;
import com.valafre.automod.modules.slayer.voidgloom.VoidgloomGuiModule;
import com.valafre.automod.modules.slayer.voidgloom.VoidgloomModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Point d'entrée client : compose le framework, enregistre les modules, les touches et le pipeline de tick. */
public class AutoModClient implements ClientModInitializer {

	private Framework framework;

	@Override
	public void onInitializeClient() {
		ModConfig.load();
		framework = new Framework(Minecraft.getInstance());
		framework.modules().register(new VoidgloomModule());

		GuiModuleRegistry.register(new VoidgloomGuiModule());
		Keybinds.register();
		HudElementHook.register(framework);
		CameraFrameHook.register(framework);
		new TickManager(framework).register();
		ClientTickEvents.END_CLIENT_TICK.register(this::handleKeys);
	}

	private void handleKeys(Minecraft mc) {
		while (Keybinds.emergencyStop.consumeClick()) {
			framework.safety().panic();
		}
		while (Keybinds.recordCamera.consumeClick()) {
			boolean started = framework.recorder().toggle() != null;
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal(started
					? "[AutoMod] Enregistrement caméra démarré : " + framework.recorder().file().getFileName()
					: "[AutoMod] Enregistrement caméra arrêté : " + framework.recorder().file()));
			}
		}
		while (Keybinds.openGui.consumeClick()) {
			mc.setScreen(new MainScreen(framework));
		}
		while (Keybinds.inspect.consumeClick()) {
			InspectTool.inspect(mc);
		}
		while (Keybinds.toggleModule.consumeClick()) {
			String id = GuiModuleRegistry.primaryId();
			if (id != null) {
				boolean enabled = framework.modules().toggle(framework, id);
				if (mc.player != null) {
					mc.player.sendSystemMessage(Component.literal("[AutoMod] " + framework.modules().get(id).displayName()
						+ " : " + (enabled ? "activé" : "désactivé")));
				}
			}
		}
		while (Keybinds.toggleHud.consumeClick()) {
			ModConfig.get().hudEnabled = !ModConfig.get().hudEnabled;
			ModConfig.save();
		}
	}
}
