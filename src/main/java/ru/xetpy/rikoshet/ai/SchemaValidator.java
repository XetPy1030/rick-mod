package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Проверка ответа по JSON-схеме маршрута. Подмножество JSON Schema, которым пользуются
 * наши схемы: type (в том числе списком), enum, properties, required,
 * additionalProperties: false, items, minItems, maxItems.
 */
public final class SchemaValidator {
	private SchemaValidator() {
	}

	public static List<String> validate(JsonElement value, JsonObject schema) {
		List<String> errors = new ArrayList<>();
		check(value, schema, "$", errors);
		return errors;
	}

	private static void check(JsonElement v, JsonObject schema, String path, List<String> errors) {
		JsonElement type = schema.get("type");
		if (type != null && !typeMatches(v, type)) {
			errors.add(path + ": ожидался " + type + ", пришло " + describe(v));
			return;
		}
		JsonElement en = schema.get("enum");
		if (en != null && en.isJsonArray() && !en.getAsJsonArray().contains(v == null ? com.google.gson.JsonNull.INSTANCE : v)) {
			errors.add(path + ": " + v + " не из " + en);
		}
		if (v != null && v.isJsonObject()) {
			JsonObject obj = v.getAsJsonObject();
			JsonObject props = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
			if (schema.has("required")) {
				for (JsonElement r : schema.getAsJsonArray("required")) {
					if (!obj.has(r.getAsString())) {
						errors.add(path + ": нет поля " + r.getAsString());
					}
				}
			}
			boolean closed = schema.has("additionalProperties") && !schema.get("additionalProperties").getAsBoolean();
			for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
				if (props.has(e.getKey())) {
					check(e.getValue(), props.getAsJsonObject(e.getKey()), path + "." + e.getKey(), errors);
				} else if (closed) {
					errors.add(path + ": лишнее поле " + e.getKey());
				}
			}
		}
		if (v != null && v.isJsonArray()) {
			JsonArray arr = v.getAsJsonArray();
			if (schema.has("minItems") && arr.size() < schema.get("minItems").getAsInt()) {
				errors.add(path + ": элементов " + arr.size() + " < " + schema.get("minItems"));
			}
			if (schema.has("maxItems") && arr.size() > schema.get("maxItems").getAsInt()) {
				errors.add(path + ": элементов " + arr.size() + " > " + schema.get("maxItems"));
			}
			if (schema.has("items")) {
				JsonObject items = schema.getAsJsonObject("items");
				for (int i = 0; i < arr.size(); i++) {
					check(arr.get(i), items, path + "[" + i + "]", errors);
				}
			}
		}
	}

	private static boolean typeMatches(JsonElement v, JsonElement type) {
		if (type.isJsonArray()) {
			for (JsonElement t : type.getAsJsonArray()) {
				if (is(v, t.getAsString())) {
					return true;
				}
			}
			return false;
		}
		return is(v, type.getAsString());
	}

	private static boolean is(JsonElement v, String type) {
		boolean isNull = v == null || v.isJsonNull();
		return switch (type) {
			case "null" -> isNull;
			case "object" -> !isNull && v.isJsonObject();
			case "array" -> !isNull && v.isJsonArray();
			case "string" -> !isNull && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
			case "boolean" -> !isNull && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
			case "number" -> !isNull && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
			case "integer" -> !isNull && v.isJsonPrimitive() && isInteger(v.getAsJsonPrimitive());
			default -> false;
		};
	}

	static boolean isInteger(JsonPrimitive p) {
		if (!p.isNumber()) {
			return false;
		}
		try {
			return p.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static String describe(JsonElement v) {
		if (v == null || v.isJsonNull()) {
			return "null";
		}
		if (v.isJsonObject()) {
			return "object";
		}
		if (v.isJsonArray()) {
			return "array";
		}
		JsonPrimitive p = v.getAsJsonPrimitive();
		return p.isString() ? "string" : p.isBoolean() ? "boolean" : "number";
	}
}
