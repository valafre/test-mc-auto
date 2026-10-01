package com.valafre.automod.gui.components;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Composant conteneur : se met en page lui-même ({@link #layout}) puis dessine ses enfants et leur transmet les événements.
 * Les écrans, cartes, lignes et colonnes en dérivent.
 */
public abstract class Block extends UiComponent {

	protected final List<UiComponent> children = new ArrayList<>();

	public List<UiComponent> children() {
		return children;
	}

	public <T extends UiComponent> T add(T child) {
		children.add(child);
		return child;
	}

	/** Place ce bloc en (x, y) sur la largeur {@code width} et renvoie sa hauteur. */
	public abstract int layout(int x, int y, int width);

	protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		if (!visible) {
			return;
		}
		renderSelf(ui, g, mx, my);
		for (UiComponent c : children) {
			if (c.visible) {
				c.render(ui, g, mx, my);
			}
		}
	}

	@Override
	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		for (int i = children.size() - 1; i >= 0; i--) {
			UiComponent c = children.get(i);
			if (c.visible && c.mouseClicked(ui, mx, my, button)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseReleased(Ui ui, double mx, double my, int button) {
		boolean used = false;
		for (UiComponent c : children) {
			used |= c.mouseReleased(ui, mx, my, button);
		}
		return used;
	}

	@Override
	public boolean mouseDragged(Ui ui, double mx, double my, int button, double dx, double dy) {
		for (UiComponent c : children) {
			if (c.mouseDragged(ui, mx, my, button, dx, dy)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(Ui ui, double mx, double my, double amount) {
		for (int i = children.size() - 1; i >= 0; i--) {
			UiComponent c = children.get(i);
			if (c.visible && c.mouseScrolled(ui, mx, my, amount)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean keyPressed(Ui ui, KeyEvent event) {
		for (UiComponent c : children) {
			if (c.keyPressed(ui, event)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean charTyped(Ui ui, CharacterEvent event) {
		for (UiComponent c : children) {
			if (c.charTyped(ui, event)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void commit() {
		for (UiComponent c : children) {
			c.commit();
		}
	}
}
