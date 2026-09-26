package ru.xetpy.rikoshet.memory;

import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.chronicle.Activity;
import ru.xetpy.rikoshet.chronicle.analysis.DayReport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Ночная консолидация: итоги дня → значения слотов. Чистая функция; сравнение с текущими
 * значениями и запись — в {@link MemoryService}. docs/architecture/memory.md#знания-слоты
 */
public final class Consolidator {
	/** Детекторы фактов, повторение которых — сюжетная линия. */
	static final Set<String> STORY_KINDS = Set.of("nemesis", "streak", "deathless", "missing", "series", "crime",
			"cooling", "new_friends", "quarrel", "record", "loner", "between");
	/** История жива, пока факт повторялся не реже чем раз в столько дней. */
	static final int STORY_GAP_DAYS = 3;

	private Consolidator() {
	}

	/** Значение слота: subject — UUID игрока, «pair:a:b» или «server». */
	public record Slot(String subject, String slot, String value, int importance, JsonObject data) {
	}

	public static List<Slot> slots(DayReport r, Function<UUID, String> who) {
		List<Slot> out = new ArrayList<>();
		for (DayReport.Profile p : r.profiles()) {
			String u = p.uuid();
			if (!"без выраженного стиля".equals(p.style())) {
				out.add(new Slot(u, "style", "по стилю игры — " + p.style() + shares(p), 15, null));
			}
			if (p.typicalHour() >= 0) {
				out.add(new Slot(u, "habit_time", "обычно играет около " + p.typicalHour() + ":00", 5, null));
			}
			if (p.bestFriend() != null) {
				JsonObject d = new JsonObject();
				d.addProperty("friend", p.bestFriend());
				out.add(new Slot(u, "best_friend", "больше всего времени проводит с " + who.apply(UUID.fromString(p.bestFriend())), 20, d));
			}
			if (p.nemesis() != null) {
				JsonObject d = new JsonObject();
				d.addProperty("killer", p.nemesis());
				out.add(new Slot(u, "nemesis", "чаще всего гибнет: " + p.nemesis() + " (" + p.nemesisCount() + " из " + p.deaths()
						+ " смертей за месяц)", 25, d));
			}
			if (p.favoriteBiome() != null) {
				out.add(new Slot(u, "favorite_biome", "любимый биом — " + p.favoriteBiome(), 5, null));
			}
		}
		for (DayReport.Relation rel : r.relations()) {
			if (!"acquainted".equals(rel.type())) {
				out.add(new Slot("pair:" + rel.a() + ":" + rel.b(), "relation", rel.aWho() + " и " + rel.bWho() + " — " + rel.text(),
						"conflict".equals(rel.type()) ? 30 : 20, null));
			}
		}
		return out;
	}

	/** «шахтёр и строитель (шахта 60%, стройка 30%)». */
	private static String shares(DayReport.Profile p) {
		List<String> parts = new ArrayList<>();
		List<Map.Entry<String, Double>> sorted = new ArrayList<>(p.shares().entrySet());
		sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());
		for (Map.Entry<String, Double> e : sorted) {
			if (e.getValue() >= 0.15 && parts.size() < 3) {
				parts.add(Activity.byId(e.getKey()).map(Activity::ru).orElse(e.getKey()) + " " + Math.round(e.getValue() * 100) + "%");
			}
		}
		return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
	}

	/** Сюжетная линия: факт, который продолжается. days — какой по счёту день. */
	public record Story(String slot, String subject, String text, int days, LocalDate since) {
	}

	/**
	 * Продлить или начать сюжетные линии по фактам дня. previous — текущие значения слотов
	 * story:* (слот → дата последнего подтверждения и число дней).
	 */
	public static List<Story> stories(DayReport r, Map<String, StoryState> previous) {
		LocalDate day = LocalDate.parse(r.day());
		List<Story> out = new ArrayList<>();
		for (DayReport.Fact f : r.facts()) {
			if (!STORY_KINDS.contains(f.kind()) || f.uuid() == null) {
				continue;
			}
			String slot = "story:" + f.kind() + ":" + f.uuid();
			StoryState prev = previous.get(slot);
			if (prev == null) {
				out.add(new Story(slot, f.uuid(), f.text(), 1, day));
			} else if (prev.last().isAfter(day)) {
				continue; // пересчёт старого дня не трогает текущие истории
			} else if (prev.last().equals(day)) {
				out.add(new Story(slot, f.uuid(), f.text(), prev.days(), prev.since())); // тот же день пересчитали
			} else if (prev.last().plusDays(STORY_GAP_DAYS).isAfter(day)) {
				out.add(new Story(slot, f.uuid(), f.text(), prev.days() + 1, prev.since()));
			} else {
				out.add(new Story(slot, f.uuid(), f.text(), 1, day)); // долго не было — новая история
			}
		}
		return out;
	}

	public record StoryState(LocalDate last, int days, LocalDate since) {
	}
}
