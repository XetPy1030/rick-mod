package ru.xetpy.rikoshet.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Ключ OpenRouter: переменная окружения OPENROUTER_API_KEY или rikoshet/secrets.json5.
 * Ключ не попадает ни в лог, ни в конфиг, ни в вывод команд.
 */
public final class Secrets {
	public static final String ENV = "OPENROUTER_API_KEY";
	public static final String FILE = "secrets.json5";

	private final String openRouterKey;
	private final String source;

	private Secrets(String openRouterKey, String source) {
		this.openRouterKey = openRouterKey;
		this.source = source;
	}

	public static Secrets load(Path dataDir, List<String> warnings) {
		String env = System.getenv(ENV);
		if (env != null && !env.isBlank()) {
			return new Secrets(env.strip(), "переменная окружения " + ENV);
		}
		Path file = dataDir.resolve(FILE);
		if (Files.exists(file)) {
			try {
				JsonElement root = Json5.parse(Files.readString(file, StandardCharsets.UTF_8));
				if (root.isJsonObject() && root.getAsJsonObject().has("openrouter_api_key")) {
					String key = root.getAsJsonObject().get("openrouter_api_key").getAsString().strip();
					if (!key.isEmpty()) {
						return new Secrets(key, "rikoshet/" + FILE);
					}
				}
				warnings.add("rikoshet/" + FILE + ": нет ключа openrouter_api_key");
			} catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException e) {
				warnings.add("rikoshet/" + FILE + " не читается: " + e.getClass().getSimpleName());
			}
		}
		return new Secrets(null, "нет");
	}

	public boolean hasKey() {
		return openRouterKey != null;
	}

	/** Только для заголовка Authorization. */
	public String openRouterKey() {
		return openRouterKey;
	}

	public String source() {
		return source;
	}

	@Override
	public String toString() {
		return hasKey() ? "ключ есть (" + source + ")" : "ключа нет";
	}
}
