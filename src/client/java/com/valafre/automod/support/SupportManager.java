package com.valafre.automod.support;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.PlayerState;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.List;
import java.util.Locale;

/**
 * Survie intégrée au combat : Wand de soin sous un seuil de PV, Orb maintenue pendant le boss.
 * Tout se fait au niveau du tick par simple utilisation d'objet : le suivi, le positionnement et l'attaque continuent
 * sans interruption (aucune tâche, aucun arrêt de mouvement). Le soin passe avant l'Orb.
 */
public final class SupportManager {

	private static final int ORB_SCAN_INTERVAL_TICKS = 10;
	private static final int ORB_MISSING_RETRY_TICKS = 40;

	private int wandCooldown;
	private int orbTicks = -1;          // ticks depuis la dernière pose ; -1 = pas posée pour ce combat
	private int orbRetry;
	private int orbScanTimer;
	private boolean orbSeen;
	private String status = "-";

	// ========================================
	// SURVIE
	// ========================================

	/**
	 * @param bossFight vrai uniquement quand le boss est la cible active : l'Orb n'est jamais utilisée en dehors.
	 */
	public void tick(Framework f, boolean bossFight) {
		ModConfig cfg = ModConfig.get();
		PlayerState ps = f.player();
		ItemUseController items = f.items();
		if (wandCooldown > 0) {
			wandCooldown--;
		}
		if (orbRetry > 0) {
			orbRetry--;
		}

		float max = ps.player().getMaxHealth();
		float fraction = max > 0 ? ps.player().getHealth() / max : 1.0f;

		// 1. Soin : priorité, dès que les PV passent sous le seuil (et à chaque fin de cooldown tant qu'ils y restent).
		if (cfg.healEnabled && fraction * 100.0f < cfg.healThresholdPercent && wandCooldown == 0) {
			int slot = items.findHotbarSlot(ps, cfg.healWandKeyword);
			if (slot >= 0 && items.use(ps, slot)) {
				wandCooldown = cfg.wandCooldownTicks;
				Debug.log("Support", () -> "Wand utilisée (PV " + Math.round(fraction * 100) + "%)");
			}
		}

		// 2. Orb : seulement pendant le combat de boss.
		if (!bossFight) {
			orbTicks = -1;
			orbSeen = false;
		} else if (cfg.orbEnabled) {
			maintainOrb(f, cfg, ps, items);
		}

		status = "PV " + Math.round(fraction * 100) + "% (seuil " + Math.round(cfg.healThresholdPercent) + "%)"
			+ " | Wand: " + slotText(items.findHotbarSlot(ps, cfg.healWandKeyword))
			+ " | Orb: " + (!bossFight ? "inactive (hors boss)" : orbTicks < 0 ? "à poser"
			: "posée il y a " + orbTicks / 20 + " s" + (orbSeen ? " (détectée)" : " (non détectée)"));
	}

	private void maintainOrb(Framework f, ModConfig cfg, PlayerState ps, ItemUseController items) {
		if (orbTicks >= 0) {
			orbTicks++;
		}
		refreshOrbDetection(f, cfg, ps);

		boolean neverPlaced = orbTicks < 0;
		// Disparue : plus détectée après un délai minimal, ou durée théorique écoulée (repli si la détection ne marche pas).
		boolean gone = orbTicks >= cfg.orbMinReplaceTicks && !orbSeen;
		boolean expired = orbTicks >= cfg.orbAssumedDurationTicks;
		if ((neverPlaced || gone || expired) && orbRetry == 0) {
			int slot = items.findHotbarSlot(ps, cfg.orbKeyword);
			if (slot >= 0 && items.use(ps, slot)) {
				orbTicks = 0;
				orbSeen = false;
				Debug.log("Support", () -> neverPlaced ? "Orb posée (début du boss)" : "Orb replacée");
			} else if (slot < 0) {
				orbRetry = ORB_MISSING_RETRY_TICKS; // pas d'Orb dans la hotbar : on réessaie plus tard sans spammer
			}
		}
	}

	/** Cherche un ArmorStand de l'Orb près du joueur (texte configurable), de temps en temps seulement. */
	private void refreshOrbDetection(Framework f, ModConfig cfg, PlayerState ps) {
		if (orbScanTimer-- > 0) {
			return;
		}
		orbScanTimer = ORB_SCAN_INTERVAL_TICKS;
		List<ArmorStand> stands = f.entityDetector().find(ps, ArmorStand.class, cfg.orbSearchRadius, ArmorStand::hasCustomName);
		orbSeen = false;
		for (ArmorStand stand : stands) {
			String name = stand.getCustomName().getString().toLowerCase(Locale.ROOT);
			for (String keyword : cfg.orbStandKeywords.split(",")) {
				if (!keyword.isBlank() && name.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
					orbSeen = true;
					return;
				}
			}
		}
	}

	public void reset() {
		wandCooldown = 0;
		orbTicks = -1;
		orbRetry = 0;
		orbSeen = false;
	}

	public String status() {
		return status;
	}

	private static String slotText(int slot) {
		return slot < 0 ? "absente" : "slot " + (slot + 1);
	}
}
