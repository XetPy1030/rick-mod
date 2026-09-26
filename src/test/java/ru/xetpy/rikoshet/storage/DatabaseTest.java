package ru.xetpy.rikoshet.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import ru.xetpy.rikoshet.ai.AiLogEntry;
import ru.xetpy.rikoshet.persona.Archetype;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {
	@Test
	void splitSql() {
		List<String> parts = Database.split("-- comment\nCREATE TABLE a (x INT); -- tail\nCREATE INDEX i ON a (x);\n");
		assertEquals(List.of("CREATE TABLE a (x INT)", "CREATE INDEX i ON a (x)"), parts);
	}

	@Test
	void migrateAndRoundTrip(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("r.db");
		UUID u = UUID.randomUUID();
		LocalDate day = LocalDate.parse("2026-09-26");
		try (Database db = Database.open(file, LoggerFactory.getLogger("test"))) {
			PlayerStore players = new PlayerStore(db);
			assertNull(players.join(u, "Kate", 1000));
			players.leave(u, 5000, 4);
			players.setOptOut(u, true);
			RoleStore roles = new RoleStore(db);
			roles.set(u, "Kate", "Джессика", 10);
			roles.note(u, "одноклассница", 11);
			roles.archetype(u, Archetype.MORTY, 12);
			roles.set(u, "Kate", "Злая Джессика", 13);
			DailyStats stats = new DailyStats(db, () -> day);
			stats.increment(u, DailyStats.DEATHS);
			stats.increment(u, DailyStats.DEATHS);
			AiLogStore log = new AiLogStore(db);
			log.write(new AiLogEntry(Instant.now(), day, "flavor", "death", "m", "ok", 1, 0, 1, 0, 10, 0.25, u, "{}", null));
			log.write(new AiLogEntry(Instant.now(), day, "flavor", "death", "m", "error", 1, 0, 1, 0, 10, 0.5, null, null, "x"));
			NoteStore notes = new NoteStore(db);
			notes.add(u, "rick", "любит лаву", 100);
			assertEquals(0.75, log.spent(day), 1e-9);
		}
		// Второе открытие: миграции не повторяются, данные на месте
		try (Database db = Database.open(file, LoggerFactory.getLogger("test"))) {
			PlayerStore players = new PlayerStore(db);
			players.load();
			assertEquals(4, players.get(u).playtimeSeconds());
			assertTrue(players.optedOut(u));
			assertEquals(u, players.byName("kate").orElseThrow().uuid());
			RoleStore roles = new RoleStore(db);
			roles.load();
			assertEquals("Злая Джессика", roles.get(u).title());
			assertEquals(Archetype.MORTY, roles.get(u).archetype(), "ручной архетип переживает смену названия");
			assertEquals("одноклассница", roles.get(u).note());
			DailyStats stats = new DailyStats(db, () -> day);
			stats.load();
			assertEquals(2, stats.get(u, DailyStats.DEATHS));
			NoteStore notes = new NoteStore(db);
			notes.load();
			assertEquals(List.of("любит лаву"), notes.recent(u, "rick"));
			assertEquals(Database.MIGRATIONS.size(), (int) db.call(c -> {
				try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM schema_version")) {
					return rs.next() ? rs.getInt(1) : -1;
				}
			}));
			Retention.purge(db, LoggerFactory.getLogger("test"), System.currentTimeMillis() + 100L * 86_400_000, 14, 90);
			notes.load();
			assertFalse(notes.recent(u, "rick").contains("любит лаву"));
		}
	}
}
