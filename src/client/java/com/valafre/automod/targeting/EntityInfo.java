package com.valafre.automod.targeting;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Description générique d'une entité telle que le joueur la voit : type réel + informations extraites du nametag.
 * {@code level}, {@code health} et {@code maxHealth} valent -1 quand ils sont inconnus.
 */
public record EntityInfo(Entity entity, String typeId, String name, int level,
						 double health, double maxHealth, Vec3 position, String rawNametag) {

	public boolean hasLevel() {
		return level >= 0;
	}

	public boolean hasHealth() {
		return health >= 0;
	}

	/** Le nom (sans niveau ni vie) contient-il {@code text} ? Insensible à la casse. */
	public boolean nameContains(String text) {
		return name.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
	}

	@Override
	public String toString() {
		return "type=" + typeId + " name=\"" + name + "\" level=" + (hasLevel() ? level : "?")
			+ " health=" + (hasHealth() ? (long) health + "/" + (long) maxHealth : "?")
			+ " pos=" + entity.blockPosition().toShortString();
	}
}
