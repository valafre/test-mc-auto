package com.valafre.automod.combat;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.movement.RotationController;
import com.valafre.automod.targeting.TargetInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/**
 * Seul point d'attaque du framework. Valide cible, portée, orientation et cooldown avant de frapper
 * via {@code MultiPlayerGameMode.attack} (API 26.1.2) suivie du swing de bras.
 */
public final class CombatController {

	private final Minecraft mc;
	private final RotationController rotation;
	private int ticksSinceAttack = Integer.MAX_VALUE / 2;

	public CombatController(Minecraft mc, RotationController rotation) {
		this.mc = mc;
		this.rotation = rotation;
	}

	/** À appeler une fois par tick pour faire avancer le cooldown. */
	public void tick() {
		if (ticksSinceAttack < Integer.MAX_VALUE / 2) {
			ticksSinceAttack++;
		}
	}

	public void reset() {
		ticksSinceAttack = Integer.MAX_VALUE / 2;
	}

	// ========================================
	// COMBAT
	// ========================================

	public boolean isInRange(PlayerState state, LivingEntity target) {
		return TargetInfo.of(state, target).distance() <= ModConfig.get().attackDistance;
	}

	public boolean isAligned(PlayerState state, LivingEntity target) {
		return rotation.isAligned(state, TargetInfo.of(state, target).aimPoint());
	}

	/** @return true si une attaque a effectivement été envoyée ce tick. */
	public boolean tryAttack(PlayerState state, LivingEntity target) {
		ModConfig cfg = ModConfig.get();
		if (mc.gameMode == null || state.player() == null || !state.player().isAlive()) {
			return false;
		}
		if (target == null || !target.isAlive() || target.isRemoved()) {
			return false;
		}
		if (ticksSinceAttack < cfg.attackCooldownTicks
			|| state.player().getAttackStrengthScale(0.0f) < cfg.minAttackStrength) {
			return false;
		}
		TargetInfo info = TargetInfo.of(state, target);
		if (info.distance() > cfg.attackDistance || !rotation.isAligned(state, info.aimPoint())) {
			return false; // hors portée ou mal orienté : la rotation continue, on n'attaque pas
		}
		mc.gameMode.attack(state.player(), target);
		state.player().swing(InteractionHand.MAIN_HAND);
		ticksSinceAttack = 0;
		Debug.log("Combat", () -> "Attaque (distance=" + String.format("%.2f", info.distance()) + ")");
		return true;
	}
}
