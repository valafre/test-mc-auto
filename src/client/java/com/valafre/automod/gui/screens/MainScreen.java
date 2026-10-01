package com.valafre.automod.gui.screens;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.gui.components.Button;
import com.valafre.automod.gui.components.NavigationButton;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.config.Keybinds;
import com.valafre.automod.gui.navigation.GuiContext;
import com.valafre.automod.gui.navigation.Navigator;
import com.valafre.automod.gui.navigation.Route;
import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fenêtre principale du mod : barre latérale de navigation à gauche, page courante à droite. Aucune logique de jeu :
 * les pages lisent le {@link Framework} et modifient la config via leurs composants.
 */
public final class MainScreen extends Screen {

	private static final int MAX_W = 760;
	private static final int MAX_H = 440;
	private static final int TOP_PAD = 26;

	private final Framework framework;
	private final Navigator nav = new Navigator();
	private Ui ui;
	private GuiContext ctx;
	private Page page;
	private Route pendingRoute;
	private Button close;
	private final List<NavigationButton> navButtons = new ArrayList<>();
	private float open;

	private int wx;
	private int wy;
	private int ww;
	private int wh;
	private boolean compact;

	public MainScreen(Framework framework) {
		super(Component.literal("SkyAssist"));
		this.framework = framework;
	}

	@Override
	protected void init() {
		Theme.sync();
		if (ui == null) {
			ui = new Ui(minecraft);
			ctx = new GuiContext(framework, nav, ui);
			nav.onChange(route -> pendingRoute = route);
			pendingRoute = nav.current();
		}
		close = new Button("x", Button.Kind.SECONDARY, this::onClose);
		close.tooltip = "Fermer";

		navButtons.clear();
		addNav("Accueil", "icon_home", Route.Kind.HOME);
		addNav("Modules", "icon_modules", Route.Kind.MODULES);
		addNav("Paramètres", "icon_settings", Route.Kind.SETTINGS);
		addNav("Profils", "icon_profile", Route.Kind.PROFILES);
		addNav("À propos", "icon_info", Route.Kind.ABOUT);
		layoutWindow();
	}

	private void addNav(String label, String icon, Route.Kind kind) {
		navButtons.add(new NavigationButton(label, Ui.tex(icon), () -> nav.current().navKind() == kind, () -> nav.go(Route.of(kind))));
	}

	private Page createPage(Route route) {
		return switch (route.kind()) {
			case HOME -> new HomePage(ctx);
			case MODULES -> new ModulesPage(ctx);
			case MODULE -> new ModuleConfigPage(ctx, route.arg());
			case SETTINGS -> new SettingsPage(() -> minecraft.setScreen(new HudEditorScreen(framework, this)));
			case PROFILES -> new ProfilesPage();
			case ABOUT -> new AboutPage();
		};
	}

	private void layoutWindow() {
		ww = Math.min(width - 16, MAX_W);
		wh = Math.min(height - 16, MAX_H);
		wx = (width - ww) / 2;
		wy = (height - wh) / 2;
		compact = ww < 560;
		int sb = sidebarW();
		int ny = wy + (compact ? 56 : 64);
		for (NavigationButton b : navButtons) {
			b.compact = compact;
			b.setBounds(wx + 8, ny, sb - 16, NavigationButton.HEIGHT);
			ny += NavigationButton.HEIGHT + 2;
		}
		close.setBounds(wx + ww - 24, wy + 7, 16, 16);
		if (page != null) {
			placePage();
		}
	}

	private int sidebarW() {
		return compact ? 48 : 168;
	}

	private void placePage() {
		int left = wx + sidebarW() + 16;
		page.setBounds(left, wy + TOP_PAD, wx + ww - 16 - left, wh - TOP_PAD - 12);
	}

	private void applyPendingRoute() {
		if (pendingRoute == null) {
			return;
		}
		if (page != null) {
			page.commit();
			ModConfig.save();
		}
		ui.setFocus(null);
		ui.closePopup(ui.popupOwner());
		page = createPage(pendingRoute);
		pendingRoute = null;
		placePage();
	}

