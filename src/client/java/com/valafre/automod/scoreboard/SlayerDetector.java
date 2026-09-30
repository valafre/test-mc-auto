package com.valafre.automod.scoreboard;

import com.valafre.automod.config.ModConfig;

/** Indique si une quête Slayer est affichée au scoreboard. Générique : utilisée par tous les modules Slayer. */
public final class SlayerDetector {

	private final ScoreboardReader scoreboard;

	public SlayerDetector(ScoreboardReader scoreboard) {
		this.scoreboard = scoreboard;
	}

	public boolean isActive() {
		return scoreboard.contains(ModConfig.get().slayerScoreboardKeyword);
	}
}
