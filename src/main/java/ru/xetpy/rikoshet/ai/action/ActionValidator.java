package ru.xetpy.rikoshet.ai.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.ai.TextFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

	public static final int MAX_DELTA = 5;
	public static final int MAX_REASON = 120;

	public record Outcome(List<AiAction> accepted, List<String> rejected) {
	}

	/**
	 * Что модели можно в этом ответе.
	 *
	 * @param allowed действия, разрешённые персонажу
	 * @param quests  id квестов, которые можно выдать игроку сейчас
	 * @param active  id активных квестов игрока
	 * @param gifts   id записей таблицы наград, доступных как подарок, → пределы количества
	 */
	public record Scope(Set<String> allowed, Set<String> quests, Set<String> active, Map<String, Range> gifts) {
		public static Scope of(Set<String> allowed) {
			return new Scope(allowed, Set.of(), Set.of(), Map.of());
		}
	}

	public record Range(int min, int max) {
	}

	/** Только действия без мира: remember. */
	public static Outcome validate(JsonObject response, Set<String> allowed, List<String> blocklist) {
		return validate(response, Scope.of(allowed), blocklist);
	}

	/** @param response ответ, уже прошедший схему маршрута */
	public static Outcome validate(JsonObject response, Scope scope, List<String> blocklist) {
		Set<String> allowed = scope.allowed();
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
			if (!"remember".equals(type) && accepted.stream().anyMatch(x -> name(x).equals(type))) {
				rejected.add(type + ": второе такое действие в одном ответе");
				continue;
			}
			switch (type) {
				case "remember" -> remember(string(a, "note"), notes, accepted, rejected, blocklist);
				case "change_reputation" -> {
					Integer delta = integer(a, "delta");
					String reason = string(a, "reason");
					if (delta == null || delta == 0 || Math.abs(delta) > MAX_DELTA) {
						rejected.add("change_reputation: delta " + delta + " вне ±" + MAX_DELTA);
					} else {
						TextFilter.Result r = reason == null ? null : TextFilter.apply(reason, Integer.MAX_VALUE, blocklist);
						String clean = r != null && r.ok() && r.text().length() <= MAX_REASON ? r.text() : "";
						accepted.add(new AiAction.ChangeReputation(delta, clean));
					}
				}
				case "give_quest" -> {
					String q = string(a, "quest");
					if (q == null || !scope.quests().contains(q)) {
						rejected.add("give_quest: " + q + " нет среди доступных");
					} else {
						accepted.add(new AiAction.GiveQuest(q));
					}
				}
				case "complete_quest" -> {
					String q = string(a, "quest");
					if (q == null || !scope.active().contains(q)) {
						rejected.add("complete_quest: " + q + " не активен");
					} else {
						accepted.add(new AiAction.CompleteQuest(q));
					}
				}
				case "give_item" -> {
					String item = string(a, "item");
					Integer count = integer(a, "count");
					Range range = item == null ? null : scope.gifts().get(item);
					if (range == null) {
						rejected.add("give_item: " + item + " нет в таблице наград для игрока");
					} else if (count == null || count < range.min() || count > range.max()) {
						rejected.add("give_item: " + item + " количество " + count + " вне " + range.min() + "–" + range.max());
					} else {
						accepted.add(new AiAction.GiveItem(item, count));
					}
				}
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
			case AiAction.ChangeReputation c -> "change_reputation";
			case AiAction.GiveQuest g -> "give_quest";
			case AiAction.CompleteQuest c -> "complete_quest";
			case AiAction.GiveItem g -> "give_item";
		};
	}

	private static Integer integer(JsonObject o, String key) {
		JsonElement v = o.get(key);
		if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		double d = v.getAsDouble();
		return d == Math.rint(d) && Math.abs(d) < 1_000_000 ? (int) d : null;
	}

	private static String string(JsonObject o, String key) {
		JsonElement v = o.get(key);
		return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
	}
}
