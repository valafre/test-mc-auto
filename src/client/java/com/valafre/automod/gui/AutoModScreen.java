package com.valafre.automod.gui;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.gui.settings.SettingsCategory;
import com.valafre.automod.gui.settings.SettingsPage;
import com.valafre.automod.gui.settings.SettingsRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Menu de réglage (INSERT) : colonne de catégories à gauche, réglages de la catégorie choisie à droite.
 * Les catégories viennent du {@link SettingsRegistry} : ajouter un module n'oblige pas à modifier cet écran.
 * La config est sauvegardée à la fermeture.
 */
public final class AutoModScreen extends Screen {

	private static final int SIDEBAR_W = 96;
	private static final int GAP = 6;
	private static final int BUTTON_H = 20;
	private static final int TOP_BAR = 14;

	/** Catégorie ouverte (conservée d'une ouverture à l'autre). */
	private static int selected;

	private final Framework framework;
	private SettingsPage page;

	public AutoModScreen(Framework framework) {
		super(Component.literal("AutoMod"));
		this.framework = framework;
	}

	@Override
	protected void init() {
		List<SettingsCategory> categories = SettingsRegistry.all();
		if (selected >= categories.size()) {
			selected = 0;
		}
		int total = Math.min(width - 16, 380);
		int x0 = (width - total) / 2;
		int panelHeight = TOP_BAR + SettingsPage.MAX_ROWS * SettingsPage.ROW + GAP + BUTTON_H;
		int y0 = Math.max(6, (height - panelHeight) / 2);

		// Colonne des catégories
		for (int i = 0; i < categories.size(); i++) {
			int index = i;
			Button button = Button.builder(
				Component.literal((i == selected ? "» " : "") + categories.get(i).title()),
				b -> {
					selected = index;
					rebuildWidgets();
				}).bounds(x0, y0 + TOP_BAR + i * SettingsPage.ROW, SIDEBAR_W, BUTTON_H).build();
			button.active = i != selected;
			addRenderableWidget(button);
		}

		// Réglages de la catégorie choisie
		page = new SettingsPage(b -> addRenderableWidget(b), x0 + SIDEBAR_W + GAP, y0 + TOP_BAR, total - SIDEBAR_W - GAP);
		if (!categories.isEmpty()) {
			categories.get(selected).build(page, framework);
		}

		addRenderableWidget(Button.builder(Component.literal("Terminé"), b -> onClose())
			.bounds(x0, y0 + TOP_BAR + SettingsPage.MAX_ROWS * SettingsPage.ROW + GAP, total, BUTTON_H).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int total = Math.min(width - 16, 380);
		int x0 = (width - total) / 2;
		int panelHeight = TOP_BAR + SettingsPage.MAX_ROWS * SettingsPage.ROW + GAP + BUTTON_H;
		int y0 = Math.max(6, (height - panelHeight) / 2);
		graphics.text(font, "AutoMod", x0, y0 + 2, 0xFFFFFFFF);
		if (page != null) {
			for (SettingsPage.Label label : page.labels()) {
				graphics.text(font, label.text(), label.x(), label.y(), label.color());
			}
		}
		// Place réservée aux prochains modules : ils apparaissent ici dès qu'ils enregistrent une catégorie.
		int below = y0 + TOP_BAR + SettingsRegistry.all().size() * SettingsPage.ROW + 2;
		graphics.text(font, "+ prochains modules", x0 + 2, below, 0xFF666666);
	}

	@Override
	public void onClose() {
		ModConfig.save();
		super.onClose();
	}
}
