package com.valafre.automod.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Point unique d'écriture des touches de déplacement.
 *
 * <p>Modèle "intention par tick" : chaque tick, les contrôleurs DEMANDENT des touches ({@link #request}) ;
 * en fin de tick, {@link #endTick} applique la différence. Une touche non redemandée est donc relâchée
 * automatiquement : impossible de laisser une touche bloquée si une tâche s'arrête.
 *
 * <p>Un seul propriétaire à la fois ({@link #claim}) : les demandes des autres sont ignorées.
 */
public final class InputController {

	public enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, SPRINT, SNEAK }

	private static final int KEY_COUNT = Key.values().length;

	private final boolean[] intent = new boolean[KEY_COUNT];
	private final boolean[] applied = new boolean[KEY_COUNT];
	private String owner;
	private int ownerPriority;

	// ========================================
	// PROPRIÉTÉ
	// ========================================

	/** Prend le contrôle si libre, déjà à soi, ou si la priorité est supérieure ou égale à celle du propriétaire. */
	public boolean claim(String newOwner, int priority) {
		if (owner == null || owner.equals(newOwner) || priority >= ownerPriority) {
			owner = newOwner;
			ownerPriority = priority;
			return true;
		}
		return false;
	}

	public void release(String releasingOwner) {
		if (releasingOwner != null && releasingOwner.equals(owner)) {
			clearClaim();
		}
	}

	public void clearClaim() {
		owner = null;
		ownerPriority = 0;
	}

	/** Touches actuellement pressées par ce contrôleur, ex. "FORWARD SPRINT" (diagnostic). */
	public String describeApplied() {
		StringBuilder sb = new StringBuilder();
		for (Key key : Key.values()) {
			if (applied[key.ordinal()]) {
				sb.append(key.name()).append(' ');
			}
		}
		return sb.isEmpty() ? "aucune" : sb.toString().trim();
	}

	public String owner() {
		return owner;
	}

	// ========================================
	// INTENTIONS
	// ========================================

	public void beginTick() {
		java.util.Arrays.fill(intent, false);
	}

	/** @return false si l'appelant n'est pas le propriétaire courant (demande ignorée). */
	public boolean request(String requester, Key key, boolean down) {
		if (requester == null || !requester.equals(owner)) {
			if (com.valafre.automod.core.Debug.enabled()) {
				com.valafre.automod.debug.CombatTrace.inputRejected(String.valueOf(requester), key.name());
			}
			return false;
		}
		intent[key.ordinal()] = down;
		return true;
	}

	/** Applique les intentions aux vraies touches, uniquement quand l'état change (pas de setDown redondant). */
	public void endTick(Minecraft mc) {
		Options options = mc.options;
		// Accroupi : pas de sprint possible ; et en mode "sneak en bascule" un setDown(true/false) inverserait l'état, on s'abstient.
		if (intent[Key.SNEAK.ordinal()]) {
			intent[Key.SPRINT.ordinal()] = false;
			if (options.toggleCrouch().get()) {
				intent[Key.SNEAK.ordinal()] = false;
			}
		}
		for (Key key : Key.values()) {
			int i = key.ordinal();
			if (intent[i] != applied[i]) {
				mapping(options, key).setDown(intent[i]);
				applied[i] = intent[i];
			}
		}
	}

	/** Relâche immédiatement toutes les touches que ce contrôleur a pressées. */
	public void releaseAll(Minecraft mc) {
		java.util.Arrays.fill(intent, false);
		if (mc != null && mc.options != null) {
			endTick(mc);
		}
		java.util.Arrays.fill(applied, false);
	}

	private static KeyMapping mapping(Options options, Key key) {
		return switch (key) {
			case FORWARD -> options.keyUp;
			case BACK -> options.keyDown;
			case LEFT -> options.keyLeft;
			case RIGHT -> options.keyRight;
			case JUMP -> options.keyJump;
			case SPRINT -> options.keySprint;
			case SNEAK -> options.keyShift;
		};
	}
}
