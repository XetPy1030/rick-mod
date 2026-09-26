package ru.xetpy.rikoshet.memory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.ai.TextFilter;
import ru.xetpy.rikoshet.chronicle.analysis.DayReport;
import ru.xetpy.rikoshet.chronicle.analysis.Metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Дневник дня: дешёвая модель пишет по 1–2 фразы на игрока одним запросом
 * (docs/architecture/memory.md#консолидация). Здесь — вход и разбор ответа, без сети.
 */
public final class Diary {
	static final int MAX = 250;

	private Diary() {
	}

	/** Игроки дня под номерами с занятиями, числами, смертями, соседями и фактами о них. */
	public static String input(DayReport r) {
		StringBuilder sb = new StringBuilder("День ").append(r.day()).append(", ").append(r.weekday()).append(".\n");
		int i = 1;
		for (DayReport.PlayerDay p : r.players()) {
			sb.append('\n').append(i++).append(". ").append(p.who()).append(": в игре ").append(Metrics.duration(p.online()));
			if (!p.activities().isEmpty()) {
				List<String> a = new ArrayList<>();
				p.activities().forEach((k, v) -> a.add(k + " " + Metrics.duration(v)));
				sb.append("; занятия: ").append(String.join(", ", a));
			}
			if (!p.numbers().isEmpty()) {
				List<String> n = new ArrayList<>();
				p.numbers().forEach((k, v) -> n.add(k + " " + v));
				sb.append("; ").append(String.join(", ", n));
			}
			if (!p.deaths().isEmpty()) {
				sb.append("; смерти: ").append(String.join(", ", p.deaths()));
			}
			if (!p.partners().isEmpty()) {
				sb.append("; рядом: ").append(String.join(", ", p.partners()));
			}
			for (DayReport.Fact f : r.facts()) {
				if (p.uuid().equals(f.uuid())) {
					sb.append("\n   - ").append(f.text());
				}
			}
		}
		return sb.toString();
	}

	/** Записи по игрокам: номер → UUID; пустые, чужие номера и не прошедшие фильтр — мимо. */
	public static Map<UUID, String> parse(JsonObject v, DayReport r, List<String> blocklist) {
		Map<UUID, String> out = new LinkedHashMap<>();
		if (v == null || !v.has("entries") || !v.get("entries").isJsonArray()) {
			return out;
		}
		for (JsonElement el : v.getAsJsonArray("entries")) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject e = el.getAsJsonObject();
			if (!e.has("player") || !e.has("text") || !e.get("player").getAsJsonPrimitive().isNumber()) {
				continue;
			}
			int n = e.get("player").getAsInt();
			if (n < 1 || n > r.players().size()) {
				continue;
			}
			TextFilter.Result f = TextFilter.apply(e.get("text").getAsString(), MAX, blocklist);
			if (f.ok() && !f.text().isBlank()) {
				out.putIfAbsent(UUID.fromString(r.players().get(n - 1).uuid()), f.text());
			}
		}
		return out;
	}
}
