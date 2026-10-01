package com.valafre.automod;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.InspectTool;
import com.valafre.automod.core.TickManager;
import com.valafre.automod.gui.AutoModScreen;
import com.valafre.automod.gui.CameraFrameHook;
import com.valafre.automod.gui.DiagnosticsHud;
import com.valafre.automod.gui.hud.DefaultHudSections;
import com.valafre.automod.gui.settings.DefaultCategories;
import com.valafre.automod.modules.slayer.voidgloom.VoidgloomModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Point d'entrée client : compose le framework, enregistre les modules, les touches et le pipeline de tick. */
public class AutoModClient implements ClientModInitializer {

	private Framework framework;
	private KeyMapping toggleVoidgloomKey;
	private KeyMapping panicKey;
	private KeyMapping inspectKey;
	private KeyMapping menuKey;
	private KeyMapping recordKey;

	@Override
	public void onInitializeClient() {
		ModConfig.load();
		framework = new Framework(Minecraft.getInstance());
		framework.modules().register(new VoidgloomModule());

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("automod", "main"));
		toggleVoidgloomKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.automod.toggle_voidgloom", GLFW.GLFW_KEY_UNKNOWN, category));
		panicKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.automod.panic", GLFW.GLFW_KEY_END, category));

		inspectKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.automod.inspect", GLFW.GLFW_KEY_I, category));

		menuKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.automod.menu", GLFW.GLFW_KEY_INSERT, category));

		recordKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.automod.record_camera", GLFW.GLFW_KEY_PAGE_UP, category));
		DefaultCategories.registerAll();
		DefaultHudSections.registerAll();
		DiagnosticsHud.register(framework);
		CameraFrameHook.register(framework);
		new TickManager(framework).register();
		ClientTickEvents.END_CLIENT_TICK.register(this::handleKeys);
	}

	private void handleKeys(Minecraft mc) {
		while (panicKey.consumeClick()) {
			framework.safety().panic();
		}
		while (recordKey.consumeClick()) {
			boolean started = framework.recorder().toggle() != null;
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal(started
					? "[AutoMod] Enregistrement caméra démarré : " + framework.recorder().file().getFileName()
					: "[AutoMod] Enregistrement caméra arrêté : " + framework.recorder().file()));
			}
		}
		while (menuKey.consumeClick()) {
			mc.setScreen(new AutoModScreen(framework));
		}
		while (inspectKey.consumeClick()) {
			InspectTool.inspect(mc);
		}
		while (toggleVoidgloomKey.consumeClick()) {
			boolean enabled = framework.modules().toggle(framework, VoidgloomModule.ID);
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal("[AutoMod] Voidgloom : " + (enabled ? "activé" : "désactivé")));
			}
		}
	}
}
