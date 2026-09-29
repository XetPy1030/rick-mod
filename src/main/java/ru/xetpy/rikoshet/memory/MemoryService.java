package ru.xetpy.rikoshet.memory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.chronicle.ChronicleEvent;
import ru.xetpy.rikoshet.chronicle.ChronicleStore;
import ru.xetpy.rikoshet.chronicle.analysis.DayReport;
import ru.xetpy.rikoshet.chronicle.analysis.EventText;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Память персонажей (docs/architecture/memory.md): эпизоды из летописи, знания-слоты,
 * сюжетные линии; вспоминание для промптов; ночная консолидация. Индекс — в памяти,
 * только главный поток.
 */
public final class MemoryService {
	/** Эпизоды дешевле — не помним. */
	static final int MIN_EPISODE = 5;
	static final int KEEP_DAYS = 90;
	static final int MAX_EPISODES = 20_000;
	static final double SLOT_HALF_LIFE = 30;

	/** Итог консолидации для газеты: смены слотов и живые сюжетные линии. */
	public record Consolidation(List<String> changes, List<String> stories) {
	}

	private record Episode(ChronicleEvent event, Set<UUID> about, Set<String> tags) {
	}

	private final Logger log;
	private final Clock clock;
	private final Supplier<ZoneId> zone;
	private final MemoryStore store;
	private final Function<UUID, String> who;
	private final Deque<Episode> episodes = new ArrayDeque<>();
	private volatile LocalDate changesDay;
	private volatile List<String> changes = List.of();
	/** subject → slot → текущее значение. */
	private final Map<String, Map<String, MemoryStore.Row>> facts = new HashMap<>();

	public MemoryService(Logger log, Clock clock, Supplier<ZoneId> zone, MemoryStore store, ChronicleStore chronicle,
			Function<UUID, String> who) throws SQLException {
		this.log = log;
		this.clock = clock;
		this.zone = zone;
		this.store = store;
		this.who = who;
		for (MemoryStore.Row r : store.loadCurrent()) {
			facts.computeIfAbsent(r.subject(), k -> new HashMap<>()).put(r.slot(), r);
		}
		long since = clock.millis() - TimeUnit.DAYS.toMillis(KEEP_DAYS);
		for (ChronicleEvent e : chronicle.recentEvents(since, MIN_EPISODE)) {
			add(e);
		}
		log.info("[память] эпизодов {}, знаний {}", episodes.size(), facts.values().stream().mapToInt(Map::size).sum());
	}

	// ---------- эпизоды ----------

	/** Событие летописи — кандидат в эпизоды. Главный поток. */
	public void onEvent(ChronicleEvent e) {
		if (e.score() >= MIN_EPISODE) {
			add(e);
		}
	}

	private void add(ChronicleEvent e) {
		episodes.addLast(new Episode(e, about(e), tags(e)));
		long cutoff = clock.millis() - TimeUnit.DAYS.toMillis(KEEP_DAYS);
		while (!episodes.isEmpty() && (episodes.size() > MAX_EPISODES || episodes.peekFirst().event().ts() < cutoff)) {
			episodes.removeFirst();
		}
	}

	static Set<UUID> about(ChronicleEvent e) {
		Set<UUID> out = new HashSet<>();
		if (e.uuid() != null) {
			out.add(e.uuid());
		}
		JsonObject d = e.data();
		if (d != null) {
			for (String k : new String[] {"target", "owner", "killer_uuid"}) {
				UUID u = uuid(d.get(k));
				if (u != null) {
					out.add(u);
				}
			}
		}
		return out;
	}

