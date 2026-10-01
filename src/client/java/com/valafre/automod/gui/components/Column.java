package com.valafre.automod.gui.components;

/** Pile verticale de blocs séparés par {@code gap}. */
public final class Column extends Block {

	private final int gap;

	public Column(int gap) {
		this.gap = gap;
	}

	@Override
	public int layout(int x, int y, int width) {
		int cy = y;
		boolean first = true;
		for (UiComponent c : children) {
			if (!c.visible) {
				continue;
			}
			if (!first) {
				cy += gap;
			}
			first = false;
			cy += c instanceof Block b ? b.layout(x, cy, width) : fixed(c, x, cy, width);
		}
		setBounds(x, y, width, cy - y);
		return cy - y;
	}

	private static int fixed(UiComponent c, int x, int y, int width) {
		c.setBounds(x, y, width, c.h);
		return c.h;
	}
}
