package com.valafre.automod.core;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Pipeline exécuté au DÉBUT de chaque tick client (les touches posées ici sont donc prises en compte par le tick du joueur).
 * Ordre : sécurité -> état joueur -> scoreboard -> modules -> tâches -> rotation -> inputs.
 */
public final class TickManager {

	private final Framework framework;

	public TickManager(Framework framework) {
		this.framework = framework;
	}

	public void register() {
		ClientTickEvents.START_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		framework.input().beginTick();

		// SAFETY : tout arrêt est traité avant le moindre calcul coûteux.
		if (!framework.safety().check(mc)) {
			return;
		}
		PlayerState state = framework.player();
		if (!state.update(mc)) {
			return;
		}

		framework.items().beginTick(state);    // rend le slot d'origine si une Wand/Orb vient d'être utilisée
		framework.scoreboard().tick(state.level());
		framework.combat().tick();
		framework.modules().tick(framework);   // les modules décident et soumettent des tâches
		framework.tasks().tick(framework);     // la tâche courante utilise mouvement / rotation / combat
		framework.rotation().update(state);    // un seul pas de rotation, après toutes les demandes
		framework.input().endTick(mc);         // application des touches (relâche celles non redemandées)
		com.valafre.automod.debug.CombatTrace.emit(framework); // diagnostic (debug uniquement), aucun effet sur le comportement
		com.valafre.automod.debug.StepTrace.tick(framework);   // diagnostic des surfaces à hauteur partielle (debug uniquement)
	}
}