	/** Теги эпизода — те же, что у запросов: «death», «killer:creeper», «biome:plains»… */
	static Set<String> tags(ChronicleEvent e) {
		Set<String> out = new HashSet<>();
		out.add(e.type());
		if (e.subject() != null) {
			out.add("subj:" + e.subject());
		}
		JsonObject d = e.data();
		if (d != null) {
			tag(out, d, "group", "group:");
			tag(out, d, "killer_type", "killer:");
			tag(out, d, "killer", "killer_label:");
			tag(out, d, "biome", "biome:");
			tag(out, d, "dim", "dim:");
			tag(out, d, "kind", "kind:");
			tag(out, d, "item", "item:");
		}
		return out;
	}

	private static void tag(Set<String> out, JsonObject d, String field, String prefix) {
		JsonElement v = d.get(field);
		if (v != null && !v.isJsonNull()) {
			out.add(prefix + v.getAsString());
		}
	}

	// ---------- вспоминание ----------

	/**
	 * Что вспомнить о ситуации: строки для блока &lt;memory&gt; или null. subjects — главный игрок
	 * первым, потом кто рядом; tags — теги ситуации. События новее freshMs пропускаются: это
	 * и есть текущая ситуация, она уже в контексте.
	 */
	public String recall(List<UUID> subjects, Set<String> tags, int budgetChars, long freshMs) {
		if (subjects.isEmpty()) {
			return null;
		}
		long now = clock.millis();
		List<Memory> candidates = new ArrayList<>();
		Set<UUID> subj = new HashSet<>(subjects);
		for (Episode ep : episodes) {
			if (now - ep.event().ts() < freshMs) {
				continue;
			}
			boolean relevant = ep.about().stream().anyMatch(subj::contains) || ep.tags().stream().anyMatch(tags::contains);
			if (!relevant) {
				continue;
			}
			ChronicleEvent e = ep.event();
			candidates.add(new Memory(EventText.render(e, who) + " (" + ago(e.ts(), now) + ")", ep.about(), ep.tags(), e.score(), e.ts(),
					Memory.halfLife(e.score()), e.type() + "|" + e.subject() + "|" + e.uuid()));
		}
		for (UUID u : subjects) {
			Map<String, MemoryStore.Row> slots = facts.get(u.toString());
			if (slots == null) {
				continue;
			}
			for (MemoryStore.Row r : slots.values()) {
				Set<String> st = new HashSet<>();
				st.add("slot:" + r.slot());
				if (r.data() != null && r.data().has("killer")) {
					st.add("killer_label:" + r.data().get("killer").getAsString());
				}
				String text = r.slot().startsWith("story:") ? r.value() + storyDays(r) : who.apply(u) + ": " + r.value();
				candidates.add(new Memory(text, Set.of(u), st, r.importance(), r.updatedTs(), SLOT_HALF_LIFE, u + "|" + r.slot()));
			}
		}
		// Связи главного игрока с теми, кто рядом
		for (int i = 1; i < subjects.size(); i++) {
			MemoryStore.Row rel = relation(subjects.getFirst(), subjects.get(i));
			if (rel != null) {
				candidates.add(new Memory(rel.value(), Set.of(subjects.getFirst(), subjects.get(i)), Set.of("slot:relation"),
						rel.importance(), rel.updatedTs(), SLOT_HALF_LIFE, "rel|" + subjects.get(i)));
			}
		}
		List<Recall.Scored> got = Recall.recall(candidates, new Recall.Query(subjects, tags, budgetChars, now));
		if (got.isEmpty()) {
			return null;
		}
		List<String> lines = new ArrayList<>();
		got.forEach(s -> lines.add("- " + s.memory().text()));
		return String.join("\n", lines);
	}

	private MemoryStore.Row relation(UUID a, UUID b) {
		String x = a.toString();
		String y = b.toString();
		String key = x.compareTo(y) < 0 ? "pair:" + x + ":" + y : "pair:" + y + ":" + x;
		Map<String, MemoryStore.Row> m = facts.get(key);
		return m == null ? null : m.get("relation");
	}

