package com.valafre.automod.core;

import com.valafre.automod.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/** Journalisation de debug. Le message n'est construit que si {@code debugMode} est actif (pas de spam, pas d'allocation). */
public final class Debug {

	private static final Logger LOGGER = LoggerFactory.getLogger("automod");

	private Debug() {}

	public static boolean enabled() {
		return ModConfig.get().debugMode;
	}

	public static void log(String tag, Supplier<String> message) {
		if (enabled()) {
			LOGGER.info("[{}] {}", tag, message.get());
		}
	}
}
