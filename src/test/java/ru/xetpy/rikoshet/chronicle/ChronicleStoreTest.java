package ru.xetpy.rikoshet.chronicle;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import ru.xetpy.rikoshet.chronicle.analysis.DayData;
import ru.xetpy.rikoshet.chronicle.social.PairKeys;
import ru.xetpy.rikoshet.storage.DailyStats;
import ru.xetpy.rikoshet.storage.Database;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChronicleStoreTest {
	@Test
	void writeLoadAndDayData(@TempDir Path dir) throws Exception {
		ZoneId z = ZoneId.of("Europe/Moscow");
		LocalDate day = LocalDate.parse("2026-10-10");
		long t0 = day.atStartOfDay(z).toInstant().toEpochMilli() + 3_600_000;
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		try (Database db = Database.open(dir.resolve("r.db"), LoggerFactory.getLogger("test"))) {
			ChronicleStore store = new ChronicleStore(db);
			DailyStats stats = new DailyStats(db, () -> day);
			stats.add(a, day.minusDays(1), Map.of(Keys.ONLINE, 3000L, Keys.DEATHS, 7L));
			stats.add(a, day, Map.of(Keys.ONLINE, 4000L, Keys.DEATHS, 9L, "act:mining", 3000L, "mined:stone", 500L));
			stats.add(a, day, Map.of(Keys.ONLINE, 100L));
			store.seen(a, "biome", "plains", t0);
			store.seen(b, "biome", "plains", t0 + 1);
			store.seen(a, "biome", "plains", t0 + 2);
			store.cells(a, List.of(new ChronicleStore.CellDelta("overworld", -3, 7, 5000)), t0);
			store.cells(a, List.of(new ChronicleStore.CellDelta("overworld", -3, 7, 4000)), t0 + 1);
			store.cells(a, List.of(new ChronicleStore.CellDelta("the_nether", 0, 0, 100)), t0 + 1);
			JsonObject data = new JsonObject();
			data.addProperty("killer", "крипер");
			store.event(new ChronicleEvent(t0, day.toString(), a, ChronicleEvent.DEATH, "explosion", 3, data));
			store.session(a, t0, t0 + 3_600_000, 60, 1, SessionSummary.json(Map.of("act:mining", 3000L, Keys.MINED, 500L), 3600, 1));
			store.pairs(day, Map.of(PairKeys.pair(a, b) + "|" + PairKeys.TOGETHER, 1200L));
			store.pairs(day, Map.of(PairKeys.pair(a, b) + "|" + PairKeys.TOGETHER, 300L));
			store.totals(a, Map.of(Keys.DEATHS, 42L), t0);

			ChronicleStore.Loaded l = store.load();
			assertTrue(l.seen().get(a).contains(ChronicleStore.seenKey("biome", "plains")));
			assertEquals(a, l.firsts().get(ChronicleStore.seenKey("biome", "plains")), "первым был a");
			var homes = ChronicleStore.homes(l.cells(), 7200);
			assertEquals(-3, homes.get(a).cx());
			assertEquals(9000, homes.get(a).seconds());
			assertEquals(42L, l.totals().get(a).get(Keys.DEATHS));
			assertEquals("1 ч: шахта 50 мин; добыл блоков 500; смертей 1", SessionSummary.text(l.lastSessions().get(a)));

			DayData d = store.dayData(day, z, Map.of(a, new DayData.Person("Kate", null, false, 0))).get();
			assertEquals(4100L, d.countersOf(a).get(Keys.ONLINE));
			assertEquals(7L, d.history().get(a).get(day.minusDays(1)).get(Keys.DEATHS));
			assertEquals(7L, d.serverBest().get(Keys.DEATHS).value());
			assertEquals(List.of(day.minusDays(1), day), d.activeDays().get(a));
			assertEquals(1, d.events().size());
			assertEquals("крипер", d.events().getFirst().data().get("killer").getAsString());
			assertEquals(1, d.sessions().size());
			assertEquals(1500L, d.pairs().get(day).getFirst().value());
			assertEquals(2, d.seen().size());
		}
	}
}
