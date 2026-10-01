package com.valafre.automod.gui.screens;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.gui.components.Button;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.hud.HudRenderer;
import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Éditeur visuel du HUD : on le glisse à la souris, la molette règle la taille, le style se change d'un clic. */
public final class HudEditorScreen extends Screen {

	private final Framework framework;
	private final Screen parent;
	private Ui ui;
	private Button done;
	private Button style;
	private Button reset;
	private HudRenderer.Bounds bounds;
	private boolean dragging;
	private double grabX;
	private double grabY;

	public HudEditorScreen(Framework framework, Screen parent) {
		super(Component.literal("HUD"));
		this.framework = framework;
		this.parent = parent;
	}

	@Override
	protected void init() {
		ui = new Ui(minecraft);
		done = new Button("Terminé", Button.Kind.PRIMARY, this::onClose);
		style = new Button("", Button.Kind.SECONDARY, () -> ModConfig.get().hudStyle = 1 - ModConfig.get().hudStyle);
		reset = new Button("Réinitialiser", Button.Kind.SECONDARY, () -> {
			ModConfig c = ModConfig.get();
			c.hudPosX = 0.01f;
			c.hudPosY = 0.35f;
			c.hudScale = 1.0f;
		});
		int bw = 100;
		int total = bw * 3 + 16;
		int bx = (width - total) / 2;
		int by = height - 34;
		style.setBounds(bx, by, bw, Theme.CONTROL_H + 2);
		reset.setBounds(bx + bw + 8, by, bw, Theme.CONTROL_H + 2);
		done.setBounds(bx + (bw + 8) * 2, by, bw, Theme.CONTROL_H + 2);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0x66000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		Anim.frame();
		extractBackground(g, mx, my, delta);
		bounds = HudRenderer.draw(g, minecraft, framework, true);
		if (bounds != null) {
			int c = dragging || bounds.contains(mx, my) ? Theme.accent() : Draw.alpha(Theme.accent(), 0.5f);
			Draw.roundBorder(g, bounds.x() - 3, bounds.y() - 3, bounds.w() + 6, bounds.h() + 6, 6, c);
		}
		Draw.centered(g, font, "Glissez le HUD pour le déplacer  ·  Molette : taille  ·  Échap : terminer", width / 2, height - 52, Theme.TEXT);
		style.label = "Style : " + (ModConfig.get().hudStyle == 0 ? "Liste" : "Anneau");
		style.render(ui, g, mx, my);
		reset.render(ui, g, mx, my);
		done.render(ui, g, mx, my);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		for (Button b : new Button[] {style, reset, done}) {
			if (b.mouseClicked(ui, mx, my, event.button())) {
				return true;
			}
		}
		if (event.button() == 0 && bounds != null && bounds.contains(mx, my)) {
			dragging = true;
			grabX = mx - bounds.x();
			grabY = my - bounds.y();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging && bounds != null) {
			ModConfig c = ModConfig.get();
			double freeX = Math.max(1, width - bounds.w());
			double freeY = Math.max(1, height - bounds.h());
			c.hudPosX = (float) Mth.clamp((event.x() - grabX) / freeX, 0, 1);
			c.hudPosY = (float) Mth.clamp((event.y() - grabY) / freeY, 0, 1);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		for (Button b : new Button[] {style, reset, done}) {
			b.mouseReleased(ui, event.x(), event.y(), event.button());
		}
		boolean was = dragging;
		dragging = false;
		return was;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		ModConfig c = ModConfig.get();
		c.hudScale = (float) Mth.clamp(Math.round((c.hudScale + Math.signum(scrollY) * 0.05) * 100.0) / 100.0, 0.5, 2.0);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		ModConfig.save();
		minecraft.setScreen(parent);
	}
}
