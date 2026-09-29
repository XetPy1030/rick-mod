package ru.xetpy.rikoshet.chronicle.analysis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.chronicle.ChronicleEvent;
import ru.xetpy.rikoshet.chronicle.Keys;
import ru.xetpy.rikoshet.chronicle.Names;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Событие летописи → фраза по-русски. Одна и та же фраза идёт в факты газеты и в эпизоды
 * памяти, поэтому без оценок и шуток: шутит модель. Координат нет нигде.
 */
public final class EventText {
	private EventText() {
	}

	/** who — как назвать игрока по UUID (игрока с /rick off — обезличенно). */
	public static String render(ChronicleEvent e, Function<UUID, String> who) {
		JsonObject d = e.data() == null ? new JsonObject() : e.data();
		String actor = who.apply(e.uuid());
		return switch (e.type()) {
			case ChronicleEvent.DEATH -> death(actor, d, who);
			case ChronicleEvent.FIRST -> first(actor, d);
			case ChronicleEvent.MILESTONE -> milestone(actor, d);
			case ChronicleEvent.ADVANCEMENT -> actor + " получил достижение «" + str(d, "title", e.subject()) + "»"
					+ (bool(d, "server_first") ? " — первым на сервере" : "");
			case ChronicleEvent.BOSS -> actor + " убил босса: " + Names.mob(str(d, "entity", e.subject()))
					+ (num(d, "count") > 1 ? " ×" + num(d, "count") : "")
					+ (bool(d, "server_first") ? " — впервые на сервере" : "");
			case ChronicleEvent.PET_DEATH -> "погиб питомец " + who.apply(uuid(d, "owner")) + ": " + str(d, "pet", "зверь")
					+ named(d) + killer(d, who);
			case ChronicleEvent.NAMED_DEATH -> "погиб " + str(d, "mob", "моб") + named(d) + killer(d, who);
			case ChronicleEvent.VISIT -> actor + " задержался у дома " + who.apply(uuid(d, "target"))
					+ (bool(d, "owner_online") ? ", пока хозяина не было рядом" : ", пока хозяина не было на сервере");
			case ChronicleEvent.TOTEM -> actor + " спасся тотемом бессмертия";
			case "diary" -> "из дневника Рика: " + str(d, "text", "");
			case ChronicleEvent.CHAT_NOTE -> "из чата: " + str(d, "text", "");
			case "build_loss" -> "постройка " + (d.has("name") ? "«" + str(d, "name", "") + "» " : "") + actor + " (" + str(d, "where", "?")
					+ ") потеряла " + num(d, "percent") + "% рукотворных блоков";
			case "gift" -> actor + ", похоже, передал " + who.apply(uuid(d, "target")) + " "
					+ Names.item(str(d, "item", "что-то")) + (num(d, "count") > 1 ? " ×" + num(d, "count") : "");
			case "rescue" -> actor + " убил моба, который добивал " + who.apply(uuid(d, "target"))
					+ " (у того оставалось " + num(d, "health") + "% здоровья)";
			case "revenge" -> actor + " отомстил за " + who.apply(uuid(d, "target")) + ": убил моба, который его прикончил";
			case "loot" -> actor + " подобрал вещи погибшего " + who.apply(uuid(d, "target"))
					+ (d.has("item") && !d.get("item").isJsonNull() ? " (" + Names.item(str(d, "item", "")) + " и другое)" : "");
			case "dig_at_home" -> actor + " копал у дома " + who.apply(uuid(d, "target")) + " в его отсутствие: "
					+ Metrics.count(num(d, "mined")) + " блоков";
			default -> actor + ": " + e.type() + (e.subject() == null ? "" : " " + e.subject());
		};
	}

