package ru.xetpy.rikoshet.quest;

import ru.xetpy.rikoshet.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Репутации игроков: таблицы reputation и reputation_log. Пока шкала одна — science,
 * «Полезность для науки» у Рика, от −100 до +100. Читается при старте, пишется в потоке БД.
 */
public final class Reputation {
	public static final String SCIENCE = "science";

	/** Изменение: было, стало, сменился ли уровень. */
	public record Change(int before, int after, Level from, Level to) {
		public boolean levelChanged() {
			return from != to;
		}

		public int delta() {
			return after - before;
		}
	}

	private final Database db;
	private final Map<UUID, Integer> science = new ConcurrentHashMap<>();

	public Reputation(Database db) {
		this.db = db;
	}

	public void load() throws SQLException {
		science.clear();
		db.call(c -> {
			try (PreparedStatement st = c.prepareStatement("SELECT uuid, value FROM reputation WHERE scale = ?")) {
				st.setString(1, SCIENCE);
				try (ResultSet rs = st.executeQuery()) {
					while (rs.next()) {
						science.put(UUID.fromString(rs.getString(1)), rs.getInt(2));
					}
				}
			}
			return null;
		});
	}

	/** Новичок начинает с нуля — «Лаборант». */
	public int get(UUID player) {
		return science.getOrDefault(player, 0);
	}

	public Level level(UUID player) {
		return Level.of(get(player));
	}

	/**
	 * Прибавить (или отнять) и записать в журнал. Значение держится в пределах шкалы.
	 *
	 * @param source talk, quest, admin
	 */
	public Change add(UUID player, int delta, String source, String reason, long now, String day) {
		int before = get(player);
		int after = clamp(before + delta);
		science.put(player, after);
		db.execute("репутация", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO reputation (uuid, scale, value, updated) VALUES (?, ?, ?, ?)
					ON CONFLICT (uuid, scale) DO UPDATE SET value = excluded.value, updated = excluded.updated""")) {
				st.setString(1, player.toString());
				st.setString(2, SCIENCE);
				st.setInt(3, after);
				st.setLong(4, now);
				st.executeUpdate();
			}
			try (PreparedStatement st = c.prepareStatement(
					"INSERT INTO reputation_log (ts, day, uuid, scale, delta, value, source, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
				st.setLong(1, now);
				st.setString(2, day);
				st.setString(3, player.toString());
				st.setString(4, SCIENCE);
				st.setInt(5, after - before);
				st.setInt(6, after);
				st.setString(7, source);
				st.setString(8, reason);
				st.executeUpdate();
			}
		});
		return new Change(before, after, Level.of(before), Level.of(after));
	}

	static int clamp(int v) {
		return Math.max(Level.MIN, Math.min(Level.MAX, v));
	}
}
