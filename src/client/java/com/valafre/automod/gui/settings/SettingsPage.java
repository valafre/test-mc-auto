package com.valafre.automod.gui.settings;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Zone de contenu d'une catégorie : grille de 2 colonnes. Les catégories y ajoutent des réglages (interrupteur, valeur
 * numérique, choix) sans se soucier de la mise en page ni des boutons.
 */
public final class SettingsPage {

	public static final int ROW = 22;
	public static final int MAX_ROWS = 7;
	private static final int BUTTON_H = 20;
	private static final int GAP = 4;
	private static final int STEP_BUTTON_W = 20;

	/** Texte simple (titre de section ou information) dessiné par l'écran. */
	public record Label(String text, int x, int y, int color) {}

	private final Consumer<Button> adder;
	private final int x;
	private final int y;
	private final int columnWidth;
	private final List<Label> labels = new ArrayList<>();
	private int column;
	private int row;

	public SettingsPage(Consumer<Button> adder, int x, int y, int width) {
		this.adder = adder;
		this.x = x;
		this.y = y;
		this.columnWidth = (width - GAP) / 2;
	}

	public List<Label> labels() {
		return labels;
	}

	// ========================================
	// MISE EN PAGE
	// ========================================

	private int cellX() {
		return x + column * (columnWidth + GAP);
	}

	private int cellY() {
		return y + row * ROW;
	}

	private void advance() {
		if (++column >= 2) {
			column = 0;
			row++;
		}
	}

	private void newRow() {
		if (column != 0) {
			column = 0;
			row++;
		}
	}

	// ========================================
	// ÉLÉMENTS
	// ========================================

	/** Titre de section sur une ligne entière. */
	public void section(String title) {
		newRow();
		labels.add(new Label(title, x, cellY() + 6, 0xFFFFD27F));
		row++;
	}

	/** Ligne d'information (grise), non modifiable. */
	public void info(String text) {
		newRow();
		labels.add(new Label(text, x, cellY() + 6, 0xFFAAAAAA));
		row++;
	}

	/** Interrupteur ON/OFF. */
	public void toggle(String label, BooleanSupplier state, Runnable flip) {
		Button button = Button.builder(Component.empty(), b -> {
			flip.run();
			b.setMessage(toggleText(label, state));
		}).bounds(cellX(), cellY(), columnWidth, BUTTON_H).build();
		button.setMessage(toggleText(label, state));
		adder.accept(button);
		advance();
	}

	private static Component toggleText(String label, BooleanSupplier state) {
		boolean on = state.getAsBoolean();
		return Component.literal(label + " : " + (on ? "ON" : "OFF"));
	}

	/** Valeur numérique avec boutons − et + ; bornée à [min, max]. */
	public void stepper(String label, DoubleSupplier get, DoubleConsumer set, double step, double min, double max) {
		int cx = cellX();
		int cy = cellY();
		Button middle = Button.builder(Component.empty(), b -> { })
			.bounds(cx + STEP_BUTTON_W + 2, cy, columnWidth - 2 * STEP_BUTTON_W - 4, BUTTON_H).build();
		middle.active = false;
		middle.setMessage(valueText(label, get.getAsDouble()));
		adder.accept(Button.builder(Component.literal("-"), b -> {
			set.accept(Mth.clamp(round(get.getAsDouble() - step), min, max));
			middle.setMessage(valueText(label, get.getAsDouble()));
		}).bounds(cx, cy, STEP_BUTTON_W, BUTTON_H).build());
		adder.accept(middle);
		adder.accept(Button.builder(Component.literal("+"), b -> {
			set.accept(Mth.clamp(round(get.getAsDouble() + step), min, max));
			middle.setMessage(valueText(label, get.getAsDouble()));
		}).bounds(cx + columnWidth - STEP_BUTTON_W, cy, STEP_BUTTON_W, BUTTON_H).build());
		advance();
	}

	/** Choix parmi plusieurs options, un clic passe à la suivante. */
	public void choice(String label, List<String> options, IntSupplier get, IntConsumer set) {
		Button button = Button.builder(Component.empty(), b -> {
			set.accept((get.getAsInt() + 1) % options.size());
			b.setMessage(Component.literal(label + " : " + options.get(get.getAsInt())));
		}).bounds(cellX(), cellY(), columnWidth, BUTTON_H).build();
		button.setMessage(Component.literal(label + " : " + options.get(get.getAsInt())));
		adder.accept(button);
		advance();
	}

	private static double round(double value) {
		return Math.round(value * 1000.0) / 1000.0; // évite 0.30000000000000004 après plusieurs pas
	}

	private static Component valueText(String label, double value) {
		String text = value == Math.floor(value) ? String.valueOf((long) value) : String.format("%.2f", value);
		return Component.literal(label + " : " + text);
	}
}
