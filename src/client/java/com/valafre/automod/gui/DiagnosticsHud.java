package com.valafre.automod.gui;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Debug;
import com.valafre.automod.core.Framework;
import com.valafre.automod.gui.hud.HudRegistry;
import com.valafre.automod.gui.hud.HudSection;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD en jeu : un panneau semi-transparent qui empile les sections enregistrées (Voidgloom, Survie, Système...).
 * Mode normal : une ligne par section ; mode debug : détails complets.
 */
public final class DiagnosticsHud {

	private static final int LINE_H = 10;
	private static final int PADDING = 4;
	private static final int SECTION_GAP = 3;

	private DiagnosticsHud() {}

	public static void register(Framework framework) {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("automod", "diagnostics"),
			(HudElement) (graphics, delta) -> render(framework, graphics));
	}

	private record Block(HudSection section, List<String> lines) {}

	private static void render(Framework f, GuiGraphicsExtractor graphics) {
		if (!ModConfig.get().hudEnabled) {
			return;
		}
		boolean detailed = Debug.enabled();
		List<Block> blocks = new ArrayList<>();
		for (HudSection section : HudRegistry.all()) {
			if (section.isVisible(f)) {
				blocks.add(new Block(section, section.lines(f, detailed)));
			}
		}
		if (blocks.isEmpty()) {
			return;
		}

		Minecraft mc = f.minecraft();
		Font font = mc.font;
		int width = 0;
		int height = PADDING * 2 - SECTION_GAP;
		for (Block block : blocks) {
			width = Math.max(width, font.width(block.section().title()));
			for (String line : block.lines()) {
				width = Math.max(width, font.width(line) + 6);
			}
			height += LINE_H * (1 + block.lines().size()) + SECTION_GAP;
		}
		int x = 4;
		int y = mc.getWindow().getGuiScaledHeight() / 2 - height / 2;
		graphics.fill(x, y, x + width + PADDING * 2, y + height, 0x99000000);

		int cy = y + PADDING;
		for (Block block : blocks) {
			graphics.fill(x, cy, x + 2, cy + LINE_H * (1 + block.lines().size()), block.section().color());
			graphics.text(font, block.section().title(), x + PADDING, cy, block.section().color());
			cy += LINE_H;
			for (String line : block.lines()) {
				graphics.text(font, line, x + PADDING + 6, cy, 0xFFE0E0E0);
				cy += LINE_H;
			}
			cy += SECTION_GAP;
		}
	}
}
