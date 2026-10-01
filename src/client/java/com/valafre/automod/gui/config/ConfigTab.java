package com.valafre.automod.gui.config;

import java.util.function.Consumer;

/**
 * Un onglet de la configuration d'un module : titre, icône (nom de texture sans extension) et fonction qui remplit la page.
 * Les onglets d'un module sont déclarés par son {@link GuiModule}, jamais par l'interface.
 */
public record ConfigTab(String id, String title, String icon, Consumer<ConfigPageBuilder> content) {}
