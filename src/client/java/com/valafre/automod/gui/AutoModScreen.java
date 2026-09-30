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
	private static final int ROW = 22;

	private final Framework framework;

	public AutoModScreen(Framework framework) {
		super(Component.literal("AutoMod"));
		this.framework = framework;
	}

	@Override
	protected void init() {
		ModConfig cfg = ModConfig.get();
		int colW = 150;
		int gap = 6;
		int left = (width - (2 * colW + gap)) / 2;
		int right = left + colW + gap;
		int top = height / 2 - 70;

		// Colonne gauche : activation et options ON/OFF
		int y = top;
		toggle(left, y, colW, "Voidgloom", () -> isVoidgloomOn(), () -> framework.modules().toggle(framework, VoidgloomModule.ID));
		y += ROW;
		toggle(left, y, colW, "Exiger Slayer", () -> cfg.requireSlayer, () -> cfg.requireSlayer = !cfg.requireSlayer);
		y += ROW;
		toggle(left, y, colW, "Farmer Enderman", () -> cfg.farmMobs, () -> cfg.farmMobs = !cfg.farmMobs);
		y += ROW;
		toggle(left, y, colW, "Humaniser", () -> cfg.humanize, () -> cfg.humanize = !cfg.humanize);
		y += ROW;
		toggle(left, y, colW, "Debug (logs)", () -> cfg.debugMode, () -> cfg.debugMode = !cfg.debugMode);
		y += ROW;
		toggle(left, y, colW, "Sprint", () -> cfg.useSprint, () -> cfg.useSprint = !cfg.useSprint);
		y += ROW;
		toggle(left, y, colW, "Nom ArmorStand", () -> cfg.allowArmorStandNameplate,
			() -> cfg.allowArmorStandNameplate = !cfg.allowArmorStandNameplate);

		// Colonne droite : position + valeurs numériques
		y = top;
		toggle(right, y, colW, "Pos. au-dessus", () -> cfg.allowAbovePosition, () -> cfg.allowAbovePosition = !cfg.allowAbovePosition);
		y += ROW;
		toggle(right, y, colW, "Seulement mon boss", () -> cfg.onlyOwnBoss, () -> cfg.onlyOwnBoss = !cfg.onlyOwnBoss);
		y += ROW;
		stepper(right, y, colW, "Portée", () -> cfg.attackDistance, v -> cfg.attackDistance = Mth.clamp(v, 2.0, 6.0), 0.25);
		y += ROW;
		stepper(right, y, colW, "Approche", () -> cfg.approachDistance,
			v -> cfg.approachDistance = Mth.clamp(v, 1.0, cfg.attackDistance - 0.1), 0.25);
		y += ROW;
		stepper(right, y, colW, "Recherche", () -> cfg.targetSearchRange, v -> cfg.targetSearchRange = Mth.clamp(v, 4.0, 48.0), 2.0);
		y += ROW;
		stepper(right, y, colW, "Cooldown", () -> cfg.attackCooldownTicks,
			v -> cfg.attackCooldownTicks = (int) Mth.clamp(v, 1, 40), 1.0);
		y += ROW;
		stepper(right, y, colW, "Niveau (0=off)", () -> cfg.voidgloomRequiredLevel,
			v -> cfg.voidgloomRequiredLevel = (int) Mth.clamp(v, 0, 500), 1.0);

		addRenderableWidget(Button.builder(Component.literal("Terminé"), b -> onClose())
			.bounds(left, top + 7 * ROW + 6, 2 * colW + gap, BUTTON_H).build());
	}

	private boolean isVoidgloomOn() {
		return framework.modules().isEnabled(VoidgloomModule.ID);
	}

	private void toggle(int x, int y, int w, String label, BooleanSupplier state, Runnable action) {
		Button real = Button.builder(Component.empty(), b -> {
			action.run();
			b.setMessage(Component.literal(label + " : " + (state.getAsBoolean() ? "ON" : "OFF")));
		}).bounds(x, y, w, BUTTON_H).build();
		real.setMessage(Component.literal(label + " : " + (state.getAsBoolean() ? "ON" : "OFF")));
		addRenderableWidget(real);
	}

	private void stepper(int x, int y, int w, String label, DoubleSupplier get, DoubleConsumer set, double step) {
		int small = 24;
		Button middle = Button.builder(Component.empty(), b -> { })
			.bounds(x + small + 2, y, w - 2 * small - 4, BUTTON_H).build();
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
		}).bounds(x + w - small, y, small, BUTTON_H).build());
	}

	private static Component text(String label, double value) {
		return Component.literal(label + " : " + (value == Math.floor(value) ? String.valueOf((int) value) : String.format("%.2f", value)));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.text(font, title, (width - font.width(title)) / 2, height / 2 - 100, 0xFFFFFFFF);
		Component status = Component.literal("Voidgloom : " + framework.modules().status(VoidgloomModule.ID));
		graphics.text(font, status, (width - font.width(status)) / 2, height / 2 - 86, 0xFFAAAAAA);
	}

	@Override
	public void onClose() {
		ModConfig.save();
		super.onClose();
	}
}
