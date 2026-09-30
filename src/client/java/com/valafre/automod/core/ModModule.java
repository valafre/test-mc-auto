package com.valafre.automod.core;

/** Contrat d'un module (Voidgloom, autres Slayer, farming...). Les modules demandent des tâches, ils ne touchent jamais aux inputs. */
public interface ModModule {

	String id();

	boolean isEnabled();

	/** Priorité du module : ordre d'exécution (plus haut d'abord) et priorité de claim des inputs. */
	int getPriority();

	void onEnable(Framework framework);

	void onDisable(Framework framework);

	/** Appelé chaque tick tant que le module est actif et que les conditions de sécurité sont réunies. */
	void onTick(Framework framework);

	/** Arrêt d'urgence / changement de monde : revenir à un état propre sans désactiver le module. */
	void onSafetyStop(Framework framework);
}
