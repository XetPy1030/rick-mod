package ru.xetpy.rikoshet.builds;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.xetpy.rikoshet.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Постройки (build_site, build_contrib). Синхронно — только загрузка при старте. */
public final class BuildStore {
	private final Database db;

	public BuildStore(Database db) {
		this.db = db;
	}

	public List<Site> load() throws SQLException {
		return db.call(c -> {
			Map<String, Site> sites = new HashMap<>();
			try (PreparedStatement st = c.prepareStatement("""
					SELECT dim, cx, cz, owner, placed, first_ts, build_ts, scan_ts, artificial, baseline, scan, history, name, description, named_at
					FROM build_site"""); ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					Site s = new Site(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(6));
					String owner = rs.getString(4);
					s.owner = owner == null ? null : UUID.fromString(owner);
					s.placed = rs.getLong(5);
					s.buildTs = rs.getLong(7);
					s.scanTs = rs.getLong(8);
					s.artificial = rs.getLong(9);
					long b = rs.getLong(10);
					s.baseline = rs.wasNull() ? null : b;
					s.scan = json(rs.getString(11));
					JsonObject h = json(rs.getString(12));
					if (h != null) {
						h.entrySet().forEach(e -> s.history.put(e.getKey(), e.getValue().getAsLong()));
					}
					s.name = rs.getString(13);
					s.description = rs.getString(14);
					long n = rs.getLong(15);
					s.namedAt = rs.wasNull() ? null : n;
					sites.put(s.key(), s);
				}
			}
			try (PreparedStatement st = c.prepareStatement("SELECT dim, cx, cz, uuid, placed FROM build_contrib");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					Site s = sites.get(Site.key(rs.getString(1), rs.getInt(2), rs.getInt(3)));
					if (s != null) {
						s.builders.put(UUID.fromString(rs.getString(4)), rs.getLong(5));
					}
				}
			}
			return new ArrayList<>(sites.values());
		});
	}

	/** Записать постройку целиком (снимок полей) и вклад строителя. */
	public void save(Site s, UUID builder, long placed, long mined, long ts) {
		Object[] v = {s.dim, s.cx, s.cz, s.owner == null ? null : s.owner.toString(), s.placed, s.firstTs, s.buildTs,
				s.scanTs == 0 ? null : s.scanTs, s.artificial, s.baseline, s.scan == null ? null : s.scan.toString(),
				history(s), s.name, s.description, s.namedAt};
		db.execute("build", c -> {
			boolean auto = c.getAutoCommit();
			c.setAutoCommit(false);
			try {
				try (PreparedStatement st = c.prepareStatement("""
						INSERT INTO build_site (dim, cx, cz, owner, placed, first_ts, build_ts, scan_ts, artificial, baseline, scan, history,
						  name, description, named_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						ON CONFLICT (dim, cx, cz) DO UPDATE SET owner = excluded.owner, placed = excluded.placed,
						  build_ts = excluded.build_ts, scan_ts = excluded.scan_ts, artificial = excluded.artificial,
						  baseline = excluded.baseline, scan = excluded.scan, history = excluded.history, name = excluded.name,
						  description = excluded.description, named_at = excluded.named_at""")) {
					for (int i = 0; i < v.length; i++) {
						st.setObject(i + 1, v[i]);
					}
					st.executeUpdate();
				}
				if (builder != null) {
					try (PreparedStatement st = c.prepareStatement("""
							INSERT INTO build_contrib (dim, cx, cz, uuid, placed, mined, last_ts) VALUES (?, ?, ?, ?, ?, ?, ?)
							ON CONFLICT (dim, cx, cz, uuid) DO UPDATE SET placed = placed + excluded.placed,
							  mined = mined + excluded.mined, last_ts = excluded.last_ts""")) {
						st.setString(1, s.dim);
						st.setInt(2, s.cx);
						st.setInt(3, s.cz);
						st.setString(4, builder.toString());
						st.setLong(5, placed);
						st.setLong(6, mined);
						st.setLong(7, ts);
						st.executeUpdate();
					}
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

	private static String history(Site s) {
		JsonObject h = new JsonObject();
		s.history.forEach(h::addProperty);
		return h.toString();
	}

	private static JsonObject json(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		try {
			return JsonParser.parseString(s).getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
