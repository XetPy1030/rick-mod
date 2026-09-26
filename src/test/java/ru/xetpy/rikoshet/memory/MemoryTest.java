package ru.xetpy.rikoshet.memory;

import org.junit.jupiter.api.Test;
import ru.xetpy.rikoshet.chronicle.analysis.DayReport;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTest {
	static final long DAY = 86_400_000L;
	final UUID rick = UUID.randomUUID();
	final UUID morty = UUID.randomUUID();
	final long now = 100 * DAY;

	Memory ep(String text, UUID about, int importance, long ageDays, String key, String... tags) {
		return new Memory(text, Set.of(about), Set.of(tags), importance, now - ageDays * DAY, Memory.halfLife(importance), key);
	}

	@Test
	void importantMemoriesFadeSlower() {
		Recall.Query q = new Recall.Query(List.of(rick), Set.of(), 1000, now);
		double dragon = Recall.score(ep("убил дракона", rick, 80, 30, "a"), q);
		double death = Recall.score(ep("умер от зомби", rick, 3, 30, "b"), q);
		double fresh = Recall.score(ep("умер от зомби", rick, 3, 0, "c"), q);
		assertTrue(dragon > death, "дракон помнится через месяц, зомби — нет");
		assertTrue(fresh > death);
	}

	@Test
	void tagsBringOthersMemoriesAndUnrelatedIsIgnored() {
		Recall.Query q = new Recall.Query(List.of(rick), Set.of("death", "killer:creeper"), 1000, now);
		assertTrue(Recall.score(ep("Морти тоже взорвал крипер", morty, 5, 1, "x", "death", "killer:creeper"), q) > 0);
		assertEquals(0, Recall.score(ep("Морти нашёл алмаз", morty, 10, 0, "y", "first"), q));
	}

	@Test
	void budgetAndOnePerKey() {
		Recall.Query q = new Recall.Query(List.of(rick), Set.of("death"), 60, now);
		List<Recall.Scored> got = Recall.recall(List.of(
				ep("первый крипер", rick, 20, 1, "same", "death"),
				ep("второй крипер", rick, 20, 2, "same", "death"),
				ep("очень длинное воспоминание, которое не влезет в оставшийся бюджет символов", rick, 10, 1, "long", "death"),
				ep("короткое", rick, 5, 1, "short", "death")), q);
		assertEquals(List.of("первый крипер", "короткое"), got.stream().map(s -> s.memory().text()).toList());
	}

	@Test
	void storiesContinueBreakAndSurviveReanalysis() {
		LocalDate d = LocalDate.parse("2026-10-10");
		DayReport r = new DayReport(d.toString(), "суббота", null, List.of(), List.of(),
				List.of(new DayReport.Fact("nemesis", rick.toString(), 20, "немезида — крипер"),
						new DayReport.Fact("record", null, 60, "рекорд без героя")),
				List.of(), List.of());
		String slot = "story:nemesis:" + rick;
		Map<String, Consolidator.StoryState> prev = new HashMap<>();
		assertEquals(1, Consolidator.stories(r, prev).getFirst().days(), "новая история");
		prev.put(slot, new Consolidator.StoryState(d.minusDays(1), 3, d.minusDays(3)));
		Consolidator.Story s = Consolidator.stories(r, prev).getFirst();
		assertEquals(4, s.days());
		assertEquals(d.minusDays(3), s.since());
		prev.put(slot, new Consolidator.StoryState(d, 4, d.minusDays(3)));
		assertEquals(4, Consolidator.stories(r, prev).getFirst().days(), "пересчёт того же дня не добавляет день");
		prev.put(slot, new Consolidator.StoryState(d.minusDays(5), 4, d.minusDays(8)));
		assertEquals(1, Consolidator.stories(r, prev).getFirst().days(), "перерыв — история заново");
		prev.put(slot, new Consolidator.StoryState(d.plusDays(1), 5, d.minusDays(3)));
		assertTrue(Consolidator.stories(r, prev).isEmpty(), "старый день не трогает свежую историю");
		assertEquals(1, Consolidator.stories(r, Map.of()).size(), "факт без героя — не история");
	}

	@Test
	void slotsFromProfilesAndRelations() {
		DayReport.Profile p = new DayReport.Profile(rick.toString(), "шахтёр", Map.of("mining", 0.7, "building", 0.2), 21, 3600, 5,
				"крипер", 4, 9, morty.toString(), 20_000, "plains");
		DayReport r = new DayReport("2026-10-10", "суббота", null, List.of(),
				List.of(new DayReport.Relation(rick.toString(), morty.toString(), "Рик", "Морти", "friends", "приятели", 3, 0, null)),
				List.of(), List.of(), List.of(p));
		Map<String, String> slots = new HashMap<>();
		Consolidator.slots(r, u -> u.equals(morty) ? "Морти" : "?").forEach(s -> slots.put(s.slot(), s.value()));
		assertEquals("по стилю игры — шахтёр (шахта 70%, стройка 20%)", slots.get("style"));
		assertEquals("больше всего времени проводит с Морти", slots.get("best_friend"));
		assertEquals("чаще всего гибнет: крипер (4 из 9 смертей за месяц)", slots.get("nemesis"));
		assertEquals("обычно играет около 21:00", slots.get("habit_time"));
		assertEquals("Рик и Морти — приятели", slots.get("relation"));
	}

	@Test
	void changeWorthiness() {
		MemoryStore.Row a = new MemoryStore.Row("u", "style", "по стилю игры — шахтёр (шахта 70%)", null, 15, 0, 0);
		MemoryStore.Row b = new MemoryStore.Row("u", "style", "по стилю игры — шахтёр (шахта 65%)", null, 15, 0, 0);
		MemoryStore.Row c = new MemoryStore.Row("u", "style", "по стилю игры — строитель (стройка 60%)", null, 15, 0, 0);
		assertEquals(false, MemoryService.changeWorthy("style", a, b), "доли поменялись — не новость");
		assertEquals(true, MemoryService.changeWorthy("style", a, c));
		assertEquals(false, MemoryService.changeWorthy("habit_time", a, c));
	}
}
