package com.valafre.automod.gui.screens;

import com.valafre.automod.core.ModModule;
import com.valafre.automod.gui.components.Block;
import com.valafre.automod.gui.components.Button;
import com.valafre.automod.gui.components.Column;
import com.valafre.automod.gui.components.ScrollContainer;
import com.valafre.automod.gui.components.TextField;
import com.valafre.automod.gui.components.Toggle;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.components.UiComponent;
import com.valafre.automod.gui.config.GuiModule;
import com.valafre.automod.gui.config.GuiModuleRegistry;
import com.valafre.automod.gui.navigation.GuiContext;
import com.valafre.automod.gui.navigation.Route;
import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Liste des modules : recherche, icône, description, interrupteur et accès à la configuration. */
public final class ModulesPage extends Page {

	private final GuiContext ctx;
	private final TextField search;
	private final ScrollContainer scroll;
	private final List<Row> rows = new ArrayList<>();
	private String lastQuery = "\0";

	public ModulesPage(GuiContext ctx) {
		this.ctx = ctx;
		search = add(new TextField(() -> "", s -> { }, "Rechercher un module…"));
		search.iconTexture = Ui.tex("icon_search");
		Column col = new Column(Theme.S8);
		for (ModModule m : ctx.framework().modules().all()) {
			Row row = new Row(m);
			rows.add(row);
			col.add(row);
		}
		scroll = add(new ScrollContainer(col));
	}

	@Override
	protected void arrange() {
		int sw = Math.min(220, w / 2 + 20);
		search.setBounds(x + w - sw, y + 8, sw, Theme.CONTROL_H + 2);
		scroll.setBounds(x, y + HEADER_H + 8, w, h - HEADER_H - 8);
	}

	@Override
	protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		drawHeader(ui, g, "Modules", rows.size() + (rows.size() > 1 ? " modules disponibles" : " module disponible"), x);
		String q = search.text().trim().toLowerCase(Locale.ROOT);
		if (!q.equals(lastQuery)) {
			lastQuery = q;
			for (Row r : rows) {
				r.visible = q.isEmpty() || r.matches(q);
			}
		}
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		super.render(ui, g, mx, my);
		if (rows.stream().noneMatch(r -> r.visible)) {
			Draw.centered(g, ui.font, "Aucun module ne correspond à la recherche.", x + w / 2, y + HEADER_H + 40, Theme.TEXT2);
		}
	}

	private final class Row extends Block {

		private final ModModule module;
		private final Toggle toggle;
		private final Button gear;
		private float hover;

		Row(ModModule module) {
			this.module = module;
			toggle = add(new Toggle(module::isEnabled, v -> ctx.framework().modules().setEnabled(ctx.framework(), module.id(), v)));
			gear = add(new Button(null, Button.Kind.SECONDARY, this::open).icon(Ui.tex("icon_settings")));
			gear.tooltip = "Configurer";
			gear.enabled = GuiModuleRegistry.get(module.id()) != null;
		}

		boolean matches(String q) {
			return module.displayName().toLowerCase(Locale.ROOT).contains(q)
				|| module.description().toLowerCase(Locale.ROOT).contains(q);
		}

		private void open() {
			ctx.nav().go(Route.module(module.id()));
		}

		@Override
		public int layout(int x, int y, int width) {
			int h = 56;
			setBounds(x, y, width, h);
			toggle.setBounds(x + width - 12 - Toggle.WIDTH, y + (h - Toggle.HEIGHT) / 2, Toggle.WIDTH, Toggle.HEIGHT);
			gear.setBounds(toggle.x - 8 - 26, y + (h - 24) / 2, 26, 24);
			return h;
		}

		@Override
		protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
			boolean over = contains(mx, my);
			hover = Anim.approach(hover, over ? 1f : 0f, 16f);
			Draw.panel(g, x, y, w, h, Theme.RADIUS_L, Draw.mix(Theme.PANEL, 0xFF1D1D2D, hover),
				Draw.mix(Theme.BORDER, Draw.alpha(Theme.accent(), 0.5f), hover));
			GuiModule gm = GuiModuleRegistry.get(module.id());
			var tex = Ui.tex(gm != null ? gm.icon() : "icon_cube");
			Draw.round(g, x + 12, y + 12, 32, 32, 8, Theme.PANEL2);
			if (gm != null && gm.icon().startsWith("module_")) {
				Draw.image(g, tex, 32, 32, x + 12, y + 12, 32, 32);
			} else {
				Draw.icon(g, tex, x + 20, y + 20, 16, Theme.accentHover());
			}
			int tx = x + 54;
			int maxW = gear.x - tx - 10;
			Draw.text(g, ui.font, Draw.fit(ui.font, module.displayName(), maxW), tx, y + 14, Theme.TEXT);
			Draw.text(g, ui.font, Draw.fit(ui.font, module.description(), maxW), tx, y + 28, Theme.TEXT2);
			String status = module.isEnabled() ? "● Actif" : "○ Inactif";
			Draw.text(g, ui.font, status, tx, y + 40 > y + h - 10 ? y + 38 : y + 40, module.isEnabled() ? Theme.SUCCESS : Theme.DISABLED);
		}

		@Override
		public boolean mouseClicked(Ui ui, double mx, double my, int button) {
			if (super.mouseClicked(ui, mx, my, button)) {
				return true;
			}
			if (button == 0 && contains(mx, my) && gear.enabled) {
				open();
				return true;
			}
			return false;
		}
	}
}
