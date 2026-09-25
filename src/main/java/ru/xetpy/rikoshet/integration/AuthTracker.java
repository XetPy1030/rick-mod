package ru.xetpy.rikoshet.integration;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * «Настоящий» вход игрока — после пароля EasyAuth. Пока игрок в лимбо, его вход не
 * засчитывается, реплики он не видит. Опрос раз в секунду: событий у EasyAuth нет.
 * Всё — из главного потока.
 */
public final class AuthTracker {
	private final EasyAuthBridge easyAuth;
	private final Consumer<ServerPlayer> onAuthenticated;
	private final Map<UUID, ServerPlayer> pending = new ConcurrentHashMap<>();
	private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();

	public AuthTracker(EasyAuthBridge easyAuth, Consumer<ServerPlayer> onAuthenticated) {
		this.easyAuth = easyAuth;
		this.onAuthenticated = onAuthenticated;
	}

	public void onJoin(ServerPlayer player) {
		authenticated.remove(player.getUUID());
		if (easyAuth.isAuthenticated(player)) {
			authenticated.add(player.getUUID());
			onAuthenticated.accept(player);
		} else {
			pending.put(player.getUUID(), player);
		}
	}

	/** @return был ли игрок авторизован: выход без пароля не считается выходом */
	public boolean onLeave(ServerPlayer player) {
		pending.remove(player.getUUID());
		return authenticated.remove(player.getUUID());
	}

	/** Раз в секунду. */
	public void poll(MinecraftServer server) {
		if (pending.isEmpty()) {
			return;
		}
		for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || p.hasDisconnected()) {
				it.remove();
				continue;
			}
			if (easyAuth.isAuthenticated(p)) {
				it.remove();
				authenticated.add(p.getUUID());
				onAuthenticated.accept(p);
			}
		}
	}

	/** Только для /rickdev: фейковый игрок без EasyAuth. */
	public void devMarkAuthenticated(ServerPlayer player) {
		pending.remove(player.getUUID());
		authenticated.add(player.getUUID());
	}

	public boolean isAuthenticated(ServerPlayer player) {
		return authenticated.contains(player.getUUID());
	}

	public boolean easyAuthPresent() {
		return easyAuth.present();
	}
}
