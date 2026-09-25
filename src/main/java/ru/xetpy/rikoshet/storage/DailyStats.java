package ru.xetpy.rikoshet.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Счётчики игрока за день (player_stat_daily): смерти, смерти по причинам. Сегодняшние —
 * в памяти для контекста ИИ, в БД — для газеты этапа 2. День — по timezone конфига.
 */
public final class DailyStats {
	public static final String DEATHS = "deaths";

	private final Database db;
	private final Supplier<LocalDate> today;
	private final Map<String, Integer> counters = new ConcurrentHashMap<>();
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
						counters.put(rs.getString(1) + "|" + rs.getString(2), rs.getInt(3));
					}
				}
			}
			return null;
		});
	}

	public int get(UUID uuid, String key) {
		roll();
		return counters.getOrDefault(uuid + "|" + key, 0);
	}

	public int increment(UUID uuid, String key) {
		roll();
		String d = day.toString();
		int v = counters.merge(uuid + "|" + key, 1, Integer::sum);
		db.execute("stat " + key, c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO player_stat_daily (uuid, day, key, value) VALUES (?, ?, ?, 1)
					ON CONFLICT (uuid, day, key) DO UPDATE SET value = value + 1""")) {
				st.setString(1, uuid.toString());
				st.setString(2, d);
				st.setString(3, key);
				st.executeUpdate();
			}
		});
		return v;
	}

	private void roll() {
		LocalDate d = today.get();
		if (!d.equals(day)) {
			day = d;
			counters.clear();
		}
	}
}
