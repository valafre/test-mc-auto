package com.valafre.automod.core;

/** Machine à états minimale et générique avec compteur de ticks dans l'état courant. */
public final class StateMachine<S extends Enum<S>> {

	private final String tag;
	private S current;
	private int ticksInState;

	public StateMachine(String tag, S initial) {
		this.tag = tag;
		this.current = initial;
	}

	public S current() {
		return current;
	}

	public int ticksInState() {
		return ticksInState;
	}

	public boolean is(S state) {
		return current == state;
	}

	public void tick() {
		ticksInState++;
	}

	public void transition(S next) {
		if (next == current) {
			return;
		}
		S previous = current;
		current = next;
		ticksInState = 0;
		Debug.log(tag, () -> "État " + previous + " -> " + next);
	}
}
