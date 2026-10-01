package com.valafre.automod.gui.config;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Raccourcis du mod : de vraies {@link KeyMapping} (donc aussi visibles dans les contrôles de Minecraft).
 * Les écrans les affichent et les modifient via cette liste, sans connaître leurs touches.
 */
public final class Keybinds {

	/** Raccourci affiché dans le GUI : libellé court et la mapping réelle. */
	public record Entry(String label, KeyMapping mapping, boolean inPanel) {}

	public static KeyMapping openGui;
	public static KeyMapping toggleModule;
	public static KeyMapping emergencyStop;
	public static KeyMapping toggleHud;
	public static KeyMapping inspect;
	public static KeyMapping recordCamera;

	private static final List<Entry> ENTRIES = new ArrayList<>();

	private Keybinds() {}

	public static void register() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("automod", "main"));
		openGui = reg("key.automod.menu", GLFW.GLFW_KEY_INSERT, category);
		toggleModule = reg("key.automod.toggle_voidgloom", GLFW.GLFW_KEY_UNKNOWN, category);
		emergencyStop = reg("key.automod.panic", GLFW.GLFW_KEY_END, category);
		toggleHud = reg("key.automod.toggle_hud", GLFW.GLFW_KEY_H, category);
		inspect = reg("key.automod.inspect", GLFW.GLFW_KEY_I, category);
		recordCamera = reg("key.automod.record_camera", GLFW.GLFW_KEY_PAGE_UP, category);

		ENTRIES.add(new Entry("Ouvrir le GUI", openGui, true));
		ENTRIES.add(new Entry("Activer / désactiver le module", toggleModule, true));
		ENTRIES.add(new Entry("Arrêt d'urgence", emergencyStop, true));
		ENTRIES.add(new Entry("Afficher / masquer le HUD", toggleHud, true));
		ENTRIES.add(new Entry("Inspecter la cible", inspect, false));
		ENTRIES.add(new Entry("Enregistrer la caméra (CSV)", recordCamera, false));
	}

	private static KeyMapping reg(String name, int key, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, key, category));
	}

	public static List<Entry> all() {
		return ENTRIES;
	}
}
