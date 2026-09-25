package ru.xetpy.rikoshet.storage;

import org.slf4j.Logger;

import java.sql.PreparedStatement;
import java.util.concurrent.TimeUnit;

/** Чистка по срокам хранения из конфига: при старте сервера, в фоне. */
public final class Retention {
	private Retention() {
	}

	public static void purge(Database db, Logger log, long now, int dialogueDays, int aiLogDays) {
		db.execute("retention", c -> {
			int dialogue;
			int ai;
			try (PreparedStatement st = c.prepareStatement("DELETE FROM dialogue_log WHERE ts < ?")) {
				st.setLong(1, now - TimeUnit.DAYS.toMillis(dialogueDays));
				dialogue = st.executeUpdate();
			}
			try (PreparedStatement st = c.prepareStatement("DELETE FROM ai_log WHERE ts < ?")) {
				st.setLong(1, now - TimeUnit.DAYS.toMillis(aiLogDays));
				ai = st.executeUpdate();
			}
			if (dialogue + ai > 0) {
				log.info("[БД] чистка: dialogue_log −{}, ai_log −{}", dialogue, ai);
			}
		});
	}
}
