package ru.xetpy.rikoshet.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;

/**
 * Минимальный JSON5 поверх Gson: комментарии, висячие запятые, ключи без кавычек, одинарные кавычки.
 * Комментарии и висячие запятые вырезаются здесь, остальное понимает Gson в нестрогом режиме.
 */
public final class Json5 {
	private Json5() {
	}

	public static JsonElement parse(String json5) {
		JsonReader reader = new JsonReader(new StringReader(strip(json5)));
		reader.setStrictness(Strictness.LENIENT);
		return JsonParser.parseReader(reader);
	}

	/** Убирает комментарии и запятые перед } и ], не трогая строки. */
	static String strip(String src) {
		StringBuilder out = new StringBuilder(src.length());
		int n = src.length();
		int i = 0;
		while (i < n) {
			char c = src.charAt(i);
			if (c == '"' || c == '\'') {
				int end = skipString(src, i);
				out.append(src, i, end);
				i = end;
			} else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
				while (i < n && src.charAt(i) != '\n') {
					i++;
				}
			} else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				int end = src.indexOf("*/", i + 2);
				i = end < 0 ? n : end + 2;
				out.append(' ');
			} else if (c == ',' && closerFollows(src, i + 1)) {
				i++;
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	private static int skipString(String src, int start) {
		char quote = src.charAt(start);
		int i = start + 1;
		while (i < src.length()) {
			char c = src.charAt(i);
			if (c == '\\') {
				i += 2;
			} else if (c == quote) {
				return i + 1;
			} else {
				i++;
			}
		}
		return src.length();
	}

	/** Идёт ли дальше } или ], с пропуском пробелов и комментариев. */
	private static boolean closerFollows(String src, int from) {
		int i = from;
		int n = src.length();
		while (i < n) {
			char c = src.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
			} else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
				while (i < n && src.charAt(i) != '\n') {
					i++;
				}
			} else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				int end = src.indexOf("*/", i + 2);
				i = end < 0 ? n : end + 2;
			} else {
				return c == '}' || c == ']';
			}
		}
		return false;
	}
}
