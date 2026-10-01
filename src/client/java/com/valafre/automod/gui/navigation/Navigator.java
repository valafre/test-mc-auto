package com.valafre.automod.gui.navigation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** Pile de navigation : aller à une page, revenir en arrière. L'écran s'abonne pour reconstruire la page affichée. */
public final class Navigator {

	/** Dernière page ouverte, restaurée à la prochaine ouverture du GUI. */
	private static Route lastRoute = Route.of(Route.Kind.HOME);

	private final Deque<Route> back = new ArrayDeque<>();
	private Route current = lastRoute;
	private Consumer<Route> listener = r -> { };

	public void onChange(Consumer<Route> listener) {
		this.listener = listener;
	}

	public Route current() {
		return current;
	}

	public void go(Route route) {
		if (route.equals(current)) {
			return;
		}
		back.push(current);
		current = route;
		lastRoute = route;
		listener.accept(route);
	}

	public boolean canGoBack() {
		return !back.isEmpty();
	}

	public void back() {
		if (!back.isEmpty()) {
			current = back.pop();
			lastRoute = current;
			listener.accept(current);
		}
	}
}
