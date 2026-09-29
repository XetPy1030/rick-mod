package ru.xetpy.rikoshet.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Мелкое состояние мода в таблице kv: поставлена ли Цитадель, куда сдвинуты её точки. Читается при старте. */
public final class KvStore {
	private final Database db;
	private final Map<String, String> values = new ConcurrentHashMap<>();

	public KvStore(Database db) {
		this.db = db;
	}

	public void load() throws SQLException {
		values.clear();
		db.call(c -> {
			try (PreparedStatement st = c.prepareStatement("SELECT key, value FROM kv"); ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					values.put(rs.getString(1), rs.getString(2));
				}
			}
			return null;
		});
	}

	public String get(String key) {
		return values.get(key);
	}

	public void put(String key, String value, long now) {
		values.put(key, value);
		db.execute("kv " + key, c -> {
			try (PreparedStatement st = c.prepareStatement(
					"INSERT INTO kv (key, value, updated) VALUES (?, ?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated = excluded.updated")) {
				st.setString(1, key);
				st.setString(2, value);
				st.setLong(3, now);
				st.executeUpdate();
			}
		});
	}

	public void remove(String key) {
		values.remove(key);
		db.execute("kv -" + key, c -> {
			try (PreparedStatement st = c.prepareStatement("DELETE FROM kv WHERE key = ?")) {
				st.setString(1, key);
				st.executeUpdate();
			}
		});
	}
}
