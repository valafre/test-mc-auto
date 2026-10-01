package com.valafre.automod.gui.settings;

import com.valafre.automod.config.ModConfig;
import com.valafre.automod.core.Framework;
import com.valafre.automod.modules.slayer.voidgloom.VoidgloomModule;

import java.util.List;

/** Catégories fournies par défaut. Un nouveau module crée sa propre {@link SettingsCategory} et l'enregistre de la même façon. */
public final class DefaultCategories {

	private DefaultCategories() {}

	public static void registerAll() {
		SettingsRegistry.register(new Entry("Voidgloom", DefaultCategories::voidgloom));
		SettingsRegistry.register(new Entry("Cibles", DefaultCategories::targets));
		SettingsRegistry.register(new Entry("Survie", DefaultCategories::survival));
		SettingsRegistry.register(new Entry("Combat", DefaultCategories::combat));
		SettingsRegistry.register(new Entry("Caméra", DefaultCategories::camera));
		SettingsRegistry.register(new Entry("Affichage", DefaultCategories::display));
	}

	private record Entry(String title, Builder builder) implements SettingsCategory {
		@Override
		public void build(SettingsPage page, Framework framework) {
			builder.build(page, framework, ModConfig.get());
		}
	}

	private interface Builder {
		void build(SettingsPage page, Framework f, ModConfig c);
	}

	// ========================================
	// VOIDGLOOM
	// ========================================

	private static void voidgloom(SettingsPage p, Framework f, ModConfig c) {
		p.toggle("Module Voidgloom", () -> f.modules().isEnabled(VoidgloomModule.ID),
			() -> f.modules().toggle(f, VoidgloomModule.ID));
		p.toggle("Exiger Slayer", () -> c.requireSlayer, () -> c.requireSlayer = !c.requireSlayer);
		p.toggle("Seulement mon boss", () -> c.onlyOwnBoss, () -> c.onlyOwnBoss = !c.onlyOwnBoss);
		p.toggle("Sneak sur boss", () -> c.sneakOnBoss, () -> c.sneakOnBoss = !c.sneakOnBoss);
		p.toggle("Nom via ArmorStand", () -> c.allowArmorStandNameplate, () -> c.allowArmorStandNameplate = !c.allowArmorStandNameplate);
		p.toggle("Position au-dessus", () -> c.allowAbovePosition, () -> c.allowAbovePosition = !c.allowAbovePosition);
		p.stepper("Niveau", () -> c.voidgloomRequiredLevel, v -> c.voidgloomRequiredLevel = (int) v, 1, 0, 500);
		p.stepper("Essais", () -> c.maxRepositionAttempts, v -> c.maxRepositionAttempts = (int) v, 1, 1, 8);
		p.stepper("Rayon boss", () -> c.targetSearchRange, v -> c.targetSearchRange = v, 2, 4, 48);
	}

	// ========================================
	// CIBLES (farm des Enderman)
	// ========================================

	private static void targets(SettingsPage p, Framework f, ModConfig c) {
		p.section("Types de mobs à farmer");
		p.toggle("Enderman", () -> c.farmsMobType("enderman"), () -> c.setFarmMobType("enderman", !c.farmsMobType("enderman")));
		p.toggle("Zealot", () -> c.farmsMobType("zealot"), () -> c.setFarmMobType("zealot", !c.farmsMobType("zealot")));
		p.toggle("Voidling Fanatic", () -> c.farmsMobType("voidling fanatic"),
			() -> c.setFarmMobType("voidling fanatic", !c.farmsMobType("voidling fanatic")));
		p.toggle("Farmer (global)", () -> c.farmMobs, () -> c.farmMobs = !c.farmMobs);
		p.choice("Mobs perchés", List.of("Ignorer", "Accepter"),
			() -> c.acceptElevatedMobs ? 1 : 0, i -> c.acceptElevatedMobs = i == 1);
		p.toggle("Préférer à l'écran", () -> c.preferOnScreen, () -> c.preferOnScreen = !c.preferOnScreen);
		p.stepper("Rayon farm", () -> c.farmSearchRange, v -> c.farmSearchRange = v, 2, 8, 48);
		p.stepper("Contact (t)", () -> c.mobKillTimeoutTicks, v -> c.mobKillTimeoutTicks = (int) v, 10, 20, 400);
		p.stepper("Bloqué (t)", () -> c.stuckSkipTicks, v -> c.stuckSkipTicks = (int) v, 10, 20, 400);
		p.stepper("Progrès (t)", () -> c.noProgressTicks, v -> c.noProgressTicks = (int) v, 10, 20, 400);
	}

	// ========================================
	// SURVIE
	// ========================================

