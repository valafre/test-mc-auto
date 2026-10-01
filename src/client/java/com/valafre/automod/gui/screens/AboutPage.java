package com.valafre.automod.gui.screens;

import com.valafre.automod.gui.components.Block;
import com.valafre.automod.gui.components.Column;
import com.valafre.automod.gui.components.ScrollContainer;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.config.ConfigPageBuilder;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** À propos : identité du mod et versions (lues dans les métadonnées Fabric). */
public final class AboutPage extends Page {

	private final ScrollContainer scroll;

	public AboutPage() {
		ConfigPageBuilder b = new ConfigPageBuilder();
		b.card("Versions")
			.info("SkyAssist", version("automod"))
			.info("Minecraft", version("minecraft"))
			.info("Fabric Loader", version("fabricloader"))
			.info("Fabric API", version("fabric-api"));
		b.card("Description")
			.info("Framework d'automatisation modulaire", "Chaque module décrit lui-même ses réglages ; ce menu n'embarque aucune logique de jeu.")
			.info("Configuration", "config/automod.json  ·  profils : config/automod-profiles/");
		Column col = new Column(Theme.S12);
		col.add(new Identity());
		col.add(b.build());
		scroll = add(new ScrollContainer(col));
	}

	/** Version d'un mod installé, « inconnue » s'il est absent. */
	public static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("inconnue");
	}

	@Override
	protected void arrange() {
		scroll.setBounds(x, y + HEADER_H + 8, w, h - HEADER_H - 8);
	}

	@Override
	protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		drawHeader(ui, g, "À propos", "Informations sur le mod", x);
	}

	private static final class Identity extends Block {

		@Override
		public int layout(int x, int y, int width) {
			setBounds(x, y, width, 80);
			return 80;
		}

		@Override
		protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
			Draw.panel(g, x, y, w, h, Theme.RADIUS_L, Theme.PANEL, Theme.BORDER);
			Draw.image(g, Ui.tex("logo"), 48, 48, x + 16, y + 16, 48, 48);
			Draw.scaled(g, ui.font, "SkyAssist", x + 76, y + 18, 1.6f, Theme.TEXT);
			Draw.text(g, ui.font, "Automation Framework", x + 76, y + 36, Theme.TEXT2);
			Draw.text(g, ui.font, "v" + version("automod"), x + 76, y + 50, Theme.accentHover());
		}
	}
}
