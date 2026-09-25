package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Тексты промптов и JSON-схемы ответов. Промпт можно переопределить файлом
 * {@code <сервер>/rikoshet/prompts/<путь>} — он важнее ресурса мода. Схемы не переопределяются:
 * это контракт с кодом. Кеш сбрасывается на /rickadmin reload.
 */
public final class PromptLibrary {
	private static final String PROMPTS = "/rikoshet/prompts/";
	private static final String SCHEMAS = "/rikoshet/schemas/";

	private final Path overrideDir;
	private final Map<String, String> texts = new ConcurrentHashMap<>();
	private final Map<String, JsonObject> schemas = new ConcurrentHashMap<>();

	public PromptLibrary(Path overrideDir) {
		this.overrideDir = overrideDir;
	}

	/** Текст промпта по относительному пути: {@code rules.md}, {@code tasks/death.md}. */
	public String text(String path) {
		return texts.computeIfAbsent(path, this::load);
	}

	public JsonObject schema(String name) {
		return schemas.computeIfAbsent(name, n -> JsonParser.parseString(resource(SCHEMAS + n + ".json")).getAsJsonObject());
	}

	public void clear() {
		texts.clear();
	}

	/** Какие промпты сейчас переопределены файлами на сервере. */
	public List<String> overridden() {
		List<String> out = new ArrayList<>();
		if (overrideDir == null || !Files.isDirectory(overrideDir)) {
			return out;
		}
		try (var files = Files.walk(overrideDir)) {
			files.filter(Files::isRegularFile)
					.map(p -> overrideDir.relativize(p).toString().replace('\\', '/'))
					.sorted()
					.forEach(out::add);
		} catch (IOException e) {
			out.add("(не читается: " + e.getMessage() + ")");
		}
		return out;
	}

	private String load(String path) {
		if (overrideDir != null) {
			Path file = overrideDir.resolve(path).normalize();
			if (file.startsWith(overrideDir) && Files.isRegularFile(file)) {
				try {
					return Files.readString(file, StandardCharsets.UTF_8).strip();
				} catch (IOException e) {
					throw new UncheckedIOException("промпт " + file, e);
				}
			}
		}
		return resource(PROMPTS + path).strip();
	}

	private static String resource(String name) {
		try (InputStream in = PromptLibrary.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalArgumentException("нет ресурса " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(name, e);
		}
	}
}
