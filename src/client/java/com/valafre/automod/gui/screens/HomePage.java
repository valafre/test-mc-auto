package com.valafre.automod.gui.screens;

import com.valafre.automod.config.ProfileManager;
import com.valafre.automod.core.ModModule;
import com.valafre.automod.gui.components.Block;
import com.valafre.automod.gui.components.Button;
import com.valafre.automod.gui.components.Column;
import com.valafre.automod.gui.components.ScrollContainer;
import com.valafre.automod.gui.components.StatusIndicator;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.config.Keybinds;
import com.valafre.automod.gui.navigation.GuiContext;
import com.valafre.automod.gui.navigation.Route;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import com.valafre.automod.gui.widgets.KeyChip;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Accueil : bannière de bienvenue, trois cartes d'état et le panneau des raccourcis. */
public final class HomePage extends Page {

	private final GuiContext ctx;
	private final ScrollContainer scroll;

	public HomePage(GuiContext ctx) {
		this.ctx = ctx;
		Column col = new Column(Theme.S12);
		col.add(new Hero());
		col.add(new StatRow());
		col.add(new Shortcuts());
		scroll = add(new ScrollContainer(col));
	}

	@Override
	protected void arrange() {
		scroll.setBounds(x, y, w, h);
	}

	// ========================================
	// BANNIÈRE
	// ========================================

	private final class Hero extends Block {

		private final Button modules;
		private final Button settings;

		Hero() {
			modules = add(new Button("Voir les modules", Button.Kind.PRIMARY, () -> ctx.nav().go(Route.of(Route.Kind.MODULES))));
			settings = add(new Button("Paramètres", Button.Kind.SECONDARY, () -> ctx.nav().go(Route.of(Route.Kind.SETTINGS))));
		}

		@Override
		public int layout(int x, int y, int width) {
			int h = width < 360 ? 132 : 112;
			setBounds(x, y, width, h);
			int by = y + h - 16 - Theme.CONTROL_H;
			modules.setBounds(x + 16, by, 112, Theme.CONTROL_H);
			settings.setBounds(x + 16 + 112 + 8, by, 84, Theme.CONTROL_H);
			return h;
		}

