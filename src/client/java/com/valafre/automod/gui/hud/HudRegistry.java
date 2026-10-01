package com.valafre.automod.gui.hud;

import com.valafre.automod.core.Debug;
import com.valafre.automod.gui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/** Lignes du HUD en jeu. Un module peut en ajouter via {@link #register}. */
public final class HudRegistry {

	private static final List<HudRow> ROWS = new ArrayList<>();

	private HudRegistry() {}

	public static void register(HudRow row) {
		ROWS.add(row);
	}

	public static List<HudRow> all() {
		return ROWS;
	}

	public static String formatTime(long ms) {
		long s = Math.max(0, ms / 1000);
		long h = s / 3600;
		long m = (s / 60) % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s % 60) : String.format("%02d:%02d", m, s % 60);
	}

	/** PV en forme courte : 5.2M, 780k. */
	public static String formatHp(double v) {
		if (v < 0) {
			return "—";
		}
		if (v >= 1_000_000) {
			return String.format("%.1fM", v / 1_000_000).replace(',', '.');
		}
		if (v >= 1_000) {
			return String.format("%.0fk", v / 1_000);
		}
		return String.valueOf(Math.round(v));
	}

	public static void registerDefaults() {
		register(new HudRow("status", "Statut", false, d -> new HudRow.Cell("Statut",
			d.moduleEnabled() ? "Actif" : "Inactif", d.moduleEnabled() ? Theme.SUCCESS : Theme.ERROR)));
		register(new HudRow("target", "Cible", false, d -> {
			double r = d.info().hpRatio();
			String label = r >= 0 ? "Cible (" + Math.round(r * 100) + "%)" : "Cible";
			return new HudRow.Cell(label, d.info().target().isEmpty() ? "—" : d.info().target(), Theme.TEXT);
		}));
		register(new HudRow("state", "État", false, d -> new HudRow.Cell("État",
			d.info().state().isEmpty() ? "—" : d.info().state(), Theme.accent())));
		register(new HudRow("time", "Temps", false, d -> new HudRow.Cell("Temps", formatTime(d.info().uptimeMs()), Theme.TEXT)));
		register(new HudRow("kills", "Kills", false, d -> new HudRow.Cell("Kills", String.valueOf(d.info().kills()), Theme.TEXT)));
		register(new HudRow("failsafe", "Failsafe", false, d -> {
			if (d.preview() || d.framework() == null) {
				return new HudRow.Cell("Failsafe", "OK", Theme.SUCCESS);
			}
			String problem = d.framework().safety().lastProblem();
			boolean ok = "aucun".equals(problem);
			return new HudRow.Cell("Failsafe", ok ? "OK" : problem, ok ? Theme.SUCCESS : Theme.WARNING);
		}));
		register(new HudRow("survival", "Survie (PV / Orb)", false, d -> {
			if (d.preview() || d.framework() == null) {
				return new HudRow.Cell("Survie", "PV 100% · Orb OK", Theme.TEXT2);
			}
			return new HudRow.Cell("Survie", d.framework().support().shortStatus(), Theme.TEXT2);
		}));
		register(new HudRow("task", "Tâche (debug)", true, d -> {
			if (d.framework() == null || !Debug.enabled()) {
				return null;
			}
			var task = d.framework().tasks().current();
			return new HudRow.Cell("Tâche", task == null ? "aucune" : task.name(), Theme.TEXT2);
		}));
		register(new HudRow("movement", "Mouvement (debug)", true, d -> {
			if (d.framework() == null || !Debug.enabled()) {
				return null;
			}
			return new HudRow.Cell("Mouvement", d.framework().movement().lastStatus(), Theme.TEXT2);
		}));
	}
}
