package com.valafre.automod.gui.hud;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.core.HudInfo;
import com.valafre.automod.core.ModModule;
import com.valafre.automod.gui.config.Keybinds;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import com.valafre.automod.gui.widgets.KeyChip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dessine le HUD en jeu (style liste ou anneau) selon la config : position, échelle, opacité, lignes visibles.
 * Les lignes sont recalculées toutes les ~100 ms seulement.
 */
public final class HudRenderer {

	/** Rectangle occupé à l'écran (coordonnées GUI, échelle incluse). */
	public record Bounds(int x, int y, int w, int h) {
		public boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	public static final HudInfo SAMPLE = new HudInfo("Attaque", "Voidgloom Seraph", "T4", 0.75, 5_850_000, 7_800_000, 12, 125_000);

	private static final int LIST_W = 158;
	private static final int RING_W = 136;
	private static final int ROW_H = 12;
	private static final int PAD = 8;
	private static final long REFRESH_MS = 100;

	private static List<HudRow.Cell> cache = List.of();
	private static HudInfo cacheInfo = HudInfo.EMPTY;
	private static String cacheName = "";
	private static boolean cacheEnabled;
	private static long cachedAt;
	private static boolean cachePreview;

	private HudRenderer() {}

	private static ModModule pickModule(Framework f, boolean preview) {
		if (f == null) {
			return null;
		}
		ModModule first = null;
		for (ModModule m : f.modules().all()) {
			if (first == null) {
				first = m;
			}
			if (m.isEnabled()) {
				return m;
			}
		}
		return preview ? first : null;
	}

	private static Set<String> hidden() {
		String raw = ModConfig.get().hudHiddenRows;
		Set<String> set = new HashSet<>();
		if (raw != null) {
			Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(set::add);
		}
		return set;
	}

	private static void refresh(Framework f, ModModule module, boolean preview) {
		long now = System.currentTimeMillis();
		if (!preview && !cachePreview && now - cachedAt < REFRESH_MS && module != null) {
			return;
		}
		cachedAt = now;
		cachePreview = preview;
		boolean enabled = preview || (module != null && module.isEnabled());
		HudInfo info = preview ? SAMPLE : module == null ? HudInfo.EMPTY : module.hudInfo();
		Set<String> hide = hidden();
		HudRow.Data data = new HudRow.Data(f, info, enabled, preview);
		List<HudRow.Cell> cells = new ArrayList<>();
		for (HudRow row : HudRegistry.all()) {
			if (hide.contains(row.key()) || (ModConfig.get().hudStyle == 1 && row.key().equals("target"))) {
				continue;
			}
			HudRow.Cell cell = row.cell().apply(data);
			if (cell != null) {
				cells.add(cell);
			}
		}
		cache = cells;
		cacheInfo = info;
		cacheName = module == null ? "Module" : module.displayName();
		cacheEnabled = enabled;
	}

	/**
	 * Dessine le HUD ; {@code preview} utilise des données d'exemple (éditeur). Renvoie le rectangle occupé, ou {@code null}
	 * si rien n'est affiché.
	 */
	public static Bounds draw(GuiGraphicsExtractor g, Minecraft mc, Framework f, boolean preview) {
		ModConfig c = ModConfig.get();
		if (!preview && !c.hudEnabled) {
			return null;
		}
		ModModule module = pickModule(f, preview);
		if (!preview && module == null) {
			return null;
		}
		refresh(f, module, preview);
		Font font = mc.font;
		boolean ring = c.hudStyle == 1;
		int panelW = ring ? RING_W : LIST_W;
		int panelH = ring ? ringHeight() : listHeight();
		boolean shortcuts = c.hudShowShortcuts;
		List<Keybinds.Entry> keys = new ArrayList<>();
		if (shortcuts) {
			for (Keybinds.Entry e : Keybinds.all()) {
				if (e.inPanel()) {
					keys.add(e);
				}
			}
		}
		int shortH = keys.isEmpty() ? 0 : PAD * 2 + 14 + keys.size() * 17;
		int totalH = panelH + (shortH > 0 ? 6 + shortH : 0);

		float scale = Math.max(0.5f, Math.min(2.5f, c.hudScale));
		int sw = mc.getWindow().getGuiScaledWidth();
		int sh = mc.getWindow().getGuiScaledHeight();
		int bw = Math.round(panelW * scale);
		int bh = Math.round(totalH * scale);
		int bx = Math.round(Math.max(0, sw - bw) * Math.max(0f, Math.min(1f, c.hudPosX)));
		int by = Math.round(Math.max(0, sh - bh) * Math.max(0f, Math.min(1f, c.hudPosY)));

		Draw.setOpacity(c.hudOpacity);
		try {
			g.pose().pushMatrix();
			g.pose().translate(bx, by);
			g.pose().scale(scale, scale);
			if (ring) {
				drawRing(g, font, panelW, panelH);
			} else {
				drawList(g, font, panelW, panelH);
			}
			if (shortH > 0) {
				drawShortcuts(g, font, panelW, panelH + 6, shortH, keys);
			}
			g.pose().popMatrix();
		} finally {
			Draw.setOpacity(1f);
		}
		return new Bounds(bx, by, bw, bh);
	}

