package com.valafre.automod.task;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.targeting.TargetInfo;
import net.minecraft.world.entity.Entity;

/**
 * Logique de poursuite partagée par le suivi et l'attaque : on se rapproche jusqu'à la distance d'approche, puis on ne
 * repart qu'au-delà d'une marge (hystérésis, pas de stop-and-go). Permet d'attaquer EN avançant au lieu de s'arrêter.
 */
final class Chase {

	private static final double RESUME_MARGIN = 0.7;
	private static final double NO_SIGHT_STOP = 0.6;

	private boolean moving;

	void step(Framework f, String owner, Entity target, TargetInfo info) {
		ModConfig cfg = ModConfig.get();
		if (!f.combat().hasLineOfSight(f.player(), target)) {
			// Cible derrière un mur : la distance ne dit rien. On contourne (chemin A*) en regardant où l'on marche.
			moving = true;
			f.movement().moveTo(f.player(), owner, target.position(), NO_SIGHT_STOP, true);
			return;
		}
		moving = moving ? info.distance() > cfg.approachDistance : info.distance() > cfg.approachDistance + RESUME_MARGIN;
		if (moving) {
			f.movement().moveTo(f.player(), owner, target.position(), cfg.approachDistance, false);
		} else {
			f.movement().reset();
		}
	}
}
