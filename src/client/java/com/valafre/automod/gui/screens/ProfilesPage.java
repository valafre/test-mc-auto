package com.valafre.automod.gui.screens;

import com.valafre.automod.config.ProfileManager;
import com.valafre.automod.gui.components.Block;
import com.valafre.automod.gui.components.Button;
import com.valafre.automod.gui.components.Column;
import com.valafre.automod.gui.components.ScrollContainer;
import com.valafre.automod.gui.components.TextField;
import com.valafre.automod.gui.components.Ui;
import com.valafre.automod.gui.theme.Anim;
import com.valafre.automod.gui.theme.Draw;
import com.valafre.automod.gui.theme.Theme;
import com.valafre.automod.gui.widgets.Card;
import com.valafre.automod.gui.widgets.SettingRow;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Profils : enregistrer la configuration actuelle, charger, supprimer, réinitialiser. */
public final class ProfilesPage extends Page {

	private final ScrollContainer scroll;
	private final Card listCard = new Card("Profils enregistrés", "Cliquez sur Charger pour appliquer un profil");
	private String message = "";
	private boolean dirty;

	public ProfilesPage() {
		Column col = new Column(Theme.S12);
		Card create = new Card("Nouveau profil", "Enregistre la configuration actuelle (nom : lettres, chiffres, espace, - et _)");
		create.add(new NameRow());
		col.add(create);
		col.add(listCard);
		Card reset = new Card("Réinitialisation");
		ResetRow resetRow = new ResetRow();
		reset.add(new SettingRow("Valeurs par défaut", "Remet tous les réglages d'origine", resetRow.button, 110));
		reset.add(resetRow);
		col.add(reset);
		scroll = add(new ScrollContainer(col));
		rebuildList();
	}

	private void rebuildList() {
		listCard.children().clear();
		var names = ProfileManager.list();
		if (names.isEmpty()) {
			listCard.add(new SettingRow("Aucun profil enregistré", "Créez-en un avec le champ ci-dessus", null, 0));
		}
		for (String name : names) {
			listCard.add(new ProfileRow(name));
		}
	}

	/** Le rebuild est différé au prochain affichage : on est peut-être au milieu du traitement d'un clic sur la liste. */
	private void say(String text) {
		message = text;
		dirty = true;
	}

	@Override
	protected void arrange() {
		scroll.setBounds(x, y + HEADER_H + 8, w, h - HEADER_H - 8);
	}

	@Override
	protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
		if (dirty) {
			dirty = false;
			rebuildList();
		}
		String current = ProfileManager.current().isEmpty() ? "Par défaut" : ProfileManager.current();
		drawHeader(ui, g, "Profils", message.isEmpty() ? "Profil actif : " + current : message, x);
	}

	// ========================================
	// LIGNES
	// ========================================

	private final class NameRow extends Block {

		private final TextField field = add(new TextField(() -> "", s -> { }, "Nom du profil"));
		private final Button save = add(new Button("Enregistrer", Button.Kind.PRIMARY, this::save));

		private void save() {
			field.commit();
			say(ProfileManager.save(field.text()));
		}

		@Override
		public int layout(int x, int y, int width) {
			int h = 38;
			setBounds(x, y, width, h);
			boolean narrow = width < 260;
			int bw = narrow ? 80 : 100;
			field.setBounds(x, y + 8, width - bw - 8, Theme.CONTROL_H + 2);
			save.setBounds(x + width - bw, y + 9, bw, Theme.CONTROL_H);
			return h;
		}
	}

	private final class ProfileRow extends Block {

		private final String name;
		private final Button load = add(new Button("Charger", Button.Kind.SECONDARY, () -> say(ProfileManager.load(name()))));
		private final Button delete;
		private double armedAt = -10;

		ProfileRow(String name) {
			this.name = name;
			delete = add(new Button("Supprimer", Button.Kind.DANGER, this::deleteClick));
		}

		private String name() {
			return name;
		}

		private void deleteClick() {
			if (Anim.time() - armedAt < 3) {
				say(ProfileManager.delete(name));
			} else {
				armedAt = Anim.time();
			}
		}

		@Override
		public int layout(int x, int y, int width) {
			int h = 36;
			setBounds(x, y, width, h);
			delete.setBounds(x + width - 92, y + 8, 92, Theme.CONTROL_H);
			load.setBounds(x + width - 92 - 6 - 70, y + 8, 70, Theme.CONTROL_H);
			return h;
		}

		@Override
		protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
			Draw.rect(g, x, y, w, 1, 0x0DFFFFFF);
			delete.label = Anim.time() - armedAt < 3 ? "Confirmer ?" : "Supprimer";
			boolean active = name.equals(ProfileManager.current());
			int maxW = load.x - x - 10;
			Draw.text(g, ui.font, Draw.fit(ui.font, name, maxW - (active ? 44 : 0)), x, y + 14, Theme.TEXT);
			if (active) {
				Draw.text(g, ui.font, "Actif", x + Math.min(ui.font.width(name), maxW - 44) + 8, y + 14, Theme.SUCCESS);
			}
		}
	}

	private final class ResetRow extends Block {

		final Button button = new Button("Réinitialiser", Button.Kind.DANGER, this::click);
		private double armedAt = -10;

		private void click() {
			if (Anim.time() - armedAt < 3) {
				say(ProfileManager.resetDefaults());
				armedAt = -10;
			} else {
				armedAt = Anim.time();
			}
		}

		@Override
		public int layout(int x, int y, int width) {
			setBounds(x, y, width, 0);
			return 0;
		}

		@Override
		protected void renderSelf(Ui ui, GuiGraphicsExtractor g, int mx, int my) {
			button.label = Anim.time() - armedAt < 3 ? "Confirmer ?" : "Réinitialiser";
		}
	}
}
