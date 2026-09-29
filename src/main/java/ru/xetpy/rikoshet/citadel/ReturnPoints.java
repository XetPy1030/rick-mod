package ru.xetpy.rikoshet.citadel;

import ru.xetpy.rikoshet.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Откуда игрок ушёл в Цитадель — таблица citadel_return. Переживает перезапуск: портал обратно вернёт туда же. */
public final class ReturnPoints {
	public record Point(String dim, double x, double y, double z, float yaw, float pitch) {
	}

	private final Database db;
	private final Map<UUID, Point> points = new ConcurrentHashMap<>();

	public ReturnPoints(Database db) {
		this.db = db;
	}

	public void load() throws SQLException {
		points.clear();
		db.call(c -> {
			try (PreparedStatement st = c.prepareStatement("SELECT uuid, dim, x, y, z, yaw, pitch FROM citadel_return");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					points.put(UUID.fromString(rs.getString(1)), new Point(rs.getString(2), rs.getDouble(3), rs.getDouble(4),
							rs.getDouble(5), rs.getFloat(6), rs.getFloat(7)));
				}
			}
			return null;
		});
	}

	public Point get(UUID player) {
		return points.get(player);
	}

	public void put(UUID player, Point p, long now) {
		points.put(player, p);
		db.execute("возврат из Цитадели", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO citadel_return (uuid, dim, x, y, z, yaw, pitch, ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (uuid) DO UPDATE SET dim = excluded.dim, x = excluded.x, y = excluded.y, z = excluded.z,
					yaw = excluded.yaw, pitch = excluded.pitch, ts = excluded.ts""")) {
				st.setString(1, player.toString());
				st.setString(2, p.dim());
				st.setDouble(3, p.x());
				st.setDouble(4, p.y());
				st.setDouble(5, p.z());
				st.setFloat(6, p.yaw());
				st.setFloat(7, p.pitch());
				st.setLong(8, now);
				st.executeUpdate();
			}
		});
	}
}