		@Override
		protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
			int r = Theme.RADIUS_L;
			Draw.round(g, x, y, w, h, r, Theme.PANEL);
			Draw.cover(g, Ui.tex("banner"), 640, 192, x, y, w, h, 0xFFFFFFFF);
			// fondu de gauche à droite pour garder le texte lisible
			int fadeW = Math.min(w, Math.max(160, w * 3 / 5));
			for (int i = 0; i < fadeW; i += 4) {
				float a = 0.96f * (1f - i / (float) fadeW);
				Draw.rect(g, x + i, y, Math.min(4, fadeW - i), h, Draw.alpha(0xFF0F0D1C, a));
			}
			Draw.cornerMask(g, x, y, w, h, r, Theme.BG2);
			Draw.roundBorder(g, x, y, w, h, r, Theme.BORDER_STRONG);
			Draw.scaled(g, ui.font, "Bienvenue sur SkyAssist", x + 16, y + 14, 1.5f, Theme.TEXT);
			Draw.text(g, ui.font, Draw.fit(ui.font, "Votre framework d'automatisation modulaire.", w - 32), x + 16, y + 36, Theme.TEXT2);
			Draw.text(g, ui.font, Draw.fit(ui.font, "Activez un module, ajustez-le, sauvegardez vos profils.", w - 32), x + 16, y + 48, Theme.TEXT2);
		}
	}

	// ========================================
	// CARTES D'ÉTAT
	// ========================================

	private final class StatRow extends Block {

		StatRow() {
			add(new Stat("icon_modules", "Modules actifs", this::activeValue, this::activeSub, () -> Theme.accentHover(), false));
			add(new Stat("icon_cube", "Statut", this::statusValue, this::statusSub, this::statusColor, true));
			add(new Stat("icon_profile", "Profil", this::profileValue, this::profileSub, () -> Theme.TEXT, false));
		}

		private int active() {
			int n = 0;
			for (ModModule m : ctx.framework().modules().all()) {
				n += m.isEnabled() ? 1 : 0;
			}
			return n;
		}

		private String activeValue() {
			return active() + " / " + ctx.framework().modules().all().size();
		}

		private String activeSub() {
			StringBuilder sb = new StringBuilder();
			for (ModModule m : ctx.framework().modules().all()) {
				if (m.isEnabled()) {
					sb.append(sb.length() > 0 ? ", " : "").append(m.displayName());
				}
			}
			return sb.length() == 0 ? "Aucun module actif" : sb.toString();
		}

		private String statusValue() {
			return active() > 0 ? "En cours" : "Au repos";
		}

		private int statusColor() {
			return active() > 0 ? Theme.SUCCESS : Theme.TEXT2;
		}

		private String statusSub() {
			String problem = ctx.framework().safety().lastProblem();
			return "aucun".equals(problem) ? "Sécurité : OK" : "Sécurité : " + problem;
		}

		private String profileValue() {
			return ProfileManager.current().isEmpty() ? "Par défaut" : ProfileManager.current();
		}

		private String profileSub() {
			int n = ProfileManager.list().size();
			return n + (n > 1 ? " profils enregistrés" : " profil enregistré");
		}

		@Override
		public int layout(int x, int y, int width) {
			int cols = width >= 420 ? 3 : 1;
			int gap = Theme.S12;
			int cw = (width - gap * (cols - 1)) / cols;
			int cardH = 78;
			int i = 0;
			for (var c : children) {
				int col = i % cols;
				int row = i / cols;
				c.setBounds(x + col * (cw + gap), y + row * (cardH + gap), cw, cardH);
				i++;
			}
			int rows = (children.size() + cols - 1) / cols;
			int h = rows * cardH + (rows - 1) * gap;
			setBounds(x, y, width, h);
			return h;
		}
	}

	private static final class Stat extends Block {

		private final String icon;
		private final String title;
		private final Supplier<String> value;
		private final Supplier<String> sub;
		private final IntSupplier color;
		private final boolean pulse;
		private final StatusIndicator dot;

		Stat(String icon, String title, Supplier<String> value, Supplier<String> sub, IntSupplier color, boolean pulse) {
			this.icon = icon;
			this.title = title;
			this.value = value;
			this.sub = sub;
			this.color = color;
			this.pulse = pulse;
			this.dot = pulse ? new StatusIndicator(() -> "", color, true) : null;
		}

		@Override
		public int layout(int x, int y, int width) {
			return h;
		}

		@Override
		protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
			Draw.panel(g, x, y, w, h, Theme.RADIUS_L, Theme.PANEL, Theme.BORDER);
			Draw.round(g, x + 12, y + 12, 22, 22, 6, Theme.accentSoft());
			Draw.icon(g, Ui.tex(icon), x + 16, y + 16, 14, Theme.accentHover());
			Draw.text(g, ui.font, title, x + 42, y + 19, Theme.TEXT2);
			int vx = x + 14;
			if (pulse) {
				dot.setBounds(vx - 4, y + 38, 16, 14);
				dot.render(ui, g, mx, my);
				vx += 12;
			}
			Draw.scaled(g, ui.font, Draw.fit(ui.font, value.get(), Math.round((w - 28) / 1.4f)), vx, y + 40, 1.4f, color.getAsInt());
			Draw.text(g, ui.font, Draw.fit(ui.font, sub.get(), w - 28), x + 14, y + 62, Theme.TEXT2);
		}
	}

	// ========================================
	// RACCOURCIS
	// ========================================

	private static final class Shortcuts extends Block {

		@Override
		public int layout(int x, int y, int width) {
			int rows = (int) Keybinds.all().stream().filter(Keybinds.Entry::inPanel).count();
			int h = 12 + 22 + rows * 22 + 6;
			setBounds(x, y, width, h);
			return h;
		}

		@Override
		protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
			Draw.panel(g, x, y, w, h, Theme.RADIUS_L, Theme.PANEL, Theme.BORDER);
			Draw.round(g, x + 12, y + 14, 3, 11, 1, Theme.accent());
			Draw.text(g, ui.font, "Raccourcis", x + 21, y + 15, Theme.TEXT);
			int cy = y + 34;
			for (Keybinds.Entry e : Keybinds.all()) {
				if (!e.inPanel()) {
					continue;
				}
				String key = e.mapping().isUnbound() ? "—" : e.mapping().getTranslatedKeyMessage().getString();
				int kw = KeyChip.width(ui.font, key);
				Draw.text(g, ui.font, Draw.fit(ui.font, e.label(), w - 24 - kw - 8), x + 12, cy + 4, Theme.TEXT2);
				KeyChip.draw(g, ui.font, key, x + w - 12 - kw, cy + 1, Theme.PANEL2, Theme.BORDER_STRONG, Theme.TEXT);
				cy += 22;
			}
		}
	}

	@Override
	protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		// la page n'a pas d'en-tête propre : la bannière sert de titre
	}
}
