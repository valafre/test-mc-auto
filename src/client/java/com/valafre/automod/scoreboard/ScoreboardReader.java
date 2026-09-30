package com.valafre.automod.scoreboard;

import com.valafre.automod.config.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lecture générique du scoreboard affiché à droite (slot SIDEBAR). Les lignes sont nettoyées (formatage, caractères
 * spéciaux), passées en minuscules et mises en cache : le rafraîchissement est espacé de {@code scoreboardRefreshTicks}.
 */
public final class ScoreboardReader {

	private final List<String> lines = new ArrayList<>();
	private int ticksSinceRefresh = Integer.MAX_VALUE / 2;

	// ========================================
	// SCOREBOARD
	// ========================================

	public void tick(ClientLevel level) {
		if (++ticksSinceRefresh < ModConfig.get().scoreboardRefreshTicks) {
			return;
		}
		ticksSinceRefresh = 0;
		refresh(level);
	}

	public void clear() {
		lines.clear();
		ticksSinceRefresh = Integer.MAX_VALUE / 2;
	}

	private void refresh(ClientLevel level) {
		lines.clear();
		Scoreboard scoreboard = level.getScoreboard();
		Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (sidebar == null) {
			return;
		}
		lines.add(clean(sidebar.getDisplayName().getString()));
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
			if (entry.isHidden()) {
				continue;
			}
			// Sur les serveurs custom, le texte visible est découpé en préfixe/nom/suffixe de team.
			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			Component owner = entry.ownerName() != null ? entry.ownerName() : Component.literal(entry.owner());
			lines.add(clean(PlayerTeam.formatNameForTeam(team, owner).getString()));
		}
	}

	/** Retire codes de formatage §x et tout caractère qui n'est pas lettre, chiffre, ponctuation ou espace. */
	static String clean(String raw) {
		String stripped = ChatFormatting.stripFormatting(raw);
		if (stripped == null) {
			return "";
		}
		return stripped.replaceAll("[^\\p{L}\\p{N}\\p{P}\\p{Zs}]", "").toLowerCase(Locale.ROOT).trim();
	}

	/** Une ligne du scoreboard contient-elle {@code text} (insensible à la casse et au formatage) ? */
	public boolean contains(String text) {
		String needle = clean(text);
		for (String line : lines) {
			if (line.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	public List<String> lines() {
		return List.copyOf(lines);
	}
}
