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
	/** @param endermen nombre d'Enderman vus à portée (tous), @param bosses ceux retenus comme Voidgloom. */
	public record Result(List<EnderMan> bosses, List<EnderMan> mobs, int endermen) {}

	public static Result find(Framework f) {
		ModConfig cfg = ModConfig.get();
		double range = cfg.farmMobs ? Math.max(cfg.targetSearchRange, cfg.farmSearchRange) : cfg.targetSearchRange;
		List<EntityInfo> infos = f.entityInfo().scan(f.player(), EnderMan.class, range, f.entityDetector());
		List<EnderMan> result = new ArrayList<>();
		List<EnderMan> mobs = new ArrayList<>();
		for (EntityInfo info : infos) {
			if (!info.nameContains(cfg.voidgloomNameKeyword)) {
				// Enderman normal ("[Lv50] Enderman") : cible de farm pour faire apparaître le boss.
				if (cfg.farmMobs && info.nameContains(cfg.farmMobKeyword)) {
					mobs.add(EnderMan.class.cast(info.entity()));
				}
				continue;
			}
			if (cfg.voidgloomRequiredLevel > 0 && info.level() != cfg.voidgloomRequiredLevel) {
				continue;
			}
			result.add(EnderMan.class.cast(info.entity()));
		}
		return new Result(result, mobs, infos.size());
	}
}