	@Override
	public void resize(int w, int h) {
		super.resize(w, h);
	}

	// ========================================
	// RENDU
	// ========================================

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
		// fond dessiné dans extractRenderState (assombrissement animé)
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		Anim.frame();
		applyPendingRoute();
		open = Anim.approach(open, 1f, 14f);
		Draw.setOpacity(open);
		try {
			g.fill(0, 0, width, height, Draw.alpha(0xFF000000, 0.6f * open));
			Draw.panel(g, wx, wy, ww, wh, 12, Theme.BG2, Theme.BORDER_STRONG);
			renderSidebar(g, mx, my);
			close.render(ui, g, mx, my);
			page.render(ui, g, mx, my);
			ui.renderOverlays(g, mx, my, width, height);
		} finally {
			Draw.setOpacity(1f);
		}
	}

	private void renderSidebar(GuiGraphicsExtractor g, int mx, int my) {
		int sb = sidebarW();
		// fond plus sombre à gauche, coins arrondis du côté de la fenêtre
		Draw.round(g, wx + 1, wy + 1, sb, wh - 2, 11, Theme.BG);
		Draw.rect(g, wx + sb - 10, wy + 1, 10, wh - 2, Theme.BG);
		Draw.rect(g, wx + sb, wy + 1, 1, wh - 2, Theme.BORDER);

		int lx = compact ? wx + (sb - 28) / 2 : wx + 14;
		Draw.image(g, Ui.tex("logo"), 48, 48, lx, wy + 14, 28, 28);
		if (!compact) {
			Draw.text(g, font, "SkyAssist", wx + 48, wy + 16, Theme.TEXT);
			Draw.text(g, font, "Automation Framework", wx + 48, wy + 28, Theme.TEXT2);
		}
		for (NavigationButton b : navButtons) {
			b.render(ui, g, mx, my);
		}
		// pied de barre : version
		String version = "v" + AboutPage.version("automod");
		String mc = "Minecraft " + AboutPage.version("minecraft") + " | Fabric";
		if (compact) {
			Draw.centered(g, font, version, wx + sb / 2, wy + wh - 18, Theme.TEXT2);
		} else {
			Draw.text(g, font, version, wx + 14, wy + wh - 28, Theme.accentHover());
			Draw.text(g, font, Draw.fit(font, mc, sb - 24), wx + 14, wy + wh - 16, Theme.TEXT2);
		}
	}

	// ========================================
	// ÉVÉNEMENTS
	// ========================================

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		int button = event.button();
		var popup = ui.popupOwner();
		if (popup != null) {
			popup.mouseClicked(ui, mx, my, button);
			return true;
		}
		var focus = ui.focus();
		if (focus != null && !focus.contains(mx, my)) {
			ui.setFocus(null);
		}
		if (close.mouseClicked(ui, mx, my, button)) {
			return true;
		}
		for (NavigationButton b : navButtons) {
			if (b.mouseClicked(ui, mx, my, button)) {
				return true;
			}
		}
		page.mouseClicked(ui, mx, my, button);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		close.mouseReleased(ui, event.x(), event.y(), event.button());
		page.mouseReleased(ui, event.x(), event.y(), event.button());
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return page.mouseDragged(ui, event.x(), event.y(), event.button(), dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (ui.popupOwner() != null) {
			return true;
		}
		return page.mouseScrolled(ui, mx, my, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		var popup = ui.popupOwner();
		if (popup != null) {
			if (event.key() == 256) {
				popup.commit();
				ui.closePopup(popup);
			}
			return true;
		}
		if (page.keyPressed(ui, event)) {
			return true; // un champ en cours d'édition ou un raccourci en écoute capte la touche
		}
		if (Keybinds.openGui.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return page.charTyped(ui, event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (page != null) {
			page.commit();
		}
		ModConfig.save();
		super.onClose();
	}
}