	private static String death(String actor, JsonObject d, Function<UUID, String> who) {
		StringBuilder sb = new StringBuilder(actor).append(" погиб: ").append(str(d, "cause", "неизвестно"));
		UUID killer = uuid(d, "killer_uuid");
		if (killer != null) {
			sb.append(", убийца — ").append(who.apply(killer));
		} else if (d.has("killer")) {
			sb.append(", убийца — ").append(str(d, "killer", ""));
		}
		List<String> lost = new ArrayList<>();
		addLost(lost, num(d, "diamonds"), "алмаз", "алмаза", "алмазов");
		addLost(lost, num(d, "netherite"), "незеритовая вещь", "незеритовые вещи", "незеритовых вещей");
		addLost(lost, num(d, "enchanted"), "зачарованная вещь", "зачарованные вещи", "зачарованных вещей");
		addLost(lost, num(d, "elytra"), "элитры", "элитры", "элитр");
		addLost(lost, num(d, "totems"), "тотем", "тотема", "тотемов");
		addLost(lost, num(d, "shulkers"), "шалкеровый ящик", "шалкеровых ящика", "шалкеровых ящиков");
		if (!lost.isEmpty()) {
			sb.append("; при себе было: ").append(String.join(", ", lost));
		}
		long since = num(d, "since_respawn");
		if (since >= 0 && since < 60) {
			sb.append("; всего через ").append(since).append(" с после прошлой смерти");
		}
		if (d.has("dim")) {
			sb.append(" (").append(Names.dimension(str(d, "dim", ""))).append(", ").append(Names.pretty(str(d, "biome", "?")));
			if (d.has("y")) {
				sb.append(", высота ").append(num(d, "y"));
			}
			sb.append(')');
		}
		return sb.toString();
	}

	private static void addLost(List<String> out, long n, String one, String few, String many) {
		if (n > 0) {
			out.add(n + " " + Metrics.plural(n, one, few, many));
		}
	}

	private static String first(String actor, JsonObject d) {
		String kind = str(d, "kind", "");
		String id = str(d, "id", "?");
		boolean server = bool(d, "server_first");
		String what = switch (kind) {
			case "dimension" -> "побывал в измерении «" + Names.dimension(id) + "»";
			case "structure" -> "нашёл структуру «" + Names.structure(id) + "»";
			case "item" -> "добыл «" + Names.item(id) + "»";
			case "biome" -> "добрался до биома «" + Names.pretty(id) + "»";
			default -> kind + " " + id;
		};
		return server ? actor + " первым на сервере " + what : actor + " впервые " + what;
	}

	private static String milestone(String actor, JsonObject d) {
		String key = str(d, "key", "");
		long t = num(d, "threshold");
		return switch (key) {
			case Keys.DISTANCE -> actor + " прошёл за всё время больше " + Metrics.km(t);
			case Keys.AVIATE -> actor + " налетал на элитрах больше " + Metrics.km(t);
			case Keys.PLAY_HOURS -> actor + " провёл на сервере больше " + t + " " + Metrics.plural(t, "часа", "часов", "часов");
			default -> actor + " — веха за всё время: " + MILESTONE_LABELS.getOrDefault(key, key) + " " + Metrics.count(t);
		};
	}

	private static final java.util.Map<String, String> MILESTONE_LABELS = java.util.Map.ofEntries(
			java.util.Map.entry(Keys.DEATHS, "смертей"),
			java.util.Map.entry(Keys.MOB_KILLS, "убито мобов"),
			java.util.Map.entry(Keys.PLAYER_KILLS, "убито игроков"),
			java.util.Map.entry(Keys.MINED, "добыто блоков"),
			java.util.Map.entry(Keys.PLACED, "поставлено блоков"),
			java.util.Map.entry(Keys.DIAMONDS, "добыто алмазной руды"),
			java.util.Map.entry(Keys.DEBRIS, "добыто древних обломков"),
			java.util.Map.entry(Keys.FISH, "поймано рыбы"),
			java.util.Map.entry(Keys.BRED, "выведено животных"),
			java.util.Map.entry(Keys.TRADES, "сделок с жителями"),
			java.util.Map.entry(Keys.ENCHANTS, "зачарований"),
			java.util.Map.entry(Keys.JUMPS, "прыжков"),
			java.util.Map.entry(Keys.TOTEMS, "спасений тотемом"));

	private static String named(JsonObject d) {
		return d.has("name") ? " по имени «" + str(d, "name", "") + "»" : "";
	}

	private static String killer(JsonObject d, Function<UUID, String> who) {
		UUID k = uuid(d, "killer_uuid");
		if (k != null) {
			return "; убийца — " + who.apply(k);
		}
		return d.has("killer") ? "; убийца — " + str(d, "killer", "") : "";
	}

	static String str(JsonObject d, String k, String def) {
		JsonElement e = d.get(k);
		return e == null || e.isJsonNull() ? def : e.getAsString();
	}

	static long num(JsonObject d, String k) {
		JsonElement e = d.get(k);
		return e == null || e.isJsonNull() ? -1 : e.getAsLong();
	}

	static boolean bool(JsonObject d, String k) {
		JsonElement e = d.get(k);
		return e != null && !e.isJsonNull() && e.getAsBoolean();
	}

	static UUID uuid(JsonObject d, String k) {
		JsonElement e = d.get(k);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		try {
			return UUID.fromString(e.getAsString());
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
