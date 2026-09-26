package ru.xetpy.rikoshet.newspaper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.ai.TextFilter;
import ru.xetpy.rikoshet.chronicle.analysis.DayReport;
import ru.xetpy.rikoshet.chronicle.analysis.Metrics;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Редакционная кухня без сети: что дать аналитику, как собрать пакет для главного редактора
 * из брифа, как проверить выпуск и как собрать выпуск из фактов без ИИ.
 * Правило каскада: факты в пакете — дословно, от аналитика — только выбор и подача
 * (docs/architecture/ai-integration.md#каскад-моделей).
 */
public final class Editorial {
	static final int HEADLINE = 80;
	static final int TITLE = 60;
	static final int BODY = 400;
	static final int AD = 200;
	static final int WEATHER = 150;
	static final int FORECAST = 250;
	static final int ANGLE = 200;
	static final int NOTE = 300;

	private Editorial() {
	}

	/** Что дополнительно знает редакция, кроме итогов дня. */
	public record Extras(List<String> stories, List<String> changes, List<String> headlines) {
	}

	// ---------- аналитик ----------

	/** Вход аналитика: вся сводка дня, факты пронумерованы с 1. */
	public static String briefInput(DayReport r, Extras x) {
		return ru.xetpy.rikoshet.chronicle.analysis.DigestWriter.write(r, merge(x.stories(), x.changes()), x.headlines(), Integer.MAX_VALUE);
	}

	/** Заметка плана: рубрика, подача, факты дословно. */
	public record Story(String rubric, String angle, List<String> facts) {
	}

	public record Brief(String lead, List<Story> stories, String note) {
	}

	/**
	 * Разобрать бриф: номера фактов — в тексты фактов. Неверные номера отбрасываются; заметка
	 * без фактов — тоже. null — бриф непригоден, газета пойдёт по обычной сводке.
	 */
	public static Brief brief(JsonObject v, List<DayReport.Fact> facts) {
		if (v == null || facts.isEmpty()) {
			return null;
		}
		String lead = fact(facts, v.get("lead"));
		List<Story> stories = new ArrayList<>();
		Set<String> used = new LinkedHashSet<>();
		JsonArray arr = v.has("stories") && v.get("stories").isJsonArray() ? v.getAsJsonArray("stories") : new JsonArray();
		for (JsonElement el : arr) {
			if (!el.isJsonObject() || stories.size() >= 6) {
				continue;
			}
			JsonObject s = el.getAsJsonObject();
			List<String> texts = new ArrayList<>();
			if (s.has("facts") && s.get("facts").isJsonArray()) {
				for (JsonElement n : s.getAsJsonArray("facts")) {
					String t = fact(facts, n);
					if (t != null && texts.size() < 4 && used.add(t)) {
						texts.add(t);
					}
				}
			}
			if (texts.isEmpty()) {
				continue;
			}
			stories.add(new Story(clip(str(s, "rubric"), 40), clip(str(s, "angle"), ANGLE), texts));
		}
		if (stories.size() < 2) {
			return null;
		}
		return new Brief(lead, stories, clip(str(v, "note"), NOTE));
	}

	private static String fact(List<DayReport.Fact> facts, JsonElement n) {
		if (n == null || !n.isJsonPrimitive() || !n.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		int i = n.getAsInt();
		return i >= 1 && i <= facts.size() ? facts.get(i - 1).text() : null;
	}

	/** Пакет главному редактору по брифу: сводка, главная тема, план заметок с фактами, истории, прогнозы. */
	public static String packageFrom(Brief b, DayReport r, Extras x) {
		StringBuilder sb = new StringBuilder();
		String digest = ru.xetpy.rikoshet.chronicle.analysis.DigestWriter.write(r, List.of(), List.of(), 0);
		// Из сводки — только шапка и сервер: игроков и факты заменяет план
		int cut = digest.indexOf("\n\nИгроки:");
		sb.append(cut > 0 ? digest.substring(0, cut) : digest.lines().limit(2).reduce((a, c) -> a + "\n" + c).orElse(digest));
		if (b.lead() != null) {
			sb.append("\n\nГлавная тема: ").append(b.lead());
		}
		sb.append("\n\nПлан номера от выпускающего редактора (факты — дословно из летописи):");
		int i = 1;
		for (Story s : b.stories()) {
			sb.append('\n').append(i++).append(". ").append(s.rubric().isBlank() ? "Заметка" : s.rubric());
			if (!s.angle().isBlank()) {
				sb.append(" — подача: ").append(s.angle());
			}
			for (String f : s.facts()) {
				sb.append("\n   - ").append(f);
			}
		}
		appendExtras(sb, r, x);
		if (b.note() != null && !b.note().isBlank()) {
			sb.append("\n\nЗаметка выпускающего: ").append(b.note());
		}
		return sb.toString();
	}

	/** Без брифа: обычная сводка с max фактами. */
	public static String packagePlain(DayReport r, Extras x, int maxFacts) {
		return ru.xetpy.rikoshet.chronicle.analysis.DigestWriter.write(r, merge(x.stories(), x.changes()), x.headlines(), maxFacts);
	}

	private static void appendExtras(StringBuilder sb, DayReport r, Extras x) {
		List<String> stories = merge(x.stories(), x.changes());
		if (!stories.isEmpty()) {
			sb.append("\n\nПродолжающиеся истории и перемены:");
			stories.forEach(s -> sb.append("\n- ").append(s));
		}
		if (!r.forecasts().isEmpty()) {
			sb.append("\n\nПрогнозы (расчёт по статистике, а не факт):");
			r.forecasts().forEach(f -> sb.append("\n- ").append(f.text()));
		}
		if (!x.headlines().isEmpty()) {
			sb.append("\n\nЗаголовки прошлых выпусков — не повторяй:");
			x.headlines().forEach(h -> sb.append("\n- ").append(h));
		}
	}

	private static List<String> merge(List<String> a, List<String> b) {
		List<String> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	// ---------- проверка выпуска ----------

	/**
	 * Выпуск из ответа модели: длины обрезаются по концу фразы, заметки со ссылками, стоп-словами
	 * или с ником игрока с /rick off выбрасываются. null — меньше двух годных заметок.
	 */
	public static Issue check(JsonObject v, String day, List<String> blocklist, Set<String> hiddenNicks, String model, double cost) {
		String headline = clean(str(v, "headline"), HEADLINE, blocklist, hiddenNicks);
		List<Issue.Article> articles = new ArrayList<>();
		if (v.has("articles") && v.get("articles").isJsonArray()) {
			for (JsonElement el : v.getAsJsonArray("articles")) {
				if (!el.isJsonObject() || articles.size() >= 5) {
					continue;
				}
				String t = clean(str(el.getAsJsonObject(), "title"), TITLE, blocklist, hiddenNicks);
				String b = clean(str(el.getAsJsonObject(), "body"), BODY, blocklist, hiddenNicks);
				if (t != null && b != null) {
					articles.add(new Issue.Article(t, b));
				}
			}
		}
		if (headline == null || articles.size() < 2) {
			return null;
		}
		return new Issue(day, headline, articles, clean(str(v, "ad"), AD, blocklist, hiddenNicks),
				clean(str(v, "weather"), WEATHER, blocklist, hiddenNicks), clean(str(v, "forecast"), FORECAST, blocklist, hiddenNicks),
				"ai", model, cost, 0);
	}

	private static String clean(String s, int max, List<String> blocklist, Set<String> hidden) {
		if (s == null || s.isBlank()) {
			return null;
		}
		String low = s.toLowerCase(Locale.ROOT);
		for (String n : hidden) {
			if (low.contains(n.toLowerCase(Locale.ROOT))) {
				return null;
			}
		}
		TextFilter.Result f = TextFilter.apply(s, max, blocklist);
		return f.ok() ? f.text() : null;
	}

	// ---------- выпуск без ИИ ----------

	/** Рубрика по детектору факта. */
	static final Map<String, String> RUBRICS = Map.ofEntries(
			Map.entry("record", "Рекорд"), Map.entry("personal_best", "Личный рекорд"),
			Map.entry("death", "Происшествия"), Map.entry("series", "Происшествия"), Map.entry("nemesis", "Немезида"),
			Map.entry("first", "Открытия"), Map.entry("explorer", "Открытия"), Map.entry("pioneer", "Открытия"),
			Map.entry("milestone", "Вехи"), Map.entry("advancement", "Достижения"), Map.entry("boss", "Битвы"),
			Map.entry("new_friends", "Светская хроника"), Map.entry("cooling", "Светская хроника"),
			Map.entry("quarrel", "Светская хроника"), Map.entry("pair_of_day", "Светская хроника"),
			Map.entry("between", "Светская хроника"), Map.entry("loner", "Светская хроника"), Map.entry("company", "Светская хроника"),
			Map.entry("gift", "Щедрость"), Map.entry("rescue", "Героизм"), Map.entry("revenge", "Месть"),
			Map.entry("crime", "Криминал"), Map.entry("pet_death", "Некрологи"), Map.entry("named_death", "Некрологи"),
			Map.entry("loot", "Мародёрство"), Map.entry("dig_at_home", "Соседи"), Map.entry("visit", "Соседи"),
			Map.entry("newcomer", "Новенькие"), Map.entry("return", "Возвращения"), Map.entry("missing", "Розыск"),
			Map.entry("streak", "Постоянство"), Map.entry("deathless", "Постоянство"), Map.entry("shift", "Перемены"),
			Map.entry("unusual", "Странности"), Map.entry("quiet", "Тишина"), Map.entry("totem", "Чудеса"));

	/** Выпуск из фактов, если ИИ недоступен: заголовок — главный факт, заметки — следующие. */
	public static Issue fallback(DayReport r, Map<String, List<String>> extras, Random rnd) {
		List<DayReport.Fact> facts = r.facts();
		String dayRu = LocalDate.parse(r.day()).format(DateTimeFormatter.ofPattern("d.MM"));
		String headline = facts.isEmpty() ? "Вчера на сервере было тихо" : clip(capitalize(facts.getFirst().text()), HEADLINE);
		List<Issue.Article> articles = new ArrayList<>();
		// Главный факт уже в заголовке; заметки — следующие, если их хватает
		for (DayReport.Fact f : facts.size() >= 3 ? facts.subList(1, facts.size()) : facts) {
			if (articles.size() >= 5) {
				break;
			}
			articles.add(new Issue.Article(RUBRICS.getOrDefault(f.kind(), "Хроника"), clip(capitalize(f.text()) + ".", BODY)));
		}
		DayReport.ServerDay s = r.server();
		if (s != null && s.players() > 0 && articles.size() < 5) {
			articles.add(new Issue.Article("Сводка за " + dayRu, "Играли " + s.players() + ", всего " + Metrics.duration(s.onlineSeconds())
					+ ". Смертей " + s.deaths() + ", убито мобов " + Metrics.count(s.mobKills()) + ", добыто блоков "
					+ Metrics.count(s.mined()) + ", пройдено " + Metrics.km(s.distance()) + "."));
		}
		String forecast = r.forecasts().isEmpty() ? pick(extras.get("prophecy"), rnd) : r.forecasts().getFirst().text();
		return new Issue(r.day(), headline, articles, pick(extras.get("ads"), rnd), pick(extras.get("weather"), rnd), forecast,
				"fallback", null, 0, 0);
	}

	private static String pick(List<String> list, Random rnd) {
		return list == null || list.isEmpty() ? null : list.get(rnd.nextInt(list.size()));
	}

	static String clip(String s, int max) {
		if (s == null) {
			return "";
		}
		if (s.length() <= max) {
			return s;
		}
		int cut = s.lastIndexOf(' ', max - 1);
		return (cut > max / 2 ? s.substring(0, cut) : s.substring(0, max - 1)) + "…";
	}

	/** С заглавной — только русское слово: ник игрока менять нельзя. */
	private static String capitalize(String s) {
		if (s.isEmpty() || Character.UnicodeBlock.of(s.charAt(0)) != Character.UnicodeBlock.CYRILLIC) {
			return s;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}
}
