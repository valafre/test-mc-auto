package com.valafre.automod.gui.components;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Contexte partagé d'une interface : police, composant focalisé, popup ouverte (liste déroulante) et infobulle en attente. */
public final class Ui {

	public final Minecraft mc;
	public final Font font;
	private UiComponent focus;
	private UiComponent popupOwner;
	private String tooltip;

	public Ui(Minecraft mc) {
		this.mc = mc;
		this.font = mc.font;
	}

	public static Identifier tex(String name) {
		return Identifier.fromNamespaceAndPath("automod", "textures/gui/" + name + ".png");
	}

	public UiComponent focus() {
		return focus;
	}

	public void setFocus(UiComponent c) {
		if (focus == c) {
			return;
		}
		UiComponent old = focus;
		focus = c;
		if (old != null) {
			old.onFocusChanged(false);
		}
		if (c != null) {
			c.onFocusChanged(true);
		}
	}

	public UiComponent popupOwner() {
		return popupOwner;
	}

	public void openPopup(UiComponent owner) {
		popupOwner = owner;
	}

	public void closePopup(UiComponent owner) {
		if (popupOwner == owner) {
			popupOwner = null;
		}
	}

	public void tooltip(String text) {
		if (text != null && !text.isEmpty()) {
			tooltip = text;
		}
	}

	/** À appeler après le rendu de tous les composants : popup puis infobulle, par-dessus le reste. */
	public void renderOverlays(GuiGraphicsExtractor g, int mx, int my, int screenW, int screenH) {
		if (popupOwner != null) {
			popupOwner.renderPopup(this, g, mx, my);
		}
		if (tooltip != null) {
			Tooltip.draw(this, g, tooltip, mx, my, screenW, screenH);
			tooltip = null;
		}
	}
}
