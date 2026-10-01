package com.valafre.automod.gui.hud;

import com.valafre.automod.core.Framework;
import com.valafre.automod.core.Task;
import com.valafre.automod.modules.slayer.voidgloom.VoidgloomModule;

import java.util.List;

/** Sections du HUD fournies par défaut. */
public final class DefaultHudSections {

	private DefaultHudSections() {}

	public static void registerAll() {
		HudRegistry.register(new HudSection() {
			@Override public String title() { return "Voidgloom"; }
			@Override public int color() { return 0xFFC58BFF; }
			@Override public boolean isVisible(Framework f) { return f.modules().isEnabled(VoidgloomModule.ID); }
			@Override public List<String> lines(Framework f, boolean detailed) {
				if (!detailed) {
					return List.of(f.modules().shortStatus(VoidgloomModule.ID));
				}
				Task task = f.tasks().current();
				return List.of(
					f.modules().status(VoidgloomModule.ID),
					"Tâche : " + (task == null ? "aucune" : task.name()),
					"Mouvement : " + f.movement().lastStatus());
			}
		});
		HudRegistry.register(new HudSection() {
			@Override public String title() { return "Survie"; }
			@Override public int color() { return 0xFF6BFF8A; }
			@Override public boolean isVisible(Framework f) { return f.modules().isEnabled(VoidgloomModule.ID); }
			@Override public List<String> lines(Framework f, boolean detailed) {
				return List.of(detailed ? f.support().status() : f.support().shortStatus());
			}
		});
		HudRegistry.register(new HudSection() {
			@Override public String title() { return "Système"; }
			@Override public int color() { return 0xFF6BDCFF; }
			@Override public boolean isVisible(Framework f) {
				return f.modules().isEnabled(VoidgloomModule.ID) && com.valafre.automod.core.Debug.enabled();
			}
			@Override public List<String> lines(Framework f, boolean detailed) {
				return List.of(
					"Touches : " + f.input().describeApplied(),
					"Sécurité : " + f.safety().lastProblem(),
					"Caméra : " + (f.recorder().isRecording() ? "enregistrement en cours" : "normale"));
			}
		});
	}
}