	private static void survival(SettingsPage p, Framework f, ModConfig c) {
		p.toggle("Soin (Wand)", () -> c.healEnabled, () -> c.healEnabled = !c.healEnabled);
		p.stepper("Seuil %", () -> c.healThresholdPercent, v -> c.healThresholdPercent = (float) v, 5, 5, 95);
		p.stepper("Délai", () -> c.wandCooldownTicks, v -> c.wandCooldownTicks = (int) v, 5, 5, 200);
		p.toggle("Orb (boss)", () -> c.orbEnabled, () -> c.orbEnabled = !c.orbEnabled);
		p.stepper("Orb min (t)", () -> c.orbMinReplaceTicks, v -> c.orbMinReplaceTicks = (int) v, 20, 20, 600);
		p.stepper("Orb durée", () -> c.orbAssumedDurationTicks, v -> c.orbAssumedDurationTicks = (int) v, 60, 100, 1200);
		p.info("Objets repérés par leur nom : \"" + c.healWandKeyword + "\", \"" + c.orbKeyword + "\", \"" + c.weaponKeyword + "\"");
	}

	// ========================================
	// COMBAT
	// ========================================

	private static void combat(SettingsPage p, Framework f, ModConfig c) {
		p.stepper("Portée", () -> c.attackDistance, v -> c.attackDistance = v, 0.25, 2, 6);
		p.stepper("Approche", () -> c.approachDistance,
			v -> c.approachDistance = Math.min(v, c.attackDistance - 0.1), 0.25, 1, 5.9);
		p.stepper("Dist. mini", () -> c.combatMinDistance, v -> c.combatMinDistance = v, 0.1, 0.8, 3);
		p.stepper("CPS min", () -> c.minCps, v -> { c.minCps = v; c.maxCps = Math.max(c.maxCps, v); }, 1, 1, 20);
		p.stepper("CPS max", () -> c.maxCps, v -> { c.maxCps = v; c.minCps = Math.min(c.minCps, v); }, 1, 1, 20);
		p.toggle("Pauses de clic", () -> c.attackPauses, () -> c.attackPauses = !c.attackPauses);
		p.toggle("Sprint", () -> c.useSprint, () -> c.useSprint = !c.useSprint);
		p.toggle("Clic droit katana", () -> c.katanaClick, () -> c.katanaClick = !c.katanaClick);
		p.stepper("Clic min (t)", () -> c.katanaClickMinTicks, v -> { c.katanaClickMinTicks = (int) v; c.katanaClickMaxTicks = Math.max(c.katanaClickMaxTicks, (int) v); }, 1, 20, 200);
		p.stepper("Clic max (t)", () -> c.katanaClickMaxTicks, v -> { c.katanaClickMaxTicks = (int) v; c.katanaClickMinTicks = Math.min(c.katanaClickMinTicks, (int) v); }, 1, 20, 200);
		p.toggle("Virages anticipés", () -> c.turnSlowdown, () -> c.turnSlowdown = !c.turnSlowdown);
	}

	// ========================================
	// CAMÉRA
	// ========================================

	private static void camera(SettingsPage p, Framework f, ModConfig c) {
		p.toggle("Humaniser", () -> c.humanize, () -> c.humanize = !c.humanize);
		p.toggle("Rotation par image", () -> c.smoothFrameRotation, () -> c.smoothFrameRotation = !c.smoothFrameRotation);
		p.toggle("Micro-mouvements", () -> c.camTremor, () -> c.camTremor = !c.camTremor);
		p.toggle("Coup d'oeil suivant", () -> c.glance, () -> c.glance = !c.glance);
		p.stepper("Vitesse max", () -> c.camPeakSpeedDeg, v -> c.camPeakSpeedDeg = (float) v, 2, 6, 40);
		p.stepper("Accél.", () -> c.camMaxAccelDeg, v -> c.camMaxAccelDeg = (float) v, 0.5, 2, 15);
		p.stepper("Amorti", () -> c.camDamping, v -> c.camDamping = (float) v, 0.05, 0.4, 1.0);
		p.stepper("Anticip.", () -> c.aimLeadTicks, v -> c.aimLeadTicks = v, 0.5, 0, 4);
		p.stepper("Visée bas", () -> c.aimBandLowFraction, v -> c.aimBandLowFraction = Math.min(v, c.aimBandHighFraction), 0.05, 0.1, 0.9);
		p.stepper("Visée haut", () -> c.aimBandHighFraction, v -> c.aimBandHighFraction = Math.max(v, c.aimBandLowFraction), 0.05, 0.2, 1.0);
	}

	// ========================================
	// AFFICHAGE
	// ========================================

	private static void display(SettingsPage p, Framework f, ModConfig c) {
		p.toggle("HUD en jeu", () -> c.hudEnabled, () -> c.hudEnabled = !c.hudEnabled);
		p.toggle("Debug (détails)", () -> c.debugMode, () -> c.debugMode = !c.debugMode);
		p.section("Touches");
		p.info("INSERT : ce menu     I : inspecter la cible");
		p.info("PAGE_UP : enregistrer la caméra (CSV)");
		p.info("END : arrêt d'urgence (coupe tous les modules)");
	}
}
