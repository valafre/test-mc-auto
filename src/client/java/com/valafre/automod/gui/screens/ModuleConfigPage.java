package com.valafre.automod.gui.screens;

import com.valafre.automod.core.ModModule;
import com.valafre.automod.gui.components.Button;
import com.valafre.automod.gui.components.ScrollContainer;
import com.valafre.automod.gui.components.TabBar;
import com.valafre.automod.gui.components.Toggle;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.config.ConfigPageBuilder;
import com.valafre.automod.gui.config.ConfigTab;
import com.valafre.automod.gui.config.GuiModule;
import com.valafre.automod.gui.config.GuiModuleRegistry;
import com.valafre.automod.gui.navigation.GuiContext;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Configuration d'un module : retour, interrupteur, onglets verticaux dynamiques et cartes de réglages. */
public final class ModuleConfigPage extends Page {

	/** Onglet ouvert par module, conservé d'une ouverture à l'autre. */
	private static final Map<String, Integer> LAST_TAB = new HashMap<>();

	private final GuiContext ctx;
	private final ModModule module;
	private final GuiModule gui;
	private final List<ConfigTab> tabs;
	private final Button back;
	private final Toggle toggle;
	private final TabBar tabBar;
	private ScrollContainer scroll;
	private int tabIndex;

	public ModuleConfigPage(GuiContext ctx, String moduleId) {
		this.ctx = ctx;
		this.module = ctx.framework().modules().get(moduleId);
		this.gui = GuiModuleRegistry.get(moduleId);
		this.tabs = gui == null ? List.of() : gui.tabs();
		back = add(new Button(null, Button.Kind.SECONDARY, ctx.nav()::back).icon(Ui.tex("icon_arrow_left")));
		back.tooltip = "Retour";
		toggle = add(new Toggle(() -> module != null && module.isEnabled(),
			v -> ctx.framework().modules().setEnabled(ctx.framework(), moduleId, v)));
		tabBar = new TabBar(true, this::selectTab);
		for (ConfigTab t : tabs) {
			tabBar.tab(t.title(), Ui.tex(t.icon()));
		}
		add(tabBar);
		tabIndex = Math.min(LAST_TAB.getOrDefault(moduleId, 0), Math.max(0, tabs.size() - 1));
		tabBar.select(tabIndex);
		buildTab();
	}

	private void selectTab(int index) {
		tabIndex = index;
		LAST_TAB.put(module.id(), index);
		buildTab();
		arrange();
	}

	private void buildTab() {
		if (scroll != null) {
			scroll.commit();
			children.remove(scroll);
		}
		ConfigPageBuilder b = new ConfigPageBuilder();
		if (!tabs.isEmpty()) {
			tabs.get(tabIndex).content().accept(b);
		}
		scroll = new ScrollContainer(b.build());
		children.add(0, scroll);
	}

	@Override
	protected void arrange() {
		back.setBounds(x, y + 4, 28, 26);
		toggle.setBounds(x + w - Toggle.WIDTH, y + 8, Toggle.WIDTH, Toggle.HEIGHT);
		int tabW = w < 430 ? 98 : 128;
		int top = y + HEADER_H + 8;
		tabBar.setBounds(x, top, tabW, tabBar.preferredHeight());
		scroll.setBounds(x + tabW + 12, top, w - tabW - 12, h - HEADER_H - 8);
	}

	@Override
	protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		if (module == null) {
			Draw.text(g, ui.font, "Module introuvable.", x + 40, y + 12, Theme.ERROR);
			return;
		}
		int left = x + 38;
		Draw.scaled(g, ui.font, Draw.fit(ui.font, module.displayName(), toggle.x - left - 70), left, y + 3, 1.4f, Theme.TEXT);
		Draw.text(g, ui.font, Draw.fit(ui.font, module.description(), toggle.x - left - 70), left, y + 26, Theme.TEXT2);
		String state = module.isEnabled() ? "Actif" : "Inactif";
		Draw.right(g, ui.font, state, toggle.x - 8, y + 11, module.isEnabled() ? Theme.SUCCESS : Theme.DISABLED);
	}
}
