package ru.xetpy.rikoshet.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Чтение раздела конфига со значениями по умолчанию. Запоминает прочитанные ключи,
 * чтобы потом предупредить о неизвестных, и копит ошибки типов вместо исключений.
 */
final class ConfigReader {
	private final JsonObject obj;
	private final String path;
	private final List<String> warnings;
	private final List<String> errors;
	private final Set<String> seen = new HashSet<>();

	ConfigReader(JsonObject obj, String path, List<String> warnings, List<String> errors) {
		this.obj = obj == null ? new JsonObject() : obj;
		this.path = path;
		this.warnings = warnings;
		this.errors = errors;
	}

	private JsonElement get(String key) {
		seen.add(key);
		JsonElement e = obj.get(key);
		return e == null || e.isJsonNull() ? null : e;
	}

	private String where(String key) {
		return path.isEmpty() ? key : path + "." + key;
	}

	boolean bool(String key, boolean def) {
		JsonElement e = get(key);
		if (e == null) {
			return def;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
			return e.getAsBoolean();
		}
		errors.add(where(key) + ": ожидалось true или false");
		return def;
	}

	double number(String key, double def, double min, double max) {
		JsonElement e = get(key);
		if (e == null) {
			return def;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			double v = e.getAsDouble();
			if (v < min || v > max) {
				errors.add(where(key) + ": " + v + " вне диапазона " + min + "…" + max);
				return def;
			}
			return v;
		}
		errors.add(where(key) + ": ожидалось число");
		return def;
	}

	int integer(String key, int def, int min, int max) {
		return (int) Math.round(number(key, def, min, max));
	}

	String string(String key, String def) {
		JsonElement e = get(key);
		if (e == null) {
			return def;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
			return e.getAsString();
		}
		errors.add(where(key) + ": ожидалась строка");
		return def;
	}

	List<String> strings(String key, List<String> def) {
		JsonElement e = get(key);
		if (e == null) {
			return def;
		}
		if (!e.isJsonArray()) {
			errors.add(where(key) + ": ожидался список строк");
			return def;
		}
		List<String> out = new ArrayList<>();
		for (JsonElement x : (JsonArray) e) {
			if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) {
				out.add(x.getAsString());
			} else {
				errors.add(where(key) + ": в списке не строка");
			}
		}
		return List.copyOf(out);
	}

	ConfigReader section(String key) {
		JsonElement e = get(key);
		if (e != null && !e.isJsonObject()) {
			errors.add(where(key) + ": ожидался объект");
			e = null;
		}
		return new ConfigReader(e == null ? null : e.getAsJsonObject(), where(key), warnings, errors);
	}

	Set<String> keys() {
		return obj.keySet();
	}

	/** Отмечает ключи, которые знаем, но пока не читаем (разделы будущих этапов). */
	void reserve(String... keys) {
		seen.addAll(List.of(keys));
	}

	void finish() {
		for (String key : obj.keySet()) {
			if (!seen.contains(key)) {
				warnings.add("неизвестный ключ " + where(key));
			}
		}
	}
}
