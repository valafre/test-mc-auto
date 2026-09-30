package com.valafre.automod.modules.slayer.voidgloom;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.targeting.EntityInfo;
import net.minecraft.world.entity.monster.EnderMan;

import java.util.ArrayList;
import java.util.List;

/** Identification des Voidgloom parmi les Enderman proches (la règle "Voidgloom" vit ici, l'analyse des nametags est générique). */
public final class VoidgloomTarget {

	private VoidgloomTarget() {}

	// ========================================
	// TARGETING
	// ========================================

	/**
	 * Enderman vivants à portée tels que décrits par leur nametag : type Enderman ET nom (sans niveau ni vie) contenant
	 * le mot-clé ET, si configuré, niveau égal à {@code voidgloomRequiredLevel}. Les Enderman normaux
	 * ("[Lv50] Enderman 9,000/9,000") sont ignorés.
	 */
	public static List<EnderMan> find(Framework f) {
		ModConfig cfg = ModConfig.get();
		List<EntityInfo> infos = f.entityInfo().scan(f.player(), EnderMan.class, cfg.targetSearchRange, f.entityDetector());
		List<EnderMan> result = new ArrayList<>();
		for (EntityInfo info : infos) {
			if (!info.nameContains(cfg.voidgloomNameKeyword)) {
				continue;
			}
			if (cfg.voidgloomRequiredLevel > 0 && info.level() != cfg.voidgloomRequiredLevel) {
				continue;
			}
			result.add(EnderMan.class.cast(info.entity()));
		}
		return result;
	}
}
