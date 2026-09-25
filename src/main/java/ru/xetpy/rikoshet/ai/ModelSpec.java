package ru.xetpy.rikoshet.ai;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Модель OpenRouter с необязательным уровнем размышлений: {@code vendor/model@effort}.
 * Тот же формат, что в tools/casting/models.json.
 */
public record ModelSpec(String id, String effort) {
	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*/[a-zA-Z0-9._:-]+");
	private static final List<String> EFFORTS = List.of("none", "minimal", "low", "medium", "high", "default");

	/** Разбирает строку; при ошибке пишет её в errors (если он есть) и возвращает null. */
	public static ModelSpec parse(String spec, List<String> errors) {
		String s = spec.strip();
		int at = s.indexOf('@');
		String id = at < 0 ? s : s.substring(0, at);
		String effort = at < 0 ? null : s.substring(at + 1);
		if (!ID.matcher(id).matches() || (effort != null && !EFFORTS.contains(effort))) {
			if (errors != null) {
				errors.add("модель «" + spec + "»: ожидалось vendor/model или vendor/model@effort");
			}
			return null;
		}
		return new ModelSpec(id, effort);
	}

	/** Уровень размышлений для запроса: свой у модели или маршрута. */
	public String effortOr(String routeEffort) {
		return effort != null ? effort : routeEffort;
	}

	@Override
	public String toString() {
		return effort == null ? id : id + "@" + effort;
	}
}
