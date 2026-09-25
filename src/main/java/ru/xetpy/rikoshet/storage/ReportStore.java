package ru.xetpy.rikoshet.storage;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Жалобы игроков на реплики ИИ (/rick report). */
public final class ReportStore {
	private final Database db;
	/** Сколько жалоб не разобрано; держим в памяти, чтобы не ходить в БД из главного потока. */
	private final AtomicInteger open = new AtomicInteger();

	public record Report(long id, long ts, String reporterName, String line, String comment) {
	}

	public ReportStore(Database db) {
		this.db = db;
	}

	/** При старте, синхронно. */
	public void load() throws SQLException {
		open.set(db.call(c -> {
			try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM ai_report WHERE resolved = 0")) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}));
	}

	public int open() {
		return open.get();
	}

	public void add(long now, UUID reporter, String reporterName, String persona, String line, Long lineTs, String comment) {
		open.incrementAndGet();
		db.execute("ai_report", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO ai_report (ts, reporter_uuid, reporter_name, persona_id, line, line_ts, comment)
					VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
				st.setLong(1, now);
				st.setString(2, reporter.toString());
				st.setString(3, reporterName);
				st.setString(4, persona);
				st.setString(5, line);
				if (lineTs == null) {
					st.setNull(6, Types.INTEGER);
				} else {
					st.setLong(6, lineTs);
				}
				st.setString(7, comment);
				st.executeUpdate();
			}
		});
	}

	/** Неразобранные, старые первыми. */
	public CompletableFuture<List<Report>> unresolved(int limit) {
		return db.submit(c -> {
			List<Report> out = new ArrayList<>();
			try (PreparedStatement st = c.prepareStatement("""
					SELECT id, ts, reporter_name, line, comment FROM ai_report
					WHERE resolved = 0 ORDER BY id LIMIT ?""")) {
				st.setInt(1, limit);
				try (var rs = st.executeQuery()) {
					while (rs.next()) {
						out.add(new Report(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)));
					}
				}
			}
			return out;
		});
	}

	/** Пометить разобранной одну жалобу или все (id == null). Возвращает, сколько помечено. */
	public CompletableFuture<Integer> resolve(Long id) {
		return db.submit(c -> {
			int n;
			try (PreparedStatement st = c.prepareStatement(id == null
					? "UPDATE ai_report SET resolved = 1 WHERE resolved = 0"
					: "UPDATE ai_report SET resolved = 1 WHERE resolved = 0 AND id = ?")) {
				if (id != null) {
					st.setLong(1, id);
				}
				n = st.executeUpdate();
			}
			open.addAndGet(-n);
			return n;
		});
	}
}
