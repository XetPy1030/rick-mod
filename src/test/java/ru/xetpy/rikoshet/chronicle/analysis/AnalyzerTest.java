package ru.xetpy.rikoshet.chronicle.analysis;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import ru.xetpy.rikoshet.chronicle.ChronicleEvent;
import ru.xetpy.rikoshet.chronicle.Keys;
import ru.xetpy.rikoshet.chronicle.social.PairKeys;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerTest {
	static final ZoneId Z = ZoneId.of("Europe/Moscow");
	static final LocalDate DAY = LocalDate.parse("2026-10-10");
	final UUID rick = UUID.fromString("00000000-0000-0000-0000-000000000001");
	final UUID morty = UUID.fromString("00000000-0000-0000-0000-000000000002");
	final UUID ghost = UUID.fromString("00000000-0000-0000-0000-000000000003");

	/** Конструктор снимка дня для тестов. */
	class Day {
		final Map<UUID, DayData.Person> people = new HashMap<>();
		final Map<UUID, Map<String, Long>> counters = new HashMap<>();
		final Map<UUID, Map<LocalDate, Map<String, Long>>> history = new HashMap<>();
		final Map<String, DayData.Best> serverBest = new HashMap<>();
		final Map<UUID, Map<String, Long>> personalBest = new HashMap<>();
		final Map<UUID, List<LocalDate>> activeDays = new HashMap<>();
		final List<ChronicleEvent> events = new ArrayList<>();
		final List<ChronicleEvent> deaths = new ArrayList<>();
		final List<DayData.Session> sessions = new ArrayList<>();
		final Map<LocalDate, List<DayData.PairRow>> pairs = new TreeMap<>();
		final List<DayData.Build> builds = new ArrayList<>();

		Day() {
			long old = DAY.minusDays(60).atStartOfDay(Z).toInstant().toEpochMilli();
			people.put(rick, new DayData.Person("sh5dawg", "Токсик Рик", false, old));
			people.put(morty, new DayData.Person("Benjurist", "Морти", false, old));
			people.put(ghost, new DayData.Person("hidden", null, true, old));
		}

		Day today(UUID u, String key, long v) {
			counters.computeIfAbsent(u, x -> new HashMap<>()).put(key, v);
			return this;
		}

		Day past(UUID u, int daysAgo, String key, long v) {
			history.computeIfAbsent(u, x -> new TreeMap<>()).computeIfAbsent(DAY.minusDays(daysAgo), x -> new HashMap<>()).put(key, v);
			return this;
		}

		Day active(UUID u, int... daysAgo) {
			List<LocalDate> l = activeDays.computeIfAbsent(u, x -> new ArrayList<>());
			for (int d : daysAgo) {
				l.add(DAY.minusDays(d));
			}
			l.sort(null);
			return this;
		}

		Day pair(int daysAgo, UUID a, UUID b, String key, long v) {
			pairs.computeIfAbsent(DAY.minusDays(daysAgo), x -> new ArrayList<>()).add(new DayData.PairRow(a, b, key, v));
			return this;
		}

		Day death(UUID u, int daysAgo, long minuteOfDay, String killer) {
			JsonObject d = new JsonObject();
			d.addProperty("cause", "убит мобом");
			d.addProperty("killer", killer);
			LocalDate day = DAY.minusDays(daysAgo);
			long ts = day.atStartOfDay(Z).toInstant().toEpochMilli() + minuteOfDay * 60_000;
			ChronicleEvent e = new ChronicleEvent(ts, day.toString(), u, ChronicleEvent.DEATH, "mob", 3, d);
			deaths.add(e);
			if (daysAgo == 0) {
				events.add(e);
			}
			return this;
		}

		Day session(UUID u, int fromMinute, int toMinute) {
			long base = DAY.atStartOfDay(Z).toInstant().toEpochMilli();
			sessions.add(new DayData.Session(u, base + fromMinute * 60_000L, base + toMinute * 60_000L, 0));
			return this;
		}

		DayData build() {
			return new DayData(DAY, Z, people, counters, history, serverBest, personalBest, activeDays, events, deaths,
					sessions, pairs, Map.of(), List.of(), List.of("Вчерашний заголовок"), builds);
		}
	}

	@Test
	void serverRecordBeatsPersonalBestForSameMetric() {
		Day d = new Day()
				.today(rick, Keys.ONLINE, 7200L).today(rick, Keys.DEATHS, 11L)
				.today(morty, Keys.ONLINE, 3600L).today(morty, Keys.DEATHS, 6L)
				.active(rick, 0, 1, 2, 3, 4, 5, 6, 7).active(morty, 0, 1, 2, 3, 4, 5, 6, 7);
		d.serverBest.put(Keys.DEATHS, new DayData.Best(morty, DAY.minusDays(3), 7));
		d.personalBest.put(morty, new HashMap<>(Map.of(Keys.DEATHS, 5L)));
		DayReport r = Analyzer.analyze(d.build(), 25);
		DayReport.Fact rec = r.facts().stream().filter(f -> f.kind().equals("record")).findFirst().orElseThrow();
		assertEquals(rick.toString(), rec.uuid());
		assertTrue(rec.text().contains("Токсик Рик (sh5dawg)"), rec.text());
		assertTrue(rec.text().contains("прежний — 7"), rec.text());
		assertTrue(r.facts().stream().anyMatch(f -> f.kind().equals("personal_best") && f.uuid().equals(morty.toString())));
		assertEquals(rec, r.facts().getFirst(), "рекорд сервера — самый значимый");
	}

	@Test
	void noRecordOnFirstDayOfHistory() {
		DayReport r = Analyzer.analyze(new Day().today(rick, Keys.ONLINE, 7200L).today(rick, Keys.MINED, 50_000L).active(rick, 0).build(), 25);
		assertTrue(r.facts().stream().noneMatch(f -> f.kind().equals("record")));
	}

	@Test
	void seriesAndNemesis() {
		Day d = new Day().today(rick, Keys.ONLINE, 3600L).active(rick, 0)
				.death(rick, 0, 600, "крипер").death(rick, 0, 603, "крипер").death(rick, 0, 607, "зомби")
				.death(rick, 2, 100, "крипер");
		DayReport r = Analyzer.analyze(d.build(), 25);
		assertTrue(r.facts().stream().anyMatch(f -> f.kind().equals("series") && f.text().contains("3 раза")));
		DayReport.Fact n = r.facts().stream().filter(f -> f.kind().equals("nemesis")).findFirst().orElseThrow();
		assertTrue(n.text().contains("крипер") && n.text().contains("3 смерти"), n.text());
	}

	@Test
	void newcomerByFirstSeenNotByChronicleStart() {
		Day d = new Day().today(rick, Keys.ONLINE, 600L).today(morty, Keys.ONLINE, 600L).active(rick, 0).active(morty, 0);
		d.people.put(morty, new DayData.Person("Benjurist", "Морти", false, DAY.atStartOfDay(Z).toInstant().toEpochMilli() + 1000));
		DayReport r = Analyzer.analyze(d.build(), 25);
		List<String> newcomers = r.facts().stream().filter(f -> f.kind().equals("newcomer")).map(DayReport.Fact::uuid).toList();
		assertEquals(List.of(morty.toString()), newcomers);
	}

	@Test
	void returnAfterAWeek() {
		DayReport r = Analyzer.analyze(new Day().today(rick, Keys.ONLINE, 600L).active(rick, 0, 9, 10).build(), 25);
		assertTrue(r.facts().stream().anyMatch(f -> f.kind().equals("return") && f.text().contains("9 дней")));
	}

	@Test
	void optedOutPlayerNeverAppears() {
		Day d = new Day().today(ghost, Keys.ONLINE, 9000L).today(ghost, Keys.DEATHS, 50L).active(ghost, 0, 1, 2, 3, 4, 5, 6, 7)
				.today(rick, Keys.ONLINE, 600L).active(rick, 0);
		d.serverBest.put(Keys.DEATHS, new DayData.Best(rick, DAY.minusDays(1), 5));
		DayReport r = Analyzer.analyze(d.build(), 25);
		assertTrue(r.players().stream().noneMatch(p -> p.uuid().equals(ghost.toString())));
		assertTrue(r.facts().stream().noneMatch(f -> ghost.toString().equals(f.uuid()) || f.text().contains("hidden")), r.facts().toString());
	}

	@Test
	void shiftOfUsualActivity() {
		Day d = new Day().today(rick, Keys.ONLINE, 7200L).today(rick, "act:fishing", 5000L).active(rick, 0, 1, 2, 3);
		for (int i = 1; i <= 3; i++) {
			d.past(rick, i, Keys.ONLINE, 7200L).past(rick, i, "act:mining", 6000L);
		}
		DayReport r = Analyzer.analyze(d.build(), 25);
		DayReport.Fact f = r.facts().stream().filter(x -> x.kind().equals("shift")).findFirst().orElseThrow();
		assertTrue(f.text().contains("обычно шахта") && f.text().contains("рыбалка"), f.text());
	}

	@Test
	void relationsInseparableAndNewFriendship() {
		Day d = new Day().today(rick, Keys.ONLINE, 20_000L).today(morty, Keys.ONLINE, 20_000L).active(rick, 0).active(morty, 0);
		for (int i = 0; i < 5; i++) {
			d.pair(i, rick, morty, PairKeys.OVERLAP, 5000).pair(i, rick, morty, PairKeys.TOGETHER, 4500);
		}
		DayReport r = Analyzer.analyze(d.build(), 25);
		DayReport.Relation rel = r.relations().getFirst();
		assertEquals("inseparable", rel.type());
		assertEquals("новая дружба", rel.trend());
		assertEquals("Токсик Рик (sh5dawg)", rel.aWho());
		assertTrue(r.facts().stream().anyMatch(f -> f.kind().equals("new_friends")));
		assertTrue(r.facts().stream().anyMatch(f -> f.kind().equals("pair_of_day")));
	}

	@Test
	void coolingWhenBothPlayButApart() {
		Day d = new Day().today(rick, Keys.ONLINE, 10_000L).today(morty, Keys.ONLINE, 10_000L).active(rick, 0).active(morty, 0);
		for (int i = 7; i < 14; i++) {
			d.pair(i, rick, morty, PairKeys.OVERLAP, 5000).pair(i, rick, morty, PairKeys.TOGETHER, 4000);
		}
		for (int i = 0; i < 7; i++) {
			d.pair(i, rick, morty, PairKeys.OVERLAP, 5000).pair(i, rick, morty, PairKeys.TOGETHER, 100);
		}
		DayReport r = Analyzer.analyze(d.build(), 25);
		assertEquals("охлаждение", r.relations().getFirst().trend());
	}

	@Test
	void conflictFromOneSidedKills() {
		Day d = new Day().today(rick, Keys.ONLINE, 3000L).active(rick, 0)
				.pair(0, rick, morty, PairKeys.OVERLAP, 3000).pair(0, rick, morty, PairKeys.PVP_KILL, 3);
		DayReport r = Analyzer.analyze(d.build(), 25);
		DayReport.Relation rel = r.relations().getFirst();
		assertEquals("conflict", rel.type());
		assertTrue(rel.text().contains("убивал"), rel.text());
	}

	@Test
	void forecastOnlineAndTogether() {
		Day d = new Day().today(rick, Keys.ONLINE, 3000L).today(morty, Keys.ONLINE, 3000L);
		int[] days = new int[20];
		for (int i = 0; i < 20; i++) {
			days[i] = i;
			d.pair(i, rick, morty, PairKeys.OVERLAP, 3000).pair(i, rick, morty, PairKeys.TOGETHER, 2000);
		}
		d.active(rick, days).active(morty, days).session(rick, 20 * 60, 22 * 60);
		DayReport r = Analyzer.analyze(d.build(), 25);
		DayReport.Forecast on = r.forecasts().stream().filter(f -> f.kind().equals("online") && f.uuid().equals(rick.toString())).findFirst().orElseThrow();
		assertTrue(on.p() > 0.5, "20 дней из 28");
		assertTrue(on.text().contains("около 20:00") || on.text().contains("около 21:00"), on.text());
		assertTrue(r.forecasts().stream().anyMatch(f -> f.kind().equals("together")));
	}

	@Test
	void peakOnline() {
		Day d = new Day().session(rick, 600, 720).session(morty, 700, 800).session(ghost, 710, 715);
		int[] peak = Analyzer.peak(d.build());
		assertEquals(3, peak[0]);
		assertEquals(710, peak[1]);
	}

	@Test
	void selectionCapsPerPlayer() {
		List<DayReport.Fact> all = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			all.add(new DayReport.Fact("k" + i, rick.toString(), 50 - i, "факт " + i));
		}
		all.add(new DayReport.Fact("x", morty.toString(), 1, "мелочь"));
		List<DayReport.Fact> sel = Analyzer.select(new Day().build(), all, 25);
		assertEquals(Analyzer.PER_PLAYER + 1, sel.size());
		assertEquals("мелочь", sel.getLast().text());
	}

	@Test
	void buildGrowthAndLossWithSuspects() {
		Day d = new Day().today(rick, Keys.ONLINE, 3600L).active(rick, 0);
		java.util.TreeMap<String, Long> grow = new java.util.TreeMap<>(Map.of(DAY.minusDays(1).toString(), 800L, DAY.toString(), 2300L));
		java.util.TreeMap<String, Long> loss = new java.util.TreeMap<>(Map.of(DAY.minusDays(2).toString(), 1000L, DAY.toString(), 500L));
		java.util.TreeMap<String, Long> fresh = new java.util.TreeMap<>(Map.of(DAY.toString(), 600L));
		d.builds.add(new DayData.Build("overworld", 0, 0, rick, "Амбар", "mangrove swamp", grow, false));
		d.builds.add(new DayData.Build("overworld", 5, 5, morty, null, "plains", loss, false));
		d.builds.add(new DayData.Build("overworld", 9, 9, morty, null, "desert", fresh, false));
		d.builds.add(new DayData.Build("overworld", 7, 7, rick, null, "village", fresh, true));
		d.pair(0, rick, morty, PairKeys.MINE_ABSENT, 300);
		DayReport r = Analyzer.analyze(d.build(), 40);
		DayReport.Fact g = r.facts().stream().filter(f -> f.kind().equals("build_day")).findFirst().orElseThrow();
		assertTrue(g.text().contains("«Амбар»") && g.text().contains("1 500"), g.text());
		DayReport.Fact l = r.facts().stream().filter(f -> f.kind().equals("build_loss")).findFirst().orElseThrow();
		assertTrue(l.text().contains("50%") && l.text().contains("замечены: Токсик Рик (sh5dawg)"), l.text());
		assertEquals(1, r.facts().stream().filter(f -> f.kind().equals("build_new")).count(), "деревня — не новая стройка");
	}

	@Test
	void digestHasSectionsAndHeadlines() {
		Day d = new Day().today(rick, Keys.ONLINE, 7200L).today(rick, "act:building", 5000L).today(rick, Keys.PLACED, 2300L)
				.active(rick, 0).death(rick, 0, 600, "крипер");
		DayReport r = Analyzer.analyze(d.build(), 25);
		String text = DigestWriter.write(r, List.of("сага о крипере, 3-й день"), List.of("Вчерашний заголовок"));
		assertTrue(text.startsWith("Выпуск за 2026-10-10, суббота."), text);
		assertTrue(text.contains("Токсик Рик (sh5dawg): 2 ч"), text);
		assertTrue(text.contains("стройка 1 ч 23 мин"), text);
		assertTrue(text.contains("Поставлено блоков 2 300"), text);
		assertTrue(text.contains("Продолжающиеся истории"), text);
		assertTrue(text.contains("не повторяй"), text);
		assertFalse(text.contains("hidden"));
	}
}
