package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/** Champ texte : curseur, sélection complète (Ctrl+A), copier/coller, validation à Entrée ou à la perte du focus. */
public class TextField extends UiComponent {

	private static final int GLFW_BACKSPACE = 259;
	private static final int GLFW_DELETE = 261;
	private static final int GLFW_LEFT = 263;
	private static final int GLFW_RIGHT = 262;
	private static final int GLFW_HOME = 268;
	private static final int GLFW_END = 269;
	private static final int GLFW_ENTER = 257;
	private static final int GLFW_KP_ENTER = 335;
	private static final int GLFW_ESCAPE = 256;
	private static final int MOD_CTRL = 2;

	private final Supplier<String> get;
	private final Consumer<String> set;
	protected String placeholder = "";
	protected IntPredicate filter = cp -> cp >= 32 && cp != 127;
	protected int maxLength = 256;
	private StringBuilder buffer = new StringBuilder();
	private boolean editing;
	private boolean selectAll;
	private int cursor;
	private float focusAnim;

	public net.minecraft.resources.Identifier iconTexture;

	public TextField(Supplier<String> get, Consumer<String> set, String placeholder) {
		this.get = get;
		this.set = set;
		this.placeholder = placeholder;
		this.h = Theme.CONTROL_H;
	}

	public String text() {
		return editing ? buffer.toString() : get.get();
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		boolean over = enabled && contains(mx, my);
		focusAnim = Anim.approach(focusAnim, editing ? 1f : 0f, 18f);
		Draw.round(g, x, y, w, h, Theme.RADIUS - 1, over || editing ? 0xFF20202F : Theme.PANEL2);
		Draw.roundBorder(g, x, y, w, h, Theme.RADIUS - 1, Draw.mix(over ? Theme.BORDER_STRONG : Theme.BORDER, Theme.accent(), focusAnim));
		int left = x + 8;
		if (iconTexture != null) {
			Draw.icon(g, iconTexture, x + 7, y + (h - 12) / 2, 12, Theme.TEXT2);
			left += 17;
		}
		int ty = y + (h - ui.font.lineHeight) / 2 + 1;
		int avail = w - (left - x) - 8;
		String text = text();
		if (text.isEmpty() && !editing) {
			Draw.text(g, ui.font, Draw.fit(ui.font, placeholder, avail), left, ty, Theme.DISABLED);
			return;
		}
		// Fenêtre de texte : on affiche la fin jusqu'au curseur si le contenu dépasse
		int start = 0;
		int cur = Math.min(cursor, text.length());
		while (start < cur && ui.font.width(text.substring(start, cur)) > avail - 4) {
			start++;
		}
		int end = text.length();
		while (end > cur && ui.font.width(text.substring(start, end)) > avail) {
			end--;
		}
		String visibleText = text.substring(start, end);
		if (editing && selectAll && !text.isEmpty()) {
			Draw.rect(g, left - 1, y + 3, ui.font.width(visibleText) + 2, h - 6, Draw.alpha(Theme.accent(), 0.35f));
		}
		Draw.text(g, ui.font, visibleText, left, ty, enabled ? Theme.TEXT : Theme.DISABLED);
		if (editing && ((long) (Anim.time() * 2f)) % 2 == 0) {
			int cx = left + ui.font.width(text.substring(start, cur));
			Draw.rect(g, cx, y + 4, 1, h - 8, Theme.TEXT);
		}
	}

	@Override
	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		if (!enabled) {
			return false;
		}
		if (contains(mx, my) && button == 0) {
			if (!editing) {
				ui.setFocus(this);
				// onFocusChanged démarre l'édition
			}
			return true;
		}
		return false;
	}

	@Override
	public void onFocusChanged(boolean focused) {
		if (focused) {
			buffer = new StringBuilder(get.get());
			cursor = buffer.length();
			selectAll = false;
			editing = true;
		} else if (editing) {
			finish();
		}
	}

	private void finish() {
		editing = false;
		selectAll = false;
		onCommit(buffer.toString());
	}

	/** Valide le texte saisi ; les sous-classes l'interprètent (nombre...). */
	protected void onCommit(String text) {
		set.accept(text);
	}

	@Override
	public void commit() {
		if (editing) {
			finish();
		}
	}

	public boolean isEditing() {
		return editing;
	}

	@Override
	public boolean keyPressed(Ui ui, KeyEvent event) {
		if (!editing) {
			return false;
		}
		int key = event.key();
		boolean ctrl = (event.modifiers() & MOD_CTRL) != 0;
		if (key == GLFW_ESCAPE) {
			editing = false; // annule
			ui.setFocus(null);
			return true;
		}
		if (key == GLFW_ENTER || key == GLFW_KP_ENTER) {
			ui.setFocus(null);
			return true;
		}
		if (ctrl && key == 65) { // A
			selectAll = true;
			return true;
		}
		if (ctrl && key == 86) { // V
			insert(ui.mc.keyboardHandler.getClipboard());
			return true;
		}
		if (ctrl && key == 67) { // C
			ui.mc.keyboardHandler.setClipboard(buffer.toString());
			return true;
		}
		switch (key) {
			case GLFW_BACKSPACE -> {
				if (selectAll) {
					buffer.setLength(0);
					cursor = 0;
					selectAll = false;
				} else if (cursor > 0) {
					buffer.deleteCharAt(--cursor);
				}
			}
			case GLFW_DELETE -> {
				if (selectAll) {
					buffer.setLength(0);
					cursor = 0;
					selectAll = false;
				} else if (cursor < buffer.length()) {
					buffer.deleteCharAt(cursor);
				}
			}
			case GLFW_LEFT -> { selectAll = false; cursor = Math.max(0, cursor - 1); }
			case GLFW_RIGHT -> { selectAll = false; cursor = Math.min(buffer.length(), cursor + 1); }
			case GLFW_HOME -> { selectAll = false; cursor = 0; }
			case GLFW_END -> { selectAll = false; cursor = buffer.length(); }
			default -> { return true; } // le champ capte toutes les touches tant qu'il est actif
		}
		return true;
	}

	@Override
	public boolean charTyped(Ui ui, CharacterEvent event) {
		if (!editing) {
			return false;
		}
		insert(event.codepointAsString());
		return true;
	}

	private void insert(String text) {
		if (text == null) {
			return;
		}
		StringBuilder clean = new StringBuilder();
		text.codePoints().filter(filter).forEach(clean::appendCodePoint);
		if (clean.length() == 0) {
			return;
		}
		if (selectAll) {
			buffer.setLength(0);
			cursor = 0;
			selectAll = false;
		}
		int room = maxLength - buffer.length();
		if (room <= 0) {
			return;
		}
		String add = clean.length() > room ? clean.substring(0, room) : clean.toString();
		buffer.insert(cursor, add);
		cursor += add.length();
	}
}
