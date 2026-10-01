package com.valafre.automod.gui.components;

import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Mth;

/** Zone défilante verticale (molette, glisser la barre) découpée par scissor ; son contenu est un {@link Block}. */
public final class ScrollContainer extends UiComponent {

	private static final int BAR_W = 4;
	private static final int GUTTER = 10;

	private final Block content;
	private float scroll;
	private float scrollTarget;
	private int contentH;
	private boolean draggingBar;

	public ScrollContainer(Block content) {
		this.content = content;
	}

	public Block content() {
		return content;
	}

	private int maxScroll() {
		return Math.max(0, contentH - h);
	}

	private void relayout() {
		contentH = content.layout(x, y - Math.round(scroll), w - GUTTER);
		scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll());
	}

	@Override
	public void setBounds(int x, int y, int w, int h) {
		super.setBounds(x, y, w, h);
		relayout();
	}

	@Override
	public void render(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll());
		scroll = Anim.approach(scroll, scrollTarget, 20f);
		relayout();
		g.enableScissor(x, y, x + w, y + h);
		int cmx = contains(mx, my) ? mx : -10000;
		int cmy = contains(mx, my) ? my : -10000;
		content.render(ui, g, cmx, cmy);
		g.disableScissor();
		if (maxScroll() > 0) {
			int trackH = h - 4;
			int thumbH = Math.max(18, trackH * h / contentH);
			int thumbY = y + 2 + Math.round((trackH - thumbH) * (scroll / maxScroll()));
			int bx = x + w - BAR_W - 1;
			Draw.round(g, bx, y + 2, BAR_W, trackH, 2, 0x14FFFFFF);
			Draw.round(g, bx, thumbY, BAR_W, thumbH, 2, draggingBar ? Theme.accentHover() : Draw.alpha(Theme.accent(), 0.8f));
		}
	}

	@Override
	public boolean mouseClicked(Ui ui, double mx, double my, int button) {
		if (!contains(mx, my)) {
			return false;
		}
		if (button == 0 && maxScroll() > 0 && mx >= x + w - GUTTER) {
			draggingBar = true;
			dragTo(my);
			return true;
		}
		return content.mouseClicked(ui, mx, my, button);
	}

	private void dragTo(double my) {
		int trackH = h - 4;
		int thumbH = Math.max(18, trackH * h / contentH);
		double t = (my - y - 2 - thumbH / 2.0) / Math.max(1, trackH - thumbH);
		scrollTarget = (float) (Mth.clamp(t, 0, 1) * maxScroll());
		scroll = scrollTarget;
	}

	@Override
	public boolean mouseDragged(Ui ui, double mx, double my, int button, double dx, double dy) {
		if (draggingBar) {
			dragTo(my);
			return true;
		}
		return content.mouseDragged(ui, mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(Ui ui, double mx, double my, int button) {
		boolean was = draggingBar;
		draggingBar = false;
		return content.mouseReleased(ui, mx, my, button) || was;
	}

	@Override
	public boolean mouseScrolled(Ui ui, double mx, double my, double amount) {
		if (!contains(mx, my)) {
			return false;
		}
		if (content.mouseScrolled(ui, mx, my, amount)) {
			return true;
		}
		scrollTarget = Mth.clamp(scrollTarget - (float) amount * 28f, 0, maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(Ui ui, KeyEvent event) {
		return content.keyPressed(ui, event);
	}

	@Override
	public boolean charTyped(Ui ui, CharacterEvent event) {
		return content.charTyped(ui, event);
	}

	@Override
	public void commit() {
		content.commit();
	}
}
