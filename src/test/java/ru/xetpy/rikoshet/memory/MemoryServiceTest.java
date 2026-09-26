package ru.xetpy.rikoshet.memory;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import ru.xetpy.rikoshet.chronicle.ChronicleEvent;
import ru.xetpy.rikoshet.chronicle.ChronicleStore;
import ru.xetpy.rikoshet.chronicle.analysis.DayReport;
import ru.xetpy.rikoshet.storage.Database;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceTest {
	static final ZoneId Z = ZoneId.of("Europe/Moscow");
	static final LocalDate DAY = LocalDate.parse("2026-10-10");

	@Test
	void recallConsolidateAndForget(@TempDir Path dir) throws Exception {
		UUID rick = UUID.randomUUID();
		UUID morty = UUID.randomUUID();
		long now = DAY.atTime(12, 0).atZone(Z).toInstant().toEpochMilli();
		Clock clock = Clock.fixed(Instant.ofEpochMilli(now), Z);
		try (Database db = Database.open(dir.resolve("m.db"), LoggerFactory.getLogger("test"))) {
			ChronicleStore cs = new ChronicleStore(db);
			JsonObject d = new JsonObject();
			d.addProperty("cause", "взрыв");
			d.addProperty("group", "explosion");
			d.addProperty("killer", "крипер");
			d.addProperty("killer_type", "creeper");
			d.addProperty("diamonds", 23);
			cs.event(new ChronicleEvent(now - 2 * 86_400_000L, DAY.minusDays(2).toString(), rick, ChronicleEvent.DEATH, "explosion", 40, d));
			JsonObject g = new JsonObject();
			g.addProperty("target", rick.toString());
			g.addProperty("item", "diamond");
			g.addProperty("count", 5);
			cs.event(new ChronicleEvent(now - 86_400_000L, DAY.minusDays(1).toString(), morty, "gift", "diamond", 20, g));
			cs.event(new ChronicleEvent(now - 1000, DAY.toString(), rick, ChronicleEvent.DEATH, "explosion", 3, d));

			Map<UUID, String> names = Map.of(rick, "Токсик Рик (sh5dawg)", morty, "Морти (Benjurist)");
			MemoryService m = new MemoryService(LoggerFactory.getLogger("test"), clock, () -> Z, new MemoryStore(db), cs,
					u -> names.getOrDefault(u, "?"));

			String got = m.recall(List.of(rick), Set.of("death", "killer:creeper", "group:explosion"), 400, 10_000);
			assertNotNull(got);
			assertTrue(got.contains("23 алмаза") && got.contains("2 дн. назад"), got);
			assertTrue(got.contains("похоже, передал Токсик Рик (sh5dawg) алмаз ×5 (вчера)"), got);
			assertFalse(got.contains("(сегодня)"), "свежая смерть — это сама ситуация: " + got);

			DayReport.Profile p = new DayReport.Profile(rick.toString(), "шахтёр", Map.of("mining", 0.8), 21, 3600, 5,
					"крипер", 4, 9, morty.toString(), 20_000, null);
			DayReport r = new DayReport(DAY.toString(), "суббота", null, List.of(), List.of(),
					List.of(new DayReport.Fact("nemesis", rick.toString(), 20, "у Токсик Рик (sh5dawg) есть немезида — крипер")),
					List.of(), List.of(p));
			m.consolidate(DAY.minusDays(1), r, DAY);
			assertEquals("больше всего времени проводит с Морти (Benjurist)", m.knowledge(rick).get("best_friend"));
			String withSlots = m.recall(List.of(rick), Set.of("death", "killer_label:крипер"), 600, 10_000);
			assertTrue(withSlots.contains("чаще всего гибнет: крипер"), withSlots);

			DayReport.Profile p2 = new DayReport.Profile(rick.toString(), "строитель", Map.of("building", 0.8), 21, 3600, 5,
					"крипер", 4, 9, null, 0, null);
			MemoryService.Consolidation c = m.consolidate(DAY, new DayReport(DAY.toString(), "воскресенье", null, List.of(), List.of(),
					List.of(new DayReport.Fact("nemesis", rick.toString(), 20, "у Токсик Рик (sh5dawg) есть немезида — крипер")),
					List.of(), List.of(p2)), DAY);
			assertTrue(c.changes().stream().anyMatch(x -> x.contains("шахтёр") && x.contains("строитель")), c.changes().toString());
			assertTrue(c.stories().getFirst().contains("2-й день"), c.stories().toString());
			assertEquals(List.of("у Токсик Рик (sh5dawg) есть немезида — крипер — 2-й день"), m.activeStories());

			// Перезапуск: знания и истории — из БД
			MemoryService again = new MemoryService(LoggerFactory.getLogger("test"), clock, () -> Z, new MemoryStore(db), cs,
					u -> names.getOrDefault(u, "?"));
			assertTrue(again.knowledge(rick).get("style").contains("строитель"));
			assertEquals(1, again.activeStories().size());

			int n = again.forget(rick).get();
			assertTrue(n >= 4, "удалено " + n);
			assertTrue(again.knowledge(rick).isEmpty());
			assertEquals(null, again.recall(List.of(rick), Set.of("death"), 400, 0));
		}
	}
}
