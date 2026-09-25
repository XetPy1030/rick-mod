package ru.xetpy.rikoshet.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Загрузка config/rikoshet.json5. Нет файла — кладёт туда конфиг по умолчанию из ресурсов. */
public final class ConfigLoader {
	public static final String DEFAULT_RESOURCE = "/rikoshet/default-config.json5";

	private ConfigLoader() {
	}

	/** config == null, если конфиг не прочитан: тогда в силе остаётся прежний. */
	public record Result(RikoshetConfig config, List<String> warnings, List<String> errors) {
		public boolean ok() {
			return config != null && errors.isEmpty();
		}
	}

	public static Result load(Path file) {
		List<String> warnings = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		try {
			if (Files.notExists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, defaultText(), StandardCharsets.UTF_8);
				warnings.add("создан " + file + " с настройками по умолчанию");
			}
			return parse(Files.readString(file, StandardCharsets.UTF_8), warnings, errors);
		} catch (IOException e) {
			errors.add("не читается " + file + ": " + e.getMessage());
			return new Result(null, warnings, errors);
		}
	}

	static Result parse(String text, List<String> warnings, List<String> errors) {
		JsonElement root;
		try {
			root = Json5.parse(text);
		} catch (JsonParseException | IllegalStateException e) {
			errors.add("синтаксис: " + e.getMessage());
			return new Result(null, warnings, errors);
		}
		if (!root.isJsonObject()) {
			errors.add("в корне ожидался объект { … }");
			return new Result(null, warnings, errors);
		}
		RikoshetConfig config = RikoshetConfig.read(root.getAsJsonObject(), warnings, errors);
		return new Result(errors.isEmpty() ? config : null, warnings, errors);
	}

	public static String defaultText() throws IOException {
		try (InputStream in = ConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
			if (in == null) {
				throw new IOException("нет ресурса " + DEFAULT_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
