package com.valafre.automod;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.TickManager;
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

	@Override
	public void onInitializeClient() {
		ModConfig.load();
		framework = new Framework(Minecraft.getInstance());
		framework.modules().register(new VoidgloomModule());

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("automod", "main"));
		toggleVoidgloomKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.automod.toggle_voidgloom", GLFW.GLFW_KEY_V, category));
		panicKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.automod.panic", GLFW.GLFW_KEY_END, category));

		new TickManager(framework).register();
		ClientTickEvents.END_CLIENT_TICK.register(this::handleKeys);
	}

	private void handleKeys(Minecraft mc) {
		while (panicKey.consumeClick()) {
			framework.safety().panic();
		}
		while (toggleVoidgloomKey.consumeClick()) {
			boolean enabled = framework.modules().toggle(framework, VoidgloomModule.ID);
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal("[AutoMod] Voidgloom : " + (enabled ? "activé" : "désactivé")));
			}
		}
	}
}
