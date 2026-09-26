package ru.xetpy.rikoshet.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Счётчики игрока за день (player_stat_daily). Этап 1 писал сюда смерти, с этапа 2 летопись
 * пишет всё: приросты ванильной статистики, свои счётчики, занятия (docs/design/chronicle.md).
 * Сегодняшние — в памяти для контекста ИИ и /rick me, запись — в фоне. День — по timezone конфига.
 */
public final class DailyStats {
	public static final String DEATHS = "deaths";

	private final Database db;
	private final Supplier<LocalDate> today;
	private final Map<UUID, Map<String, Long>> counters = new ConcurrentHashMap<>();
	private volatile LocalDate day;

	public DailyStats(Database db, Supplier<LocalDate> today) {
		this.db = db;
		this.today = today;
		this.day = today.get();
	}

	public void load() throws SQLException {
		counters.clear();
		LocalDate d = today.get();
		day = d;
		db.call(c -> {
			try (PreparedStatement st = c.prepareStatement("SELECT uuid, key, value FROM player_stat_daily WHERE day = ?")) {
				st.setString(1, d.toString());
				try (ResultSet rs = st.executeQuery()) {
					while (rs.next()) {
						mine(UUID.fromString(rs.getString(1))).put(rs.getString(2), rs.getLong(3));
					}
				}
			}
			return null;
		});
	}

	public int get(UUID uuid, String key) {
		roll();
		Map<String, Long> m = counters.get(uuid);
		return m == null ? 0 : (int) (long) m.getOrDefault(key, 0L);
	}

	/** Все счётчики игрока за сегодня, копия. */
	public Map<String, Long> today(UUID uuid) {
		roll();
		Map<String, Long> m = counters.get(uuid);
		return m == null ? Map.of() : new HashMap<>(m);
	}

	public int increment(UUID uuid, String key) {
		roll();
		long v = mine(uuid).merge(key, 1L, Long::sum);
		String d = day.toString();
		db.execute("stat " + key, c -> {
			try (PreparedStatement st = c.prepareStatement(UPSERT)) {
				bind(st, uuid, d, key, 1);
				st.executeUpdate();
			}
		});
		return (int) v;
	}

	/** Прибавить пачку счётчиков за день одной транзакцией. Вчерашний день в памяти не держим. */
	public void add(UUID uuid, LocalDate forDay, Map<String, Long> delta) {
		if (delta.isEmpty()) {
			return;
		}
		roll();
		if (forDay.equals(day)) {
			Map<String, Long> m = mine(uuid);
			delta.forEach((k, v) -> m.merge(k, v, Long::sum));
		}
		Map<String, Long> copy = Map.copyOf(delta);
		String d = forDay.toString();
		db.execute("stats " + uuid, c -> {
			boolean auto = c.getAutoCommit();
			c.setAutoCommit(false);
			try (PreparedStatement st = c.prepareStatement(UPSERT)) {
				for (var e : copy.entrySet()) {
					bind(st, uuid, d, e.getKey(), e.getValue());
					st.addBatch();
				}
				st.executeBatch();
				c.commit();
			} catch (SQLException e) {
				c.rollback();
				throw e;
			} finally {
				c.setAutoCommit(auto);
			}
		});
	}

	private static final String UPSERT = """
			INSERT INTO player_stat_daily (uuid, day, key, value) VALUES (?, ?, ?, ?)
			ON CONFLICT (uuid, day, key) DO UPDATE SET value = value + excluded.value""";

	private static void bind(PreparedStatement st, UUID uuid, String day, String key, long v) throws SQLException {
		st.setString(1, uuid.toString());
		st.setString(2, day);
		st.setString(3, key);
		st.setLong(4, v);
	}

	private Map<String, Long> mine(UUID uuid) {
		return counters.computeIfAbsent(uuid, u -> new ConcurrentHashMap<>());
	}

	private void roll() {
		LocalDate d = today.get();
		if (!d.equals(day)) {
			day = d;
			counters.clear();
		}
	}
}
