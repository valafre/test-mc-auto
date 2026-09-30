package com.valafre.automod.combat;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.PlayerState;
import com.valafre.automod.targeting.TargetInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Seul point d'attaque du framework. Valide cible, portée, orientation et cooldown avant de frapper
 * via {@code MultiPlayerGameMode.attack} (API 26.1.2) suivie du swing de bras.
 */
public final class CombatController {

	private final Minecraft mc;
	private final java.util.Random rng = new java.util.Random();
	private int nextInterval = 1;   // ticks à attendre avant le prochain coup
	private double tickDebt;        // reste fractionnaire reporté : garantit le CPS moyen visé
	private int ticksSinceAttack = Integer.MAX_VALUE / 2;

	public CombatController(Minecraft mc) {
		this.mc = mc;
	}

	/** À appeler une fois par tick pour faire avancer le cooldown. */
	public void tick() {
		if (ticksSinceAttack < Integer.MAX_VALUE / 2) {
			ticksSinceAttack++;
		}
	}

	public void reset() {
		ticksSinceAttack = Integer.MAX_VALUE / 2;
		nextInterval = 1;
		tickDebt = 0;
	}

	// ========================================
	// COMBAT
	// ========================================

	/** Tire un CPS entre min et max ; 20/CPS ticks séparent les coups, la partie fractionnaire est reportée au coup suivant. */
	private void scheduleNextAttack(ModConfig cfg) {
		double min = Math.max(1.0, Math.min(cfg.minCps, cfg.maxCps));
		double max = Math.min(20.0, Math.max(cfg.minCps, cfg.maxCps));
		double cps = min + rng.nextDouble() * (max - min);
		tickDebt += 20.0 / cps;
		int interval = (int) tickDebt;
		tickDebt -= interval;
		nextInterval = Math.max(1, interval);
	}

	/**
	 * Ligne de vue oeil -> cible : aucun bloc solide entre les deux (test du centre puis de la tête).
	 * Empêche de croire "à portée" une cible qui n'est qu'à 2 blocs MAIS derrière un mur.
	 */
	public boolean hasLineOfSight(PlayerState state, Entity target) {
		Vec3 eye = state.eyePosition();
		AABB box = target.getBoundingBox();
		Vec3 center = box.getCenter();
		return isVisible(state, eye, center)
			|| isVisible(state, eye, new Vec3(center.x, box.minY + box.getYsize() * 0.9, center.z));
	}

	private static boolean isVisible(PlayerState state, Vec3 eye, Vec3 to) {
		ClipContext context = new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, state.player());
		return state.level().clip(context).getType() == HitResult.Type.MISS;
	}

	/** Vrai si le rayon du regard (portée d'attaque) traverse la hitbox de la cible : le viseur est réellement dessus. */
	public boolean isCrosshairOnTarget(PlayerState state, LivingEntity target) {
		Vec3 eye = state.eyePosition();
		Vec3 end = eye.add(state.player().getViewVector(1.0f).scale(ModConfig.get().attackDistance));
		AABB box = target.getBoundingBox().inflate(target.getPickRadius());
		return box.contains(eye) || box.clip(eye, end).isPresent();
	}

	public boolean isInRange(PlayerState state, LivingEntity target) {
		return TargetInfo.of(state, target).distance() <= ModConfig.get().attackDistance;
	}

	public boolean isAligned(PlayerState state, LivingEntity target) {
		return isCrosshairOnTarget(state, target);
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
		if (ticksSinceAttack < nextInterval
			|| state.player().getAttackStrengthScale(0.0f) < cfg.minAttackStrength) {
			return false;
		}
		TargetInfo info = TargetInfo.of(state, target);
		if (info.distance() > cfg.attackDistance || !hasLineOfSight(state, target) || !isCrosshairOnTarget(state, target)) {
			return false; // hors portée ou viseur pas encore dans la hitbox : la rotation continue, on n'attaque pas
		}
		mc.gameMode.attack(state.player(), target);
		state.player().swing(InteractionHand.MAIN_HAND);
		ticksSinceAttack = 0;
		scheduleNextAttack(cfg);
		Debug.log("Combat", () -> "Attaque (distance=" + String.format("%.2f", info.distance()) + ")");
		return true;
	}
}
