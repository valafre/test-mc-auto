package com.valafre.automod.gui.navigation;

import com.valafre.automod.core.Framework;
import com.valafre.automod.gui.components.Ui;

/** Ce que les pages reçoivent : le framework (lecture d'état), la navigation et le contexte de dessin. */
public record GuiContext(Framework framework, Navigator nav, Ui ui) {}
