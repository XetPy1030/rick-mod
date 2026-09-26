package ru.xetpy.rikoshet.chronicle.analysis;

import ru.xetpy.rikoshet.chronicle.ChronicleEvent;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Всё, что нужно анализу одного дня: снимок из БД без ссылок на мир. Собирается в потоке БД
 * ({@link ru.xetpy.rikoshet.chronicle.ChronicleStore#dayData}), анализ — чистая функция от него.
 *
 * @param counters     счётчики игроков за день
 * @param history      выбранные счётчики за 28 дней до дня: метрики рекордов, act:*, biome:*
 * @param serverBest   лучший день сервера по метрике — до этого дня
 * @param personalBest лучший день игрока по метрике — до этого дня
 * @param activeDays   все дни, когда игрок был онлайн, по возрастанию, включая этот
 * @param events       события дня
 * @param deaths       смерти за 28 дней, включая этот
 * @param sessions     сессии, задевшие 28 дней до конца дня
 * @param pairs        счётчики пар за 28 дней, включая этот
 * @param totals       счётчики за всё время
 * @param seen         что игроки увидели впервые в этот день
 * @param headlines    заголовки прошлых выпусков, свежие первыми
 */
public record DayData(
		LocalDate day,
		ZoneId zone,
		Map<UUID, Person> people,
		Map<UUID, Map<String, Long>> counters,
		Map<UUID, Map<LocalDate, Map<String, Long>>> history,
		Map<String, Best> serverBest,
		Map<UUID, Map<String, Long>> personalBest,
		Map<UUID, List<LocalDate>> activeDays,
		List<ChronicleEvent> events,
		List<ChronicleEvent> deaths,
		List<Session> sessions,
		Map<LocalDate, List<PairRow>> pairs,
		Map<UUID, Map<String, Long>> totals,
		List<Seen> seen,
		List<String> headlines
) {
	/** Игрок: ник, роль (или null), /rick off, когда впервые зашёл. */
	public record Person(String name, String role, boolean optedOut, long firstSeen) {
		/** Как назвать в тексте: «Роль (ник)» или ник. */
		public String who() {
			return role == null ? name : role + " (" + name + ")";
		}
	}

	public record Best(UUID uuid, LocalDate day, long value) {
	}

	public record Session(UUID uuid, long start, long end, long afk) {
	}

	public record PairRow(UUID a, UUID b, String key, long value) {
	}

	public record Seen(UUID uuid, String kind, String id, long ts) {
	}

	public Map<String, Long> countersOf(UUID uuid) {
		return counters.getOrDefault(uuid, Map.of());
	}

	/** Название игрока для текста; игрок с /rick off — «кто-то из игроков». */
	public String who(UUID uuid) {
		if (uuid == null) {
			return "кто-то";
		}
		Person p = people.get(uuid);
		if (p == null) {
			return "неизвестный игрок";
		}
		return p.optedOut() ? "кто-то из игроков" : p.who();
	}

	public boolean visible(UUID uuid) {
		Person p = people.get(uuid);
		return p != null && !p.optedOut();
	}
}