	// ========================================
	// STYLE LISTE
	// ========================================

	private static int listHeight() {
		return PAD + 16 + 4 + cache.size() * ROW_H + PAD - 2;
	}

	private static void drawHeader(GuiGraphicsExtractor g, Font font, int w) {
		float p = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 1000.0 * 3.0));
		int dot = cacheEnabled ? Theme.SUCCESS : Theme.ERROR;
		if (cacheEnabled) {
			Draw.circle(g, PAD + 4, PAD + 6, 5, Draw.alpha(dot, 0.10f + 0.18f * p));
		}
		Draw.circle(g, PAD + 4, PAD + 6, 3, dot);
		Draw.text(g, font, Draw.fit(font, cacheName, w - PAD * 2 - 16), PAD + 14, PAD + 2, Theme.TEXT);
	}

	private static void drawList(GuiGraphicsExtractor g, Font font, int w, int h) {
		Draw.panel(g, 0, 0, w, h, Theme.RADIUS, 0xE6101019, Draw.alpha(Theme.accent(), 0.45f));
		Draw.round(g, 1, 8, 2, h - 16, 1, Theme.accent());
		drawHeader(g, font, w);
		Draw.rect(g, PAD, PAD + 16, w - PAD * 2, 1, 0x14FFFFFF);
		int y = PAD + 21;
		for (HudRow.Cell cell : cache) {
			Draw.text(g, font, cell.label(), PAD, y, Theme.TEXT2);
			Draw.right(g, font, Draw.fit(font, cell.text(), w - PAD * 2 - font.width(cell.label()) - 6), w - PAD, y, cell.color());
			y += ROW_H;
		}
	}

	// ========================================
	// STYLE ANNEAU
	// ========================================

	private static int ringHeight() {
		return PAD + 16 + 4 + 64 + 6 + 12 + cache.size() * ROW_H + PAD;
	}

	private static void drawRing(GuiGraphicsExtractor g, Font font, int w, int h) {
		Draw.panel(g, 0, 0, w, h, Theme.RADIUS, 0xE6101019, Draw.alpha(Theme.accent(), 0.45f));
		drawHeader(g, font, w);
		HudInfo info = cacheInfo;
		int cx = w / 2;
		int cy = PAD + 20 + 32;
		float frac = (float) Math.max(0, info.hpRatio());
		int color = frac > 0.5f ? Theme.SUCCESS : frac > 0.25f ? Theme.WARNING : Theme.ERROR;
		if (info.hpRatio() < 0) {
			color = Theme.accent();
		}
		Draw.ring(g, cx, cy, 30, 5, info.hpRatio() < 0 ? 0f : frac, color, 0xFF2C2C40);
		String tier = info.tier().isEmpty() ? "—" : info.tier();
		Draw.scaled(g, font, tier, cx - Math.round(font.width(tier) * 1.4f / 2f), cy - 12, 1.4f, Theme.TEXT);
		String pct = info.hpRatio() < 0 ? "—" : Math.round(info.hpRatio() * 100) + "%";
		Draw.centered(g, font, pct, cx, cy + 4, Theme.TEXT2);
		int y = cy + 38;
		String hp = "PV " + HudRegistry.formatHp(info.hp()) + " / " + HudRegistry.formatHp(info.maxHp());
		Draw.centered(g, font, Draw.fit(font, hp, w - PAD * 2), cx, y, Theme.TEXT);
		y += 12;
		for (HudRow.Cell cell : cache) {
			Draw.text(g, font, cell.label(), PAD, y, Theme.TEXT2);
			Draw.right(g, font, Draw.fit(font, cell.text(), w - PAD * 2 - font.width(cell.label()) - 6), w - PAD, y, cell.color());
			y += ROW_H;
		}
	}

	// ========================================
	// RACCOURCIS
	// ========================================

	private static void drawShortcuts(GuiGraphicsExtractor g, Font font, int w, int top, int h, List<Keybinds.Entry> keys) {
		Draw.panel(g, 0, top, w, h, Theme.RADIUS, 0xE6101019, Theme.BORDER_STRONG);
		Draw.text(g, font, "Raccourcis", PAD, top + PAD, Theme.TEXT);
		int y = top + PAD + 16;
		for (Keybinds.Entry e : keys) {
			String key = e.mapping().isUnbound() ? "—" : e.mapping().getTranslatedKeyMessage().getString();
			int kw = KeyChip.width(font, key);
			Draw.text(g, font, Draw.fit(font, e.label(), w - PAD * 2 - kw - 6), PAD, y + 3, Theme.TEXT2);
			KeyChip.draw(g, font, key, w - PAD - kw, y, Theme.PANEL2, Theme.BORDER_STRONG, Theme.TEXT);
			y += 17;
		}
	}
}
