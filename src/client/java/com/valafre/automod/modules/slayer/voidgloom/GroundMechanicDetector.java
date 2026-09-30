package com.valafre.automod.modules.slayer.voidgloom;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.PlayerState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Détecte la mécanique au sol sous forme de BLOC (par défaut {@code minecraft:beacon}, configurable).
 *
 * <p>Hypothèse non vérifiable depuis Minecraft/Fabric seuls : la nature exacte (bloc, entité, particule) de la mécanique
 * dépend du serveur. Le scan est limité à un volume autour du joueur et espacé dans le temps ; tant qu'une mécanique
 * est suivie, seule la présence de son bloc est revérifiée (1 lecture par tick).
 */
public final class GroundMechanicDetector {

	private Block block = Blocks.BEACON;
	private String resolvedId = "minecraft:beacon";
	private int ticksSinceScan = Integer.MAX_VALUE / 2;
	private BlockPos active;

	// ========================================
	// GROUND MECHANIC
	// ========================================

	/** @return la position de la mécanique présente (la plus proche du joueur), ou null s'il n'y en a pas. */
	public BlockPos poll(PlayerState state) {
		ModConfig cfg = ModConfig.get();
		resolveBlock(cfg.mechanicBlockId);
		if (active != null) {
			if (isPresent(state, active)) {
				return active;
			}
			active = null;
		}
		if (++ticksSinceScan < cfg.mechanicScanIntervalTicks) {
			return null;
		}
		ticksSinceScan = 0;
		active = scan(state, cfg);
		return active;
	}

	public boolean isPresent(PlayerState state, BlockPos pos) {
		return pos != null && state.level().getBlockState(pos).is(block);
	}

	public void reset() {
		active = null;
		ticksSinceScan = Integer.MAX_VALUE / 2;
	}

	private BlockPos scan(PlayerState state, ModConfig cfg) {
		BlockPos center = state.player().blockPosition();
		int r = cfg.mechanicScanRadius;
		int h = cfg.mechanicScanHalfHeight;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -h; dy <= h; dy++) {
					cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (state.level().getBlockState(cursor).is(block)) {
						double d = dx * dx + dy * dy + dz * dz;
						if (d < bestDist) {
							bestDist = d;
							best = cursor.immutable();
						}
					}
				}
			}
		}
		return best;
	}

	private void resolveBlock(String id) {
		if (id.equals(resolvedId)) {
			return;
		}
		Identifier identifier = Identifier.tryParse(id);
		if (identifier != null) {
			BuiltInRegistries.BLOCK.getOptional(identifier).ifPresent(b -> block = b);
		}
		resolvedId = id;
	}
}
