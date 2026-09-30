package com.valafre.automod.targeting;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.nametag.NametagInfo;
import com.valafre.automod.nametag.NametagParser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Construit des {@link EntityInfo} : associe chaque mob au nametag qui le décrit. Sur les serveurs custom le nametag est
 * rarement le nom du mob : c'est un ArmorStand flottant à sa verticale. Chaque ArmorStand nommé est rattaché au mob le
 * plus proche (un nametag ne décrit qu'un seul mob), puis le nametag le plus informatif est retenu.
 */
public final class EntityInfoResolver {

	/** Écart horizontal maximal entre un mob et son nametag. */
	private static final double MATCH_HORIZONTAL = 1.2;
	private static final double MATCH_BELOW = -0.8;
	private static final double MATCH_ABOVE = 4.5;

	// ========================================
	// NAMETAGS
	// ========================================

	/** Toutes les entités de {@code type} à portée, avec niveau / nom / vie extraits. */
	public <T extends Entity> List<EntityInfo> scan(PlayerState state, Class<T> type, double range, EntityDetector detector) {
		List<T> mobs = detector.find(state, type, range, e -> true);
		if (mobs.isEmpty()) {
			return List.of();
		}
		Map<Entity, List<NametagInfo>> tags = new HashMap<>();
		if (ModConfig.get().allowArmorStandNameplate) {
			List<ArmorStand> stands = detector.find(state, ArmorStand.class, range + MATCH_ABOVE, ArmorStand::hasCustomName);
			for (ArmorStand stand : stands) {
				Entity owner = nearestMob(mobs, stand);
				if (owner != null) {
					tags.computeIfAbsent(owner, k -> new ArrayList<>()).add(NametagParser.parse(stand.getCustomName().getString()));
				}
			}
		}
		List<EntityInfo> result = new ArrayList<>(mobs.size());
		for (T mob : mobs) {
			result.add(build(mob, tags.getOrDefault(mob, List.of())));
		}
		return result;
	}

	/** Analyse d'une seule entité (ex. celle visée) : cherche ses nametags autour d'elle. */
	public EntityInfo resolve(Level level, Entity entity) {
		List<NametagInfo> tags = new ArrayList<>();
		if (ModConfig.get().allowArmorStandNameplate) {
			var area = entity.getBoundingBox().inflate(MATCH_HORIZONTAL, MATCH_ABOVE, MATCH_HORIZONTAL);
			for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, area, ArmorStand::hasCustomName)) {
				if (isAttached(entity, stand)) {
					tags.add(NametagParser.parse(stand.getCustomName().getString()));
				}
			}
		}
		return build(entity, tags);
	}

	private static Entity nearestMob(List<? extends Entity> mobs, ArmorStand stand) {
		Entity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity mob : mobs) {
			if (!isAttached(mob, stand)) {
				continue;
			}
			double dx = mob.getX() - stand.getX();
			double dz = mob.getZ() - stand.getZ();
			double d = dx * dx + dz * dz;
			if (d < bestDist) {
				bestDist = d;
				best = mob;
			}
		}
		return best;
	}

	private static boolean isAttached(Entity mob, ArmorStand stand) {
		double dx = mob.getX() - stand.getX();
		double dz = mob.getZ() - stand.getZ();
		double dy = stand.getY() - mob.getY();
		return dx * dx + dz * dz <= MATCH_HORIZONTAL * MATCH_HORIZONTAL && dy >= MATCH_BELOW && dy <= MATCH_ABOVE;
	}

	/** Choisit le nametag le plus informatif (vie > niveau > nom) parmi son propre nom et les ArmorStands rattachés. */
	private static EntityInfo build(Entity entity, List<NametagInfo> attached) {
		NametagInfo best = NametagInfo.EMPTY;
		int bestScore = -1;
		List<NametagInfo> candidates = new ArrayList<>(attached);
		if (entity.hasCustomName()) {
			candidates.add(NametagParser.parse(entity.getCustomName().getString()));
		}
		for (NametagInfo tag : candidates) {
			int score = (tag.hasHealth() ? 4 : 0) + (tag.hasLevel() ? 2 : 0) + (tag.name().isEmpty() ? 0 : 1);
			if (score > bestScore) {
				bestScore = score;
				best = tag;
			}
		}

		String name = best.name().isEmpty() ? entity.getName().getString() : best.name();
		double health = best.health();
		double maxHealth = best.maxHealth();
		if (health < 0 && entity instanceof LivingEntity living) {
			health = living.getHealth();
			maxHealth = living.getMaxHealth();
		}
		return new EntityInfo(entity, EntityType.getKey(entity.getType()).toString(), name,
			best.level(), health, maxHealth, entity.position(), best.raw());
	}
}
