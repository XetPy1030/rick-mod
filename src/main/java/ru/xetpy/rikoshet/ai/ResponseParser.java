package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбор ответа модели: JSON, возможно в ```-блоке или с текстом вокруг, плюс проверка схемы. */
public final class ResponseParser {
	private static final Pattern FENCE = Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```$", Pattern.DOTALL);

	private ResponseParser() {
	}

	public record Parsed(JsonObject value, List<String> errors) {
		public boolean ok() {
			return value != null && errors.isEmpty();
		}
	}

	public static Parsed parse(String content, JsonObject schema) {
		if (content == null || content.isBlank()) {
			return new Parsed(null, List.of("пустой ответ"));
		}
		String s = content.strip();
		Matcher m = FENCE.matcher(s);
		if (m.matches()) {
			s = m.group(1);
		}
		JsonElement root = tryParse(s);
		if (root == null) {
			int a = s.indexOf('{');
			int b = s.lastIndexOf('}');
			if (a >= 0 && b > a) {
				root = tryParse(s.substring(a, b + 1));
			}
		}
		if (root == null || !root.isJsonObject()) {
			return new Parsed(null, List.of("не JSON-объект"));
		}
		List<String> errors = SchemaValidator.validate(root, schema);
		return new Parsed(root.getAsJsonObject(), errors);
	}

	private static JsonElement tryParse(String s) {
		try {
			return JsonParser.parseString(s);
		} catch (JsonParseException | IllegalStateException e) {
			return null;
		}
	}
}
