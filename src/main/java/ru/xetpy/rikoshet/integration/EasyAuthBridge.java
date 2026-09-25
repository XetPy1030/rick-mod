package ru.xetpy.rikoshet.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Мягкая интеграция с EasyAuth: авторизован ли игрок. Через рефлексию, без зависимости
 * при сборке. Нет мода или API поменялся — все считаются авторизованными, и мод работает
 * как без EasyAuth (docs/architecture/server-integration.md).
 */
public final class EasyAuthBridge {
	private static final String IFACE = "xyz.nikitacartes.easyauth.interfaces.PlayerAuth";
	private static final String METHOD = "easyAuth$isAuthenticated";

	private final Class<?> iface;
	private final Method isAuthenticated;
	private final Logger log;
	private boolean broken;

	private EasyAuthBridge(Class<?> iface, Method isAuthenticated, Logger log) {
		this.iface = iface;
		this.isAuthenticated = isAuthenticated;
		this.log = log;
	}

	public static EasyAuthBridge create(Logger log) {
		if (!FabricLoader.getInstance().isModLoaded("easyauth")) {
			return new EasyAuthBridge(null, null, log);
		}
		try {
			Class<?> c = Class.forName(IFACE);
			Method m = c.getMethod(METHOD);
			log.info("EasyAuth найден: вход засчитывается после пароля");
			return new EasyAuthBridge(c, m, log);
		} catch (ReflectiveOperationException | LinkageError e) {
			log.warn("EasyAuth есть, но его API не найден ({}); вход засчитывается сразу", e.toString());
			return new EasyAuthBridge(null, null, log);
		}
	}

	public boolean present() {
		return iface != null && !broken;
	}

	public boolean isAuthenticated(ServerPlayer player) {
		if (!present() || !iface.isInstance(player)) {
			return true;
		}
		try {
			return (boolean) isAuthenticated.invoke(player);
		} catch (ReflectiveOperationException | RuntimeException e) {
			broken = true;
			log.warn("EasyAuth: вызов {} упал ({}); дальше вход засчитывается сразу", METHOD, e.toString());
			return true;
		}
	}
}
