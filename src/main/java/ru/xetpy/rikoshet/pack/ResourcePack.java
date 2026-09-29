package ru.xetpy.rikoshet.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.core.Json5;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ресурспак мода через Polymer (docs/architecture/content-delivery.md#ресурспак): ассеты из
 * assets/rikoshet/ попадают в общий пак, autohost раздаёт его при входе через порт игры.
 * Пока в паке только скины NPC; без пака манекены выглядят как Стив.
 */
public final class ResourcePack {
	static final String AUTOHOST_CONFIG = "polymer/auto-host.json";

	private ResourcePack() {
	}

	/** Из onInitializeServer: до того, как Polymer соберёт пак. */
	public static void init(Logger log) {
		if (!FabricLoader.getInstance().isModLoaded("polymer-resource-pack")) {
			log.warn("[пак] Polymer не найден — скинов NPC не будет");
			return;
		}
		PolymerResourcePackUtils.addModAssets("rikoshet");
		PolymerResourcePackUtils.markAsRequired();
	}

	/**
	 * autohost на боевом сервере по умолчанию выключен (включён только в dev-среде). Выключен — пак
	 * никому не уходит: предупреждаем, что поправить. null — всё в порядке.
	 */
	public static String autohostProblem(Path configDir) {
		if (!FabricLoader.getInstance().isModLoaded("polymer-autohost")) {
			return null;
		}
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return null;
		}
		Path file = configDir.resolve(AUTOHOST_CONFIG);
		if (!Files.exists(file)) {
			return "нет " + AUTOHOST_CONFIG + ": ресурспак не раздаётся. Polymer создаст файл при первом запуске — поставь в нём enabled: true и перезапусти";
		}
		try {
			JsonElement root = Json5.parse(Files.readString(file));
			JsonElement enabled = root.isJsonObject() ? ((JsonObject) root).get("enabled") : null;
			if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsBoolean()) {
				return AUTOHOST_CONFIG + ": enabled false — ресурспак не раздаётся, у NPC не будет скинов. Поставь true и перезапусти";
			}
		} catch (IOException | RuntimeException e) {
			return AUTOHOST_CONFIG + " не прочитан: " + e.getMessage();
		}
		return null;
	}
}
