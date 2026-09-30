package com.valafre.automod.modules.slayer.voidgloom;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.targeting.EntityDetector;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.EnderMan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Identification des Voidgloom parmi les Enderman proches (spécifique au module : c'est ici que vit la règle "nom"). */
public final class VoidgloomTarget {

	private VoidgloomTarget() {}

	// ========================================
	// TARGETING
	// ========================================

	/** Enderman vivants à portée dont le nom personnalisé contient le mot-clé (insensible à la casse et au formatage). */
	public static List<EnderMan> find(Framework f) {
		ModConfig cfg = ModConfig.get();
		List<EnderMan> endermen = f.entityDetector().find(f.player(), EnderMan.class, cfg.targetSearchRange, e -> true);
		if (endermen.isEmpty()) {
			return endermen;
		}
		String keyword = cfg.voidgloomNameKeyword.toLowerCase(Locale.ROOT);
		List<ArmorStand> nameplates = null;
		List<EnderMan> result = new ArrayList<>();

		for (EnderMan enderman : endermen) {
			if (EntityDetector.cleanCustomName(enderman).contains(keyword)) {
				result.add(enderman);
				continue;
			}
			if (!cfg.allowArmorStandNameplate) {
				continue;
			}
			// Repli : le nom est porté par un ArmorStand flottant au-dessus de l'Enderman (chargé seulement si nécessaire).
			if (nameplates == null) {
				nameplates = f.entityDetector().find(f.player(), ArmorStand.class, cfg.targetSearchRange + 4.0,
					s -> EntityDetector.cleanCustomName(s).contains(keyword));
			}
			for (ArmorStand stand : nameplates) {
				double dx = stand.getX() - enderman.getX();
				double dz = stand.getZ() - enderman.getZ();
				double dy = stand.getY() - enderman.getY();
				if (dx * dx + dz * dz < 1.5 * 1.5 && dy > -0.5 && dy < 4.0) {
					result.add(enderman);
					break;
				}
			}
		}
		return result;
	}
}
