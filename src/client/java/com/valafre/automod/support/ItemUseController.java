package com.valafre.automod.support;

import com.valafre.automod.core.PlayerState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * Utilisation d'objets de la hotbar (clic droit) SANS perturber le reste : sélectionne le slot, utilise l'objet, puis
 * rend le slot d'origine au tick suivant. Une seule utilisation par tick. Pendant le tick d'utilisation, {@link #isBusy()}
 * est vrai pour que le combat n'attaque pas avec l'objet utilitaire en main. Les déplacements et la rotation ne sont pas touchés.
 */
public final class ItemUseController {

	private final Minecraft mc;
	private int restoreSlot = -1;
	private int usedSlot = -1;
	private boolean busy;

	public ItemUseController(Minecraft mc) {
		this.mc = mc;
	}

	/** À appeler en début de tick, avant les modules : rend le slot d'origine si on l'avait changé au tick précédent. */
	public void beginTick(PlayerState state) {
		busy = false;
		if (restoreSlot >= 0) {
			Inventory inventory = state.player().getInventory();
			if (inventory.getSelectedSlot() == usedSlot) { // si le joueur a changé de slot lui-même, on n'y touche pas
				inventory.setSelectedSlot(restoreSlot);
			}
			restoreSlot = -1;
			usedSlot = -1;
		}
	}

	/** @return true si l'objet du slot hotbar a été utilisé ce tick. */
	public boolean use(PlayerState state, int hotbarSlot) {
		if (busy || hotbarSlot < 0 || hotbarSlot > 8 || mc.gameMode == null) {
			return false;
		}
		Inventory inventory = state.player().getInventory();
		int original = inventory.getSelectedSlot();
		inventory.setSelectedSlot(hotbarSlot);
		mc.gameMode.useItem(state.player(), InteractionHand.MAIN_HAND);
		busy = true;
		if (original != hotbarSlot) {
			restoreSlot = original;
			usedSlot = hotbarSlot;
		}
		return true;
	}

	private int equipTimer;

	/**
	 * Garde l'arme (premier objet de la hotbar dont le nom contient {@code keyword}) en main. Vérifié toutes les 5 ticks
	 * seulement ; ne fait rien pendant une utilisation de Wand/Orb ni juste après (le slot d'origine est alors rendu).
	 */
	public void equip(PlayerState state, String keyword) {
		if (keyword == null || keyword.isBlank() || busy || restoreSlot >= 0 || equipTimer-- > 0) {
			return;
		}
		equipTimer = 4;
		int slot = findHotbarSlot(state, keyword);
		Inventory inventory = state.player().getInventory();
		if (slot >= 0 && inventory.getSelectedSlot() != slot) {
			inventory.setSelectedSlot(slot);
		}
	}

	/** Vrai pendant le tick où un objet utilitaire est en main : le combat ne doit pas attaquer ce tick-là. */
	public boolean isBusy() {
		return busy;
	}

	public void reset() {
		restoreSlot = -1;
		usedSlot = -1;
		busy = false;
	}

	/** Premier slot de la hotbar dont le nom contient {@code keyword} (insensible à la casse : couvre tous les tiers), sinon -1. */
	public int findHotbarSlot(PlayerState state, String keyword) {
		String needle = keyword.toLowerCase(Locale.ROOT);
		Inventory inventory = state.player().getInventory();
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)) {
				return slot;
			}
		}
		return -1;
	}
}
