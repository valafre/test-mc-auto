package com.valafre.automod.gui.components;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/** Base de tous les composants : un rectangle, un rendu, des événements souris/clavier (coordonnées écran). */
public abstract class UiComponent {

	public int x;
	public int y;
	public int w;
	public int h;
	public boolean visible = true;
	public boolean enabled = true;
	public String tooltip;

	public void setBounds(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
	}

	public boolean contains(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public abstract void render(Ui ui, GuiGraphicsExtractor g, int mx, int my);

	/** Popup dessinée au-dessus de tout (liste déroulante). */
	public void renderPopup(Ui ui, GuiGraphicsExtractor g, int mx, int my) {}

	/** Popup ouverte ? Elle capte alors les clics avant les autres composants. */
	public boolean popupContains(double mx, double my) {
		return false;
	}

	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		return false;
	}

	public boolean mouseReleased(Ui ui, double mx, double my, int button) {
		return false;
	}

	public boolean mouseDragged(Ui ui, double mx, double my, int button, double dx, double dy) {
		return false;
	}

	public boolean mouseScrolled(Ui ui, double mx, double my, double amount) {
		return false;
	}

	public boolean keyPressed(Ui ui, KeyEvent event) {
		return false;
	}

	public boolean charTyped(Ui ui, CharacterEvent event) {
		return false;
	}

	public void onFocusChanged(boolean focused) {}

	/** Fin d'édition (fermeture de l'écran, changement de page) : valider ce qui est en cours. */
	public void commit() {}
}
