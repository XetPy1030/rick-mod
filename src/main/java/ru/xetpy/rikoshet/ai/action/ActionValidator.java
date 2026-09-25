package ru.xetpy.rikoshet.ai.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.ai.TextFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Белый список действий ИИ: docs/architecture/ai-actions.md. Действие вне списка персонажа,
 * неизвестное или с параметрами вне пределов — отбрасывается целиком, а не обрезается.
 * Остальной ответ (реплика) при этом живёт.
 */
public final class ActionValidator {
	public static final int MAX_ACTIONS = 3;
	public static final int MAX_NOTE = 300;

	/** Все типы из контракта, включая ещё не реализованные: их отказ — «не разрешено», а не «неизвестно». */
	public static final Set<String> KNOWN = Set.of(
			"remember", "change_reputation", "give_quest", "complete_quest", "give_item", "take_item",
			"apply_effect", "set_scale", "play_sound", "grant_title", "spawn_mob", "teleport", "trade_offer", "leave");

	private ActionValidator() {
	}

	public record Outcome(List<AiAction> accepted, List<String> rejected) {
	}

	/**
	 * @param response ответ, уже прошедший схему маршрута
	 * @param allowed  действия, разрешённые персонажу
	 */
	public static Outcome validate(JsonObject response, Set<String> allowed, List<String> blocklist) {
		List<AiAction> accepted = new ArrayList<>();
		List<String> rejected = new ArrayList<>();
		Set<String> notes = new LinkedHashSet<>();

		JsonArray actions = response.has("actions") && response.get("actions").isJsonArray()
				? response.getAsJsonArray("actions") : new JsonArray();
		for (JsonElement el : actions) {
			if (!el.isJsonObject() || !el.getAsJsonObject().has("type") || !el.getAsJsonObject().get("type").isJsonPrimitive()) {
				rejected.add("действие без type");
				continue;
			}
			JsonObject a = el.getAsJsonObject();
			String type = a.get("type").getAsString();
			if (!KNOWN.contains(type)) {
				rejected.add(type + ": неизвестный тип");
				continue;
			}
			if (!allowed.contains(type)) {
				rejected.add(type + ": не разрешено");
				continue;
			}
			switch (type) {
				case "remember" -> remember(string(a, "note"), notes, accepted, rejected, blocklist);
				default -> rejected.add(type + ": не реализовано");
			}
		}
		// memory_note — то же, что remember; модели часто пишут одно и то же в оба места
		// разными словами, поэтому memory_note берём, только если remember в actions нет
		if (allowed.contains("remember") && notes.isEmpty()) {
			remember(string(response, "memory_note"), notes, accepted, rejected, blocklist);
		}
		if (accepted.size() > MAX_ACTIONS) {
			for (AiAction extra : accepted.subList(MAX_ACTIONS, accepted.size())) {
				rejected.add(name(extra) + ": больше " + MAX_ACTIONS + " действий");
			}
			accepted = new ArrayList<>(accepted.subList(0, MAX_ACTIONS));
		}
		return new Outcome(List.copyOf(accepted), List.copyOf(rejected));
	}

	private static void remember(String raw, Set<String> seen, List<AiAction> accepted, List<String> rejected, List<String> blocklist) {
		if (raw == null || raw.isBlank()) {
			return; // пустая заметка — обычное «нечего запоминать», не ошибка
		}
		TextFilter.Result clean = TextFilter.apply(raw, Integer.MAX_VALUE, blocklist);
		if (!clean.ok()) {
			rejected.add("remember: " + clean.reason());
			return;
		}
		String note = clean.text();
		if (note.length() > MAX_NOTE) {
			rejected.add("remember: заметка длиннее " + MAX_NOTE);
			return;
		}
		if (seen.add(note)) {
			accepted.add(new AiAction.Remember(note));
		}
	}

	private static String name(AiAction a) {
		return switch (a) {
			case AiAction.Remember r -> "remember";
		};
	}

	private static String string(JsonObject o, String key) {
		JsonElement v = o.get(key);
		return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
	}
}
