package ru.xetpy.rikoshet.quest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Реестр квестов Рика и шаблоны эксперимента дня — rikoshet/quests.json (переопределяется файлом в
 * папке данных мода). Предметы и статистика сверяются с реестрами игры: чего нет — пропускаем с
 * предупреждением, а не падаем.
 */
public final class QuestDefs {
	public static final String EXPERIMENT = "experiment";

	public enum Kind {
		BRING, STAT
	}

	/**
	 * Квест. count — в единицах показа (блоки), target — в единицах статистики (сантиметры).
	 *
	 * @param name что принести, по-русски — для промпта и журнала
	 * @param what что сделать для статистики, по-русски
	 */
	public record Def(String id, String title, Kind kind, String item, String name, String stat, String what, int count, int unit,
			Level level, int days, int repeatDays, int reputation, boolean roll, String brief) {
		public int target() {
			return count * unit;
		}

		/** «гнилая плоть ×32» или «убить зомби: 15». */
		public String goal() {
			return kind == Kind.BRING ? name + " ×" + count : what + ": " + count;
		}
	}

	private record Template(Kind kind, String item, String name, String stat, String what, int min, int max, int unit) {
	}

	private final Map<String, Def> quests;
	private final List<Template> experiments;
	private final int experimentReputation;
	private final boolean experimentRoll;

	private QuestDefs(Map<String, Def> quests, List<Template> experiments, int experimentReputation, boolean experimentRoll) {
		this.quests = quests;
		this.experiments = experiments;
		this.experimentReputation = experimentReputation;
		this.experimentRoll = experimentRoll;
	}

	public static QuestDefs load(Path dataDir, Logger log) {
		JsonObject root = read("quests.json", dataDir, log);
		Map<String, Def> quests = new LinkedHashMap<>();
		for (JsonElement el : array(root, "quests")) {
			Def d = def(el.getAsJsonObject(), log);
			if (d != null) {
				quests.put(d.id(), d);
			}
		}
		List<Template> templates = new ArrayList<>();
		for (JsonElement el : array(root, "experiments")) {
			JsonObject o = el.getAsJsonObject();
			Kind kind = kind(str(o, "kind"));
			String item = str(o, "item");
			String stat = str(o, "stat");
			if (kind == null || !valid(kind, item, stat)) {
				log.warn("[квесты] шаблон эксперимента пропущен: {}", o);
				continue;
			}
			templates.add(new Template(kind, item, str(o, "name"), stat, str(o, "what"), num(o, "min", 1), num(o, "max", 1), num(o, "unit", 1)));
		}
		JsonObject exp = root.has("experiment") ? root.getAsJsonObject("experiment") : new JsonObject();
		log.info("[квесты] квестов {}, шаблонов эксперимента {}", quests.size(), templates.size());
		return new QuestDefs(Map.copyOf(quests), List.copyOf(templates), num(exp, "reputation", 3), bool(exp, "roll", true));
	}

	private static Def def(JsonObject o, Logger log) {
		String id = str(o, "id");
		Kind kind = kind(str(o, "kind"));
		Level level = Level.byId(str(o, "level") == null ? "lab" : str(o, "level"));
		String item = str(o, "item");
		String stat = str(o, "stat");
		if (id == null || kind == null || level == null || !valid(kind, item, stat) || EXPERIMENT.equals(id)) {
			log.warn("[квесты] квест пропущен: {}", o);
			return null;
		}
		return new Def(id, str(o, "title") == null ? id : str(o, "title"), kind, item, str(o, "name"), stat, str(o, "what"),
				Math.max(1, num(o, "count", 1)), Math.max(1, num(o, "unit", 1)), level, Math.max(1, num(o, "days", 3)),
				Math.max(0, num(o, "repeat_days", 3)), num(o, "reputation", 2), bool(o, "roll", true), str(o, "brief"));
	}

	/** Предмет есть в реестре, статистика разбирается. */
	static boolean valid(Kind kind, String item, String stat) {
		if (kind == Kind.BRING) {
			Identifier id = item == null ? null : Identifier.tryParse(item);
			return id != null && BuiltInRegistries.ITEM.containsKey(id);
		}
		return StatRef.parse(stat) != null;
	}

	public Def get(String id) {
		return quests.get(id);
	}

	public List<Def> all() {
		return List.copyOf(quests.values());
	}

	/**
	 * Эксперимент дня: один на сервер, шаблон и число выбираются по дате — одинаково после перезапуска.
	 * Шаблонов нет — null.
	 */
	public Def experiment(LocalDate day) {
		if (experiments.isEmpty()) {
			return null;
		}
		Random rnd = new Random(day.toEpochDay() * 7_919L + 17);
		Template t = experiments.get(rnd.nextInt(experiments.size()));
		int count = t.min() + rnd.nextInt(Math.max(1, t.max() - t.min() + 1));
		return new Def(EXPERIMENT, "Эксперимент дня", t.kind(), t.item(), t.name(), t.stat(), t.what(), count, t.unit(), Level.SUBJECT,
				1, 1, experimentReputation, experimentRoll, null);
	}

	// ---------- чтение ----------

	static JsonObject read(String file, Path dataDir, Logger log) {
		Path override = dataDir.resolve(file);
		try {
			if (Files.exists(override)) {
				log.info("[квесты] {} — из {}", file, override);
				return JsonParser.parseString(Files.readString(override)).getAsJsonObject();
			}
			try (InputStream in = QuestDefs.class.getResourceAsStream("/rikoshet/" + file)) {
				if (in == null) {
					throw new IOException("нет ресурса /rikoshet/" + file);
				}
				return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (IOException | RuntimeException e) {
			log.error("[квесты] {} не прочитан: {}", file, e.toString());
			return new JsonObject();
		}
	}

	static JsonArray array(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	static int num(JsonObject o, String key, int def) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : def;
	}

	static boolean bool(JsonObject o, String key, boolean def) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
	}

	private static Kind kind(String s) {
		return "bring".equals(s) ? Kind.BRING : "stat".equals(s) ? Kind.STAT : null;
	}
}
