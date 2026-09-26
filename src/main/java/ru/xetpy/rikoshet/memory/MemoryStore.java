package ru.xetpy.rikoshet.memory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.xetpy.rikoshet.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Знания-слоты (memory_fact): текущие значения и история. docs/architecture/memory.md */
public final class MemoryStore {
	private final Database db;

	public MemoryStore(Database db) {
		this.db = db;
	}

	public record Row(String subject, String slot, String value, JsonObject data, int importance, long firstTs, long updatedTs) {
	}

	/** Текущие значения всех слотов. Синхронно — только при старте. */
	public List<Row> loadCurrent() throws SQLException {
		return db.call(c -> {
			List<Row> out = new ArrayList<>();
			try (PreparedStatement st = c.prepareStatement(
					"SELECT subject, slot, value, data, importance, first_ts, updated_ts FROM memory_fact WHERE until_ts IS NULL");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					String d = rs.getString(4);
					out.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3),
							d == null ? null : JsonParser.parseString(d).getAsJsonObject(), rs.getInt(5), rs.getLong(6), rs.getLong(7)));
				}
			}
			return out;
		});
	}

	/** Значение подтвердилось. */
	public void touch(String subject, String slot, JsonObject data, long ts) {
		db.execute("memory touch", c -> {
			try (PreparedStatement st = c.prepareStatement(
					"UPDATE memory_fact SET updated_ts = ?, data = COALESCE(?, data) WHERE subject = ? AND slot = ? AND until_ts IS NULL")) {
				st.setLong(1, ts);
				st.setString(2, data == null ? null : data.toString());
				st.setString(3, subject);
				st.setString(4, slot);
				st.executeUpdate();
			}
		});
	}

	/** Новое значение: прежнее уходит в историю. */
	public void replace(Row row, long ts) {
		db.execute("memory replace", c -> {
			boolean auto = c.getAutoCommit();
			c.setAutoCommit(false);
			try {
				close(c, row.subject(), row.slot(), ts);
				try (PreparedStatement st = c.prepareStatement("""
						INSERT INTO memory_fact (subject, slot, value, data, importance, confidence, first_ts, updated_ts, source)
						VALUES (?, ?, ?, ?, ?, 1, ?, ?, 'chronicle')""")) {
					st.setString(1, row.subject());
					st.setString(2, row.slot());
					st.setString(3, row.value());
					st.setString(4, row.data() == null ? null : row.data().toString());
					st.setInt(5, row.importance());
					st.setLong(6, row.firstTs());
					st.setLong(7, row.updatedTs());
					st.executeUpdate();
				}
				c.commit();
			} catch (SQLException e) {
				c.rollback();
				throw e;
			} finally {
				c.setAutoCommit(auto);
			}
		});
	}

	/** Обновить текущее значение на месте, без истории: продолжение сюжетной линии. */
	public void update(Row row) {
		db.execute("memory update", c -> {
			try (PreparedStatement st = c.prepareStatement(
					"UPDATE memory_fact SET value = ?, data = ?, importance = ?, updated_ts = ? WHERE subject = ? AND slot = ? AND until_ts IS NULL")) {
				st.setString(1, row.value());
				st.setString(2, row.data() == null ? null : row.data().toString());
				st.setInt(3, row.importance());
				st.setLong(4, row.updatedTs());
				st.setString(5, row.subject());
				st.setString(6, row.slot());
				st.executeUpdate();
			}
		});
	}

	/** Слот больше не действует (закончилась сюжетная линия). */
	public void end(String subject, String slot, long ts) {
		db.execute("memory end", c -> close(c, subject, slot, ts));
	}

	private static void close(java.sql.Connection c, String subject, String slot, long ts) throws SQLException {
		try (PreparedStatement st = c.prepareStatement(
				"UPDATE memory_fact SET until_ts = ? WHERE subject = ? AND slot = ? AND until_ts IS NULL")) {
			st.setLong(1, ts);
			st.setString(2, subject);
			st.setString(3, slot);
			st.executeUpdate();
		}
	}

	/**
	 * Забыть игрока: знания о нём и его парах, эпизоды летописи о нём, заметки разговоров.
	 * Счётчики летописи (статистика) остаются. Возвращает, сколько строк удалено.
	 */
	public CompletableFuture<Integer> forget(UUID uuid) {
		String u = uuid.toString();
		return db.submit(c -> {
			int n = 0;
			try (PreparedStatement st = c.prepareStatement("DELETE FROM memory_fact WHERE subject = ? OR subject LIKE ? OR slot LIKE ?")) {
				st.setString(1, u);
				st.setString(2, "pair:%" + u + "%");
				st.setString(3, "%" + u);
				n += st.executeUpdate();
			}
			try (PreparedStatement st = c.prepareStatement("DELETE FROM chronicle_event WHERE uuid = ?")) {
				st.setString(1, u);
				n += st.executeUpdate();
			}
			try (PreparedStatement st = c.prepareStatement("DELETE FROM dialogue_log WHERE uuid = ?")) {
				st.setString(1, u);
				n += st.executeUpdate();
			}
			return n;
		});
	}
}