	/** Знания об игроке: слот → значение (для /rickadmin memory show). */
	public Map<String, String> knowledge(UUID uuid) {
		Map<String, String> out = new java.util.TreeMap<>();
		Map<String, MemoryStore.Row> m = facts.get(uuid.toString());
		if (m != null) {
			m.forEach((k, r) -> out.put(k, r.value() + (k.startsWith("story:") ? storyDays(r) : "")));
		}
		return out;
	}

	private static String storyDays(MemoryStore.Row r) {
		int days = r.data() != null && r.data().has("days") ? r.data().get("days").getAsInt() : 1;
		return days > 1 ? " (" + days + "-й день)" : "";
	}

	private String ago(long ts, long now) {
		LocalDate d = java.time.Instant.ofEpochMilli(ts).atZone(zone.get()).toLocalDate();
		LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone.get()).toLocalDate();
		long days = java.time.temporal.ChronoUnit.DAYS.between(d, today);
		if (days <= 0) {
			return "сегодня";
		}
		if (days == 1) {
			return "вчера";
		}
		return days + " дн. назад";
	}

	// ---------- консолидация ----------

	/**
	 * Ночью, после анализа дня: обновить слоты, продлить сюжетные линии. Смена значения — сама
	 * по себе новость. Пересчёт старого дня знания не трогает. Главный поток.
	 */
	public Consolidation consolidate(LocalDate day, DayReport report, LocalDate today) {
		List<String> changes = new ArrayList<>();
		List<String> stories = new ArrayList<>();
		if (day.isBefore(today.minusDays(1))) {
			return new Consolidation(changes, stories);
		}
		long now = clock.millis();
		for (Consolidator.Slot s : Consolidator.slots(report, who)) {
			MemoryStore.Row cur = get(s.subject(), s.slot());
			if (cur != null && cur.value().equals(s.value())) {
				put(new MemoryStore.Row(s.subject(), s.slot(), s.value(), s.data(), s.importance(), cur.firstTs(), now));
				store.touch(s.subject(), s.slot(), s.data(), now);
				continue;
			}
			MemoryStore.Row next = new MemoryStore.Row(s.subject(), s.slot(), s.value(), s.data(), s.importance(), now, now);
			put(next);
			store.replace(next, now);
			if (cur != null && changeWorthy(s.slot(), cur, next)) {
				changes.add(changeText(s, cur));
			}
		}

		Map<String, Consolidator.StoryState> prev = new HashMap<>();
		facts.forEach((subject, slots) -> slots.forEach((slot, r) -> {
			if (slot.startsWith("story:") && r.data() != null && r.data().has("last")) {
				prev.put(slot, new Consolidator.StoryState(LocalDate.parse(r.data().get("last").getAsString()),
						r.data().get("days").getAsInt(), LocalDate.parse(r.data().get("since").getAsString())));
			}
		}));
		Set<String> alive = new HashSet<>();
		for (Consolidator.Story st : Consolidator.stories(report, prev)) {
			JsonObject d = new JsonObject();
			d.addProperty("last", day.toString());
			d.addProperty("days", st.days());
			d.addProperty("since", st.since().toString());
			MemoryStore.Row cur = get(st.subject(), st.slot());
			MemoryStore.Row next = new MemoryStore.Row(st.subject(), st.slot(), st.text(), d, 25,
					cur == null ? now : cur.firstTs(), now);
			put(next);
			if (cur == null) {
				store.replace(next, now);
			} else {
				store.update(next); // история продолжается — та же строка, без записи в историю слота
			}
			alive.add(st.slot());
			if (st.days() >= 2) {
				stories.add(st.text() + " — " + st.days() + "-й день");
			}
		}
		// Истории, которые не продолжились STORY_GAP_DAYS дней, закрываем
		for (var sub : facts.entrySet()) {
			sub.getValue().entrySet().removeIf(e -> {
				if (!e.getKey().startsWith("story:") || alive.contains(e.getKey())) {
					return false;
				}
				MemoryStore.Row r = e.getValue();
				LocalDate last = r.data() != null && r.data().has("last") ? LocalDate.parse(r.data().get("last").getAsString()) : day;
				if (!last.plusDays(Consolidator.STORY_GAP_DAYS).isAfter(day)) {
					store.end(sub.getKey(), e.getKey(), now);
					return true;
				}
				return false;
			});
		}
		if (!changes.isEmpty() || !stories.isEmpty()) {
			log.info("[память] {}: смен {}, историй {}", day, changes.size(), stories.size());
		}
		this.changesDay = day;
		this.changes = List.copyOf(changes);
		return new Consolidation(changes, stories);
	}

	/** Записать знание в слот вне ночной консолидации (имя постройки и т. п.). Главный поток. */
	public void remember(UUID subject, String slot, String value, int importance) {
		long now = clock.millis();
		MemoryStore.Row cur = get(subject.toString(), slot);
		MemoryStore.Row next = new MemoryStore.Row(subject.toString(), slot, value, null, importance,
				cur != null && cur.value().equals(value) ? cur.firstTs() : now, now);
		put(next);
		if (cur != null && cur.value().equals(value)) {
			store.touch(subject.toString(), slot, null, now);
		} else {
			store.replace(next, now);
		}
	}

	/** Когда слот последний раз менялся; 0 — слота нет. */
	public long slotUpdated(UUID subject, String slot) {
		MemoryStore.Row r = get(subject.toString(), slot);
		return r == null ? 0 : r.updatedTs();
	}

	/** Смены слотов, найденные при консолидации этого дня (для газеты). */
	public List<String> changesFor(LocalDate day) {
		return day.equals(changesDay) ? changes : List.of();
	}

	/** Живые сюжетные линии от второго дня: «… — 3-й день». */
	public List<String> activeStories() {
		List<String> out = new ArrayList<>();
		for (Map<String, MemoryStore.Row> slots : facts.values()) {
			for (var e : slots.entrySet()) {
				MemoryStore.Row r = e.getValue();
				if (e.getKey().startsWith("story:") && r.data() != null && r.data().has("days") && r.data().get("days").getAsInt() >= 2) {
					out.add(r.value() + " — " + r.data().get("days").getAsInt() + "-й день");
				}
			}
		}
		out.sort(null);
		return out;
	}

	/** Смена, о которой стоит сказать: не часы и не мелкие перетасовки долей. */
	static boolean changeWorthy(String slot, MemoryStore.Row cur, MemoryStore.Row next) {
		return switch (slot) {
			case "best_friend", "nemesis", "relation" -> true;
			case "style" -> !cur.value().split(" \\(")[0].equals(next.value().split(" \\(")[0]);
			default -> false;
		};
	}

	private static String changeText(Consolidator.Slot s, MemoryStore.Row cur) {
		String old = cur.value().split(" \\(")[0];
		String now = s.value().split(" \\(")[0];
		return "перемена: было «" + old + "», стало «" + now + "»";
	}

	/** /rickadmin memory forget: стереть знания и эпизоды игрока. */
	public java.util.concurrent.CompletableFuture<Integer> forget(UUID uuid) {
		String u = uuid.toString();
		facts.remove(u);
		facts.keySet().removeIf(k -> k.startsWith("pair:") && k.contains(u));
		facts.values().forEach(m -> m.keySet().removeIf(k -> k.endsWith(u)));
		episodes.removeIf(e -> e.about().contains(uuid));
		return store.forget(uuid);
	}

	private MemoryStore.Row get(String subject, String slot) {
		Map<String, MemoryStore.Row> m = facts.get(subject);
		return m == null ? null : m.get(slot);
	}

	private void put(MemoryStore.Row r) {
		facts.computeIfAbsent(r.subject(), k -> new HashMap<>()).put(r.slot(), r);
	}

	private static UUID uuid(JsonElement e) {
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
