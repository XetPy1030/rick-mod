package ru.xetpy.rikoshet.chronicle.analysis;

import ru.xetpy.rikoshet.chronicle.ChronicleEvent;
import ru.xetpy.rikoshet.chronicle.Keys;
import ru.xetpy.rikoshet.chronicle.Milestones;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Предвиденье: простые частотные модели по истории, без ИИ. Всё — расчёт с вероятностью,
 * газета подаёт это как прогноз, а не как факт (docs/design/chronicle.md#предвиденье).
 */
public final class Forecaster {
	private Forecaster() {
	}

	/** Вероятность, что игрок зайдёт завтра, и час, когда он обычно онлайн. */
	public record Online(double p, int hour) {
	}

	/**
	 * P(завтра онлайн) = ½·доля активных дней за 28 + ½·доля тех же дней недели за 4 недели.
	 * Меньше 3 активных дней за 28 — прогноза нет.
	 */
	public static Map<UUID, Online> online(DayData d) {
		Map<UUID, Online> out = new HashMap<>();
		LocalDate tomorrow = d.day().plusDays(1);
		DayOfWeek dow = tomorrow.getDayOfWeek();
		LocalDate from = d.day().minusDays(27);
		Map<UUID, long[]> hours = hourHistogram(d);
		for (var e : d.activeDays().entrySet()) {
			int recent = 0;
			int sameDow = 0;
			for (LocalDate day : e.getValue()) {
				if (!day.isBefore(from) && !day.isAfter(d.day())) {
					recent++;
					if (day.getDayOfWeek() == dow) {
						sameDow++;
					}
				}
			}
			if (recent < 3) {
				continue;
			}
			double p = 0.5 * recent / 28.0 + 0.5 * Math.min(1.0, sameDow / 4.0);
			out.put(e.getKey(), new Online(Math.min(0.99, p), peakHour(hours.get(e.getKey()))));
		}
		return out;
	}

	/** Секунды онлайн по часам суток за 28 дней. */
	static Map<UUID, long[]> hourHistogram(DayData d) {
		Map<UUID, long[]> out = new HashMap<>();
		for (DayData.Session s : d.sessions()) {
			long[] h = out.computeIfAbsent(s.uuid(), k -> new long[24]);
			long t = s.start();
			while (t < s.end()) {
				ZonedDateTime z = Instant.ofEpochMilli(t).atZone(d.zone());
				long nextHour = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli();
				long upto = Math.min(nextHour, s.end());
				h[z.getHour()] += (upto - t) / 1000;
				t = upto;
			}
		}
		return out;
	}

	static int peakHour(long[] h) {
		if (h == null) {
			return -1;
		}
		int best = -1;
		long bestV = 0;
		for (int i = 0; i < 24; i++) {
			if (h[i] > bestV) {
				bestV = h[i];
				best = i;
			}
		}
		return best;
	}

	/** Главная угроза: убийца, на которого приходится больше всего смертей за 28 дней (от 3). */
	public record Risk(String killer, int count, int total) {
	}

	public static Map<UUID, Risk> risks(DayData d) {
		Map<UUID, Map<String, Integer>> by = new HashMap<>();
		for (ChronicleEvent e : d.deaths()) {
			if (e.uuid() == null) {
				continue;
			}
			by.computeIfAbsent(e.uuid(), k -> new HashMap<>()).merge(killerLabel(e), 1, Integer::sum);
		}
		Map<UUID, Risk> out = new HashMap<>();
		for (var e : by.entrySet()) {
			int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
			var top = e.getValue().entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
			if (top.getValue() >= 3) {
				out.put(e.getKey(), new Risk(top.getKey(), top.getValue(), total));
			}
		}
		return out;
	}

	/** Кто убил, одной строкой: моб, «игрок», иначе причина. */
	static String killerLabel(ChronicleEvent e) {
		var d = e.data();
		if (d != null && d.has("killer_uuid")) {
			return "другой игрок";
		}
		if (d != null && d.has("killer")) {
			return d.get("killer").getAsString();
		}
		return d != null && d.has("cause") ? d.get("cause").getAsString() : "неизвестно";
	}

	/** Веха на подходе: до порога осталось столько, что при темпе последних 7 дней — за 1–3 дня. */
	public record Upcoming(String key, long threshold, long left, int days) {
	}

	public static List<Upcoming> upcoming(DayData d, UUID uuid) {
		List<Upcoming> out = new ArrayList<>();
		Map<String, Long> totals = d.totals().getOrDefault(uuid, Map.of());
		Map<LocalDate, Map<String, Long>> hist = d.history().getOrDefault(uuid, Map.of());
		for (var m : Milestones.LIFETIME.entrySet()) {
			Long total = totals.get(m.getKey());
			if (total == null || m.getKey().equals(Keys.PLAY_HOURS)) {
				continue;
			}
			long next = -1;
			for (long t : m.getValue()) {
				if (t > total) {
					next = t;
					break;
				}
			}
			if (next < 0) {
				continue;
			}
			long week = d.countersOf(uuid).getOrDefault(m.getKey(), 0L);
			for (int i = 1; i < 7; i++) {
				week += hist.getOrDefault(d.day().minusDays(i), Map.of()).getOrDefault(m.getKey(), 0L);
			}
			double perDay = week / 7.0;
			if (perDay <= 0) {
				continue;
			}
			long left = next - total;
			int days = (int) Math.ceil(left / perDay);
			if (days >= 1 && days <= 3) {
				out.add(new Upcoming(m.getKey(), next, left, days));
			}
		}
		return out;
	}

	/**
	 * Пропавшие: был активным (5+ дней за 30), а сейчас не заходит в 2 раза дольше своего
	 * обычного перерыва и минимум 3 дня. Сообщаем на пороге и потом раз в неделю, чтобы
	 * газета не повторяла одно и то же каждый день.
	 */
	public record Missing(UUID uuid, long days, long usualGap) {
	}

	public static List<Missing> missing(DayData d) {
		List<Missing> out = new ArrayList<>();
		for (var e : d.activeDays().entrySet()) {
			List<LocalDate> days = e.getValue();
			if (days.isEmpty() || days.getLast().equals(d.day())) {
				continue;
			}
			long recent = days.stream().filter(x -> !x.isBefore(d.day().minusDays(30))).count();
			if (recent < 5) {
				continue;
			}
			List<Long> gaps = new ArrayList<>();
			for (int i = 1; i < days.size(); i++) {
				gaps.add(ChronoUnit.DAYS.between(days.get(i - 1), days.get(i)));
			}
			long usual = median(gaps);
			long away = ChronoUnit.DAYS.between(days.getLast(), d.day());
			long threshold = Math.max(3, 2 * usual);
			if (away >= threshold && (away - threshold) % 7 == 0) {
				out.add(new Missing(e.getKey(), away, usual));
			}
		}
		return out;
	}

	static long median(List<Long> v) {
		if (v.isEmpty()) {
			return 1;
		}
		List<Long> s = new ArrayList<>(v);
		s.sort(Long::compare);
		return Math.max(1, s.get(s.size() / 2));
	}
}
