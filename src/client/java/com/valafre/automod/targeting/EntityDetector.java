package com.valafre.automod.targeting;

import com.valafre.automod.core.PlayerState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/** Recherche d'entités autour du joueur (générique). */
public final class EntityDetector {

	/** Entités vivantes/valides de la classe donnée dans un cube de rayon {@code range} autour du joueur, filtrées par {@code filter}. */
	public <T extends Entity> List<T> find(PlayerState state, Class<T> type, double range, Predicate<? super T> filter) {
		AABB area = state.player().getBoundingBox().inflate(range);
		double rangeSqr = range * range;
		return state.level().getEntitiesOfClass(type, area,
			e -> e.isAlive() && !e.isRemoved() && e.distanceToSqr(state.player()) <= rangeSqr && filter.test(e));
	}

	/** Nom personnalisé nettoyé des codes de formatage et passé en minuscules ; chaîne vide s'il n'y en a pas. */
	public static String cleanCustomName(Entity entity) {
		Component name = entity.getCustomName();
		if (name == null) {
			return "";
		}
		String stripped = ChatFormatting.stripFormatting(name.getString());
		return stripped == null ? "" : stripped.toLowerCase(java.util.Locale.ROOT);
	}
}
