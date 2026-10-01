package com.valafre.automod.gui.theme;

import com.valafre.automod.config.ModConfig;

/** Palette, dimensions et animations de l'interface : tout le style est ici, aucun composant ne code une couleur en dur. */
public final class Theme {

	private Theme() {}

	// Fonds
	public static final int BG = 0xFF0B0B12;
	public static final int BG2 = 0xFF11111C;
	public static final int PANEL = 0xFF161622;
	public static final int PANEL2 = 0xFF1C1C2A;
	// Textes
	public static final int TEXT = 0xFFF5F5F7;
	public static final int TEXT2 = 0xFFA1A1B5;
	public static final int DISABLED = 0xFF5F6070;
	// États
	public static final int SUCCESS = 0xFF22C55E;
	public static final int WARNING = 0xFFF59E0B;
	public static final int ERROR = 0xFFEF4444;
	// Bordure : blanc à ~10 %
	public static final int BORDER = 0x1AFFFFFF;
	public static final int BORDER_STRONG = 0x33FFFFFF;

	// Espacements et rayons
	public static final int S4 = 4;
	public static final int S8 = 8;
	public static final int S12 = 12;
	public static final int S16 = 16;
	public static final int S24 = 24;
	public static final int S32 = 32;
	public static final int RADIUS = 8;
	public static final int RADIUS_L = 10;
	public static final int ROW_H = 34;
	public static final int CONTROL_H = 20;

	/** Préréglages de couleur d'accent : nom, couleur normale. */
	public static final String[] ACCENT_NAMES = {"Violet", "Bleu", "Vert", "Rose", "Orange"};
	private static final int[] ACCENTS = {0xFF8B5CF6, 0xFF3B82F6, 0xFF22C55E, 0xFFEC4899, 0xFFF97316};

	private static int accent = ACCENTS[0];
	private static int preset = 0;

	/** Applique le préréglage choisi dans la config (appelé à l'ouverture de l'interface). */
	public static void sync() {
		int wanted = Math.max(0, Math.min(ACCENTS.length - 1, ModConfig.get().guiAccentPreset));
		if (wanted != preset) {
			preset = wanted;
			accent = ACCENTS[wanted];
		}
	}

	public static int accent() {
		return accent;
	}

	public static int accentHover() {
		return Draw.mix(accent, 0xFFFFFFFF, 0.28f);
	}

	public static int accentActive() {
		return Draw.mix(accent, 0xFF000000, 0.22f);
	}

	/** Accent très discret pour les fonds sélectionnés. */
	public static int accentSoft() {
		return Draw.alpha(accent, 0.18f);
	}

	/** Vitesse des animations (0 = instantané). */
	public static float animSpeed() {
		return Math.max(0f, ModConfig.get().guiAnimSpeed);
	}
}
