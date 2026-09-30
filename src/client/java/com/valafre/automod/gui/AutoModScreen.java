package com.valafre.automod.gui;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.modules.slayer.voidgloom.VoidgloomModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** Menu de réglage (ouvert avec INSERT) : activation des modules et paramètres principaux. Sauvegarde à la fermeture. */
public final class AutoModScreen extends Screen {

	private static final int BUTTON_H = 20;
	private static final int ROW = 24;
	private static final int WIDTH = 240;

	private final Framework framework;

	public AutoModScreen(Framework framework) {
		super(Component.literal("AutoMod"));
		this.framework = framework;
	}

	@Override
	protected void init() {
		ModConfig cfg = ModConfig.get();
		int x = (width - WIDTH) / 2;
		int y = height / 2 - 120;

		// Modules
		toggle(x, y, "Module Voidgloom", () -> isVoidgloomOn(), () -> framework.modules().toggle(framework, VoidgloomModule.ID));
		y += ROW;

		// Options booléennes
		toggle(x, y, "Debug (logs)", () -> cfg.debugMode, () -> cfg.debugMode = !cfg.debugMode);
		y += ROW;
		toggle(x, y, "Sprint", () -> cfg.useSprint, () -> cfg.useSprint = !cfg.useSprint);
		y += ROW;
		toggle(x, y, "Nom via ArmorStand", () -> cfg.allowArmorStandNameplate,
			() -> cfg.allowArmorStandNameplate = !cfg.allowArmorStandNameplate);
		y += ROW;
		toggle(x, y, "Position au-dessus", () -> cfg.allowAbovePosition, () -> cfg.allowAbovePosition = !cfg.allowAbovePosition);
		y += ROW + 6;

		// Valeurs numériques
		stepper(x, y, "Portée d'attaque", () -> cfg.attackDistance,
			v -> cfg.attackDistance = Mth.clamp(v, 2.0, 6.0), 0.25);
		y += ROW;
		stepper(x, y, "Distance d'approche", () -> cfg.approachDistance,
			v -> cfg.approachDistance = Mth.clamp(v, 1.0, cfg.attackDistance - 0.1), 0.25);
		y += ROW;
		stepper(x, y, "Rayon de recherche", () -> cfg.targetSearchRange,
			v -> cfg.targetSearchRange = Mth.clamp(v, 4.0, 48.0), 2.0);
		y += ROW;
		stepper(x, y, "Cooldown attaque (ticks)", () -> cfg.attackCooldownTicks,
			v -> cfg.attackCooldownTicks = (int) Mth.clamp(v, 1, 40), 1.0);
		y += ROW + 6;

		addRenderableWidget(Button.builder(Component.literal("Terminé"), b -> onClose())
			.bounds(x, y, WIDTH, BUTTON_H).build());
	}

	private boolean isVoidgloomOn() {
		return framework.modules().isEnabled(VoidgloomModule.ID);
	}

	private void toggle(int x, int y, String label, BooleanSupplier state, Runnable action) {
		Button real = Button.builder(Component.empty(), b -> {
			action.run();
			b.setMessage(Component.literal(label + " : " + (state.getAsBoolean() ? "ON" : "OFF")));
		}).bounds(x, y, WIDTH, BUTTON_H).build();
		real.setMessage(Component.literal(label + " : " + (state.getAsBoolean() ? "ON" : "OFF")));
		addRenderableWidget(real);
	}

	private void stepper(int x, int y, String label, DoubleSupplier get, DoubleConsumer set, double step) {
		int small = 24;
		Button middle = Button.builder(Component.empty(), b -> { })
			.bounds(x + small + 2, y, WIDTH - 2 * small - 4, BUTTON_H).build();
		middle.active = false;
		middle.setMessage(text(label, get.getAsDouble()));
		addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			set.accept(get.getAsDouble() - step);
			middle.setMessage(text(label, get.getAsDouble()));
		}).bounds(x, y, small, BUTTON_H).build());
		addRenderableWidget(middle);
		addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			set.accept(get.getAsDouble() + step);
			middle.setMessage(text(label, get.getAsDouble()));
		}).bounds(x + WIDTH - small, y, small, BUTTON_H).build());
	}

	private static Component text(String label, double value) {
		return Component.literal(label + " : " + (value == Math.floor(value) ? String.valueOf((int) value) : String.format("%.2f", value)));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.text(font, title, (width - font.width(title)) / 2, height / 2 - 140, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		ModConfig.save();
		super.onClose();
	}
}
