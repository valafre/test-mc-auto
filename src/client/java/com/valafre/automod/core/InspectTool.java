package com.valafre.automod.core;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Outil d'observation : écrit dans le chat ET copie dans le presse-papiers l'entité ou le bloc visé.
 * Sert notamment à identifier la mécanique au sol du Voidgloom (id de bloc / type d'entité à mettre dans la config).
 */
public final class InspectTool {

	private InspectTool() {}

	public static void inspect(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		String text = describe(mc);
		if (text == null) {
			mc.player.sendSystemMessage(Component.literal("[AutoMod] Rien n'est visé."));
			return;
		}
		mc.keyboardHandler.setClipboard(text);
		mc.player.sendSystemMessage(Component.literal("[AutoMod] Copié : " + text));
	}

	private static String describe(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (hit instanceof EntityHitResult entityHit) {
			Entity e = entityHit.getEntity();
			String name = e.hasCustomName() ? e.getCustomName().getString() : e.getName().getString();
			return "Entity type=" + EntityType.getKey(e.getType()) + " name=\"" + name + "\""
				+ " pos=" + e.blockPosition().toShortString();
		}
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = blockHit.getBlockPos();
			BlockState state = mc.level.getBlockState(pos);
			return "Block id=" + BuiltInRegistries.BLOCK.getKey(state.getBlock())
				+ " state=" + state + " pos=" + pos.toShortString();
		}
		return null;
	}
}
