package ru.xetpy.rikoshet.chronicle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.xetpy.rikoshet.chronicle.analysis.DayData;
import ru.xetpy.rikoshet.chronicle.analysis.Metrics;
import ru.xetpy.rikoshet.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Таблицы летописи: события, «впервые», клетки, сессии, счётчики за всё время, пары, итоги дня,
 * профили. Все записи — в потоке БД; синхронно только загрузка при старте (docs/architecture/storage.md).
 */
public final class ChronicleStore {
	private final Database db;

	public ChronicleStore(Database db) {
		this.db = db;
	}

	// ---------- загрузка при старте ----------

	public record CellRow(UUID uuid, String dim, int cx, int cz, long seconds) {
	}

	public record Loaded(Map<UUID, Set<String>> seen, Map<String, UUID> firsts, List<CellRow> cells,
			Map<UUID, Map<String, Long>> totals, Map<UUID, JsonObject> lastSessions, Map<UUID, JsonObject> profiles) {
	}

	/** Ключ «впервые»: вид и id. */
	public static String seenKey(String kind, String id) {
		return kind + "|" + id;
	}

	public Loaded load() throws SQLException {
		return db.call(c -> {
			Map<UUID, Set<String>> seen = new HashMap<>();
			Map<String, UUID> firsts = new HashMap<>();
			try (PreparedStatement st = c.prepareStatement("SELECT uuid, kind, id FROM player_seen ORDER BY first_ts");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					UUID u = UUID.fromString(rs.getString(1));
					String k = seenKey(rs.getString(2), rs.getString(3));
					seen.computeIfAbsent(u, x -> new java.util.HashSet<>()).add(k);
					firsts.putIfAbsent(k, u);
				}
			}
			List<CellRow> cells = new ArrayList<>();
			try (PreparedStatement st = c.prepareStatement("SELECT uuid, dim, cx, cz, seconds FROM player_cell");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					cells.add(new CellRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getLong(5)));
				}
			}
			Map<UUID, Map<String, Long>> totals = new HashMap<>();
			try (PreparedStatement st = c.prepareStatement("SELECT uuid, key, value FROM player_total");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					totals.computeIfAbsent(UUID.fromString(rs.getString(1)), x -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
				}
			}
			Map<UUID, JsonObject> last = new HashMap<>();
			try (PreparedStatement st = c.prepareStatement("""
					SELECT uuid, summary FROM player_session
					WHERE id IN (SELECT MAX(id) FROM player_session GROUP BY uuid) AND summary IS NOT NULL""");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					last.put(UUID.fromString(rs.getString(1)), json(rs.getString(2)));
				}
			}
			Map<UUID, JsonObject> profiles = new HashMap<>();
			try (PreparedStatement st = c.prepareStatement("SELECT uuid, data FROM player_profile");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					profiles.put(UUID.fromString(rs.getString(1)), json(rs.getString(2)));
				}
			}
			return new Loaded(seen, firsts, cells, totals, last, profiles);
		});
	}

	/** Дом — клетка, где игрок провёл больше всего времени, от minSeconds. */
	public static Map<UUID, CellRow> homes(List<CellRow> cells, long minSeconds) {
		Map<UUID, CellRow> out = new HashMap<>();
		for (CellRow r : cells) {
			CellRow cur = out.get(r.uuid());
			if (r.seconds() >= minSeconds && (cur == null || r.seconds() > cur.seconds())) {
				out.put(r.uuid(), r);
			}
		}
		return out;
	}

	public CompletableFuture<List<CellRow>> cells() {
		return db.submit(c -> {
			List<CellRow> cells = new ArrayList<>();
			try (PreparedStatement st = c.prepareStatement("SELECT uuid, dim, cx, cz, seconds FROM player_cell");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					cells.add(new CellRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getLong(5)));
				}
			}
			return cells;
		});
	}

	// ---------- записи ----------

	public void event(ChronicleEvent e) {
		db.execute("event " + e.type(), c -> {
			try (PreparedStatement st = c.prepareStatement(
					"INSERT INTO chronicle_event (ts, day, uuid, type, subject, score, data) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
				st.setLong(1, e.ts());
				st.setString(2, e.day());
				st.setString(3, e.uuid() == null ? null : e.uuid().toString());
				st.setString(4, e.type());
				st.setString(5, e.subject());
				st.setInt(6, e.score());
				st.setString(7, e.data() == null ? null : e.data().toString());
				st.executeUpdate();
			}
		});
	}

	public void seen(UUID uuid, String kind, String id, long ts) {
		db.execute("seen", c -> {
			try (PreparedStatement st = c.prepareStatement(
					"INSERT OR IGNORE INTO player_seen (uuid, kind, id, first_ts) VALUES (?, ?, ?, ?)")) {
				st.setString(1, uuid.toString());
				st.setString(2, kind);
				st.setString(3, id);
				st.setLong(4, ts);
				st.executeUpdate();
			}
		});
	}

	public record CellDelta(String dim, int cx, int cz, long seconds) {
	}

	public void cells(UUID uuid, List<CellDelta> deltas, long ts) {
		if (deltas.isEmpty()) {
			return;
		}
		batch("cells", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO player_cell (uuid, dim, cx, cz, seconds, first_ts, last_ts) VALUES (?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (uuid, dim, cx, cz) DO UPDATE SET seconds = seconds + excluded.seconds, last_ts = excluded.last_ts""")) {
				for (CellDelta d : deltas) {
					st.setString(1, uuid.toString());
					st.setString(2, d.dim());
					st.setInt(3, d.cx());
					st.setInt(4, d.cz());
					st.setLong(5, d.seconds());
					st.setLong(6, ts);
					st.setLong(7, ts);
					st.addBatch();
				}
				st.executeBatch();
			}
		});
	}

	public void totals(UUID uuid, Map<String, Long> totals, long ts) {
		if (totals.isEmpty()) {
			return;
		}
		Map<String, Long> copy = Map.copyOf(totals);
		batch("totals", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO player_total (uuid, key, value, ts) VALUES (?, ?, ?, ?)
					ON CONFLICT (uuid, key) DO UPDATE SET value = excluded.value, ts = excluded.ts""")) {
				for (var e : copy.entrySet()) {
					st.setString(1, uuid.toString());
					st.setString(2, e.getKey());
					st.setLong(3, e.getValue());
					st.setLong(4, ts);
					st.addBatch();
				}
				st.executeBatch();
			}
		});
	}

	public void session(UUID uuid, long start, long end, long afk, int deaths, JsonObject summary) {
		db.execute("session", c -> {
			try (PreparedStatement st = c.prepareStatement(
					"INSERT INTO player_session (uuid, start_ts, end_ts, seconds, afk_s, deaths, summary) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
				st.setString(1, uuid.toString());
				st.setLong(2, start);
				st.setLong(3, end);
				st.setLong(4, Math.max(0, (end - start) / 1000));
				st.setLong(5, afk);
				st.setInt(6, deaths);
				st.setString(7, summary.toString());
				st.executeUpdate();
			}
		});
	}

	/** Счётчики пар из {@link ru.xetpy.rikoshet.chronicle.social.SocialTracker#drain()}: «a|b|key» → прирост. */
	public void pairs(LocalDate day, Map<String, Long> pending) {
		if (pending.isEmpty()) {
			return;
		}
		String d = day.toString();
		batch("pairs", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO pair_daily (day, a, b, key, value) VALUES (?, ?, ?, ?, ?)
					ON CONFLICT (day, a, b, key) DO UPDATE SET value = value + excluded.value""")) {
				for (var e : pending.entrySet()) {
					String[] p = e.getKey().split("\\|", 3);
					st.setString(1, d);
					st.setString(2, p[0]);
					st.setString(3, p[1]);
					st.setString(4, p[2]);
					st.setLong(5, e.getValue());
					st.addBatch();
				}
				st.executeBatch();
			}
		});
	}

	public void report(LocalDate day, String json, long ts) {
		db.execute("report", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO chronicle_day (day, created_at, report) VALUES (?, ?, ?)
					ON CONFLICT (day) DO UPDATE SET created_at = excluded.created_at, report = excluded.report""")) {
				st.setString(1, day.toString());
				st.setLong(2, ts);
				st.setString(3, json);
				st.executeUpdate();
			}
		});
	}

	public CompletableFuture<String> report(LocalDate day) {
		return db.submit(c -> {
			try (PreparedStatement st = c.prepareStatement("SELECT report FROM chronicle_day WHERE day = ?")) {
				st.setString(1, day.toString());
				try (ResultSet rs = st.executeQuery()) {
					return rs.next() ? rs.getString(1) : null;
				}
			}
		});
	}

	public void profiles(LocalDate day, Map<UUID, JsonObject> profiles) {
		if (profiles.isEmpty()) {
			return;
		}
		Map<UUID, JsonObject> copy = Map.copyOf(profiles);
		batch("profiles", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO player_profile (uuid, day, data) VALUES (?, ?, ?)
					ON CONFLICT (uuid) DO UPDATE SET day = excluded.day, data = excluded.data""")) {
				for (var e : copy.entrySet()) {
					st.setString(1, e.getKey().toString());
					st.setString(2, day.toString());
					st.setString(3, e.getValue().toString());
					st.addBatch();
				}
				st.executeBatch();
			}
		});
	}

	/** Были ли у дня счётчики: анализировать ли его. */
	public CompletableFuture<Boolean> hasData(LocalDate day) {
		return db.submit(c -> {
			try (PreparedStatement st = c.prepareStatement("SELECT 1 FROM player_stat_daily WHERE day = ? AND key = ? LIMIT 1")) {
				st.setString(1, day.toString());
				st.setString(2, Keys.ONLINE);
				try (ResultSet rs = st.executeQuery()) {
					return rs.next();
				}
			}
		});
	}

	// ---------- снимок для анализа ----------

	/** Ключи истории: метрики рекордов и вех, AFK, занятия и биомы. */
	static Set<String> historyKeys() {
		Set<String> keys = new LinkedHashSet<>();
		Metrics.ALL.forEach(m -> keys.add(m.key()));
		keys.addAll(Milestones.LIFETIME.keySet());
		keys.add(Keys.AFK);
		return keys;
	}

	/** Собрать всё для анализа дня. people — игроки с ролями и /rick off (из памяти главного потока). */
	public CompletableFuture<DayData> dayData(LocalDate day, ZoneId zone, Map<UUID, DayData.Person> people) {
		Map<UUID, DayData.Person> ppl = Map.copyOf(people);
		return db.submit(c -> load(c, day, zone, ppl));
	}

	static DayData load(Connection c, LocalDate day, ZoneId zone, Map<UUID, DayData.Person> people) throws SQLException {
		String d = day.toString();
		String from28 = day.minusDays(27).toString();
		long dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
		long dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli();
		long start28 = day.minusDays(27).atStartOfDay(zone).toInstant().toEpochMilli();
		Set<String> keys = historyKeys();
		String in = String.join(",", keys.stream().map(k -> "?").toList());

		Map<UUID, Map<String, Long>> counters = new HashMap<>();
		try (PreparedStatement st = c.prepareStatement("SELECT uuid, key, value FROM player_stat_daily WHERE day = ?")) {
			st.setString(1, d);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					counters.computeIfAbsent(UUID.fromString(rs.getString(1)), x -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
				}
			}
		}

		Map<UUID, Map<LocalDate, Map<String, Long>>> history = new HashMap<>();
		try (PreparedStatement st = c.prepareStatement("SELECT uuid, day, key, value FROM player_stat_daily WHERE day >= ? AND day < ? AND (key IN ("
				+ in + ") OR key LIKE 'act:%' OR key LIKE 'biome:%')")) {
			st.setString(1, from28);
			st.setString(2, d);
			int i = 3;
			for (String k : keys) {
				st.setString(i++, k);
			}
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					history.computeIfAbsent(UUID.fromString(rs.getString(1)), x -> new TreeMap<>())
							.computeIfAbsent(LocalDate.parse(rs.getString(2)), x -> new HashMap<>())
							.put(rs.getString(3), rs.getLong(4));
				}
			}
		}

		Map<String, DayData.Best> serverBest = new HashMap<>();
		try (PreparedStatement st = c.prepareStatement(
				"SELECT uuid, day, value FROM player_stat_daily WHERE key = ? AND day < ? ORDER BY value DESC LIMIT 1")) {
			for (Metrics.Metric m : Metrics.ALL) {
				st.setString(1, m.key());
				st.setString(2, d);
				try (ResultSet rs = st.executeQuery()) {
					if (rs.next()) {
						serverBest.put(m.key(), new DayData.Best(UUID.fromString(rs.getString(1)), LocalDate.parse(rs.getString(2)), rs.getLong(3)));
					}
				}
			}
		}

		Map<UUID, Map<String, Long>> personalBest = new HashMap<>();
		String metricIn = String.join(",", Metrics.ALL.stream().map(m -> "?").toList());
		try (PreparedStatement st = c.prepareStatement("SELECT uuid, key, MAX(value) FROM player_stat_daily WHERE day < ? AND key IN ("
				+ metricIn + ") GROUP BY uuid, key")) {
			st.setString(1, d);
			int i = 2;
			for (Metrics.Metric m : Metrics.ALL) {
				st.setString(i++, m.key());
			}
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					personalBest.computeIfAbsent(UUID.fromString(rs.getString(1)), x -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
				}
			}
		}

		Map<UUID, List<LocalDate>> activeDays = new HashMap<>();
		try (PreparedStatement st = c.prepareStatement(
				"SELECT uuid, day FROM player_stat_daily WHERE key = ? AND value >= 60 AND day <= ? ORDER BY day")) {
			st.setString(1, Keys.ONLINE);
			st.setString(2, d);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					activeDays.computeIfAbsent(UUID.fromString(rs.getString(1)), x -> new ArrayList<>()).add(LocalDate.parse(rs.getString(2)));
				}
			}
		}

		List<ChronicleEvent> events = events(c, "SELECT ts, day, uuid, type, subject, score, data FROM chronicle_event WHERE day = ? ORDER BY ts", d);
		List<ChronicleEvent> deaths = events(c, "SELECT ts, day, uuid, type, subject, score, data FROM chronicle_event WHERE type = 'death' AND day >= ? AND day <= ? ORDER BY ts", from28, d);

		List<DayData.Session> sessions = new ArrayList<>();
		try (PreparedStatement st = c.prepareStatement("SELECT uuid, start_ts, end_ts, afk_s FROM player_session WHERE end_ts >= ? AND start_ts < ?")) {
			st.setLong(1, start28);
			st.setLong(2, dayEnd);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					sessions.add(new DayData.Session(UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
				}
			}
		}

		Map<LocalDate, List<DayData.PairRow>> pairs = new TreeMap<>();
		try (PreparedStatement st = c.prepareStatement("SELECT day, a, b, key, value FROM pair_daily WHERE day >= ? AND day <= ?")) {
			st.setString(1, from28);
			st.setString(2, d);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					pairs.computeIfAbsent(LocalDate.parse(rs.getString(1)), x -> new ArrayList<>())
							.add(new DayData.PairRow(UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)), rs.getString(4), rs.getLong(5)));
				}
			}
		}

		Map<UUID, Map<String, Long>> totals = new HashMap<>();
		try (PreparedStatement st = c.prepareStatement("SELECT uuid, key, value FROM player_total");
				ResultSet rs = st.executeQuery()) {
			while (rs.next()) {
				totals.computeIfAbsent(UUID.fromString(rs.getString(1)), x -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
			}
		}

		List<DayData.Seen> seen = new ArrayList<>();
		try (PreparedStatement st = c.prepareStatement("SELECT uuid, kind, id, first_ts FROM player_seen WHERE first_ts >= ? AND first_ts < ? ORDER BY first_ts")) {
			st.setLong(1, dayStart);
			st.setLong(2, dayEnd);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					seen.add(new DayData.Seen(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getLong(4)));
				}
			}
		}

		List<String> headlines = new ArrayList<>();
		try (PreparedStatement st = c.prepareStatement("SELECT headline FROM newspaper WHERE day < ? ORDER BY day DESC LIMIT 5")) {
			st.setString(1, d);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					headlines.add(rs.getString(1));
				}
			}
		}

		List<DayData.Build> builds = new ArrayList<>();
		try (PreparedStatement st = c.prepareStatement("SELECT dim, cx, cz, owner, name, scan, history FROM build_site WHERE history IS NOT NULL");
				ResultSet rs = st.executeQuery()) {
			while (rs.next()) {
				String owner = rs.getString(4);
				JsonObject scan = json(rs.getString(6));
				JsonObject h = json(rs.getString(7));
				TreeMap<String, Long> hist = new TreeMap<>();
				h.entrySet().forEach(e -> hist.put(e.getKey(), e.getValue().getAsLong()));
				String dim = rs.getString(1);
				String biome = scan.has("biome") ? Names.pretty(scan.get("biome").getAsString()) : "?";
				builds.add(new DayData.Build(dim, rs.getInt(2), rs.getInt(3), owner == null ? null : UUID.fromString(owner), rs.getString(5),
						dim.equals("overworld") ? biome : Names.dimension(dim) + ", " + biome, hist,
						scan.has("structure") && scan.get("structure").getAsBoolean()));
			}
		}

		return new DayData(day, zone, people, counters, history, serverBest, personalBest, activeDays, events, deaths,
				sessions, pairs, totals, seen, headlines, builds);
	}

	private static List<ChronicleEvent> events(Connection c, String sql, String... args) throws SQLException {
		List<ChronicleEvent> out = new ArrayList<>();
		try (PreparedStatement st = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				st.setString(i + 1, args[i]);
			}
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					String u = rs.getString(3);
					out.add(new ChronicleEvent(rs.getLong(1), rs.getString(2), u == null ? null : UUID.fromString(u),
							rs.getString(4), rs.getString(5), rs.getInt(6), json(rs.getString(7))));
				}
			}
		}
		return out;
	}

	/** События за последние дни (для эпизодов памяти), от minScore. */
	public List<ChronicleEvent> recentEvents(long sinceTs, int minScore) throws SQLException {
		return db.call(c -> {
			List<ChronicleEvent> out = new ArrayList<>();
			try (PreparedStatement st = c.prepareStatement(
					"SELECT ts, day, uuid, type, subject, score, data FROM chronicle_event WHERE ts >= ? AND score >= ? ORDER BY ts")) {
				st.setLong(1, sinceTs);
				st.setInt(2, minScore);
				try (ResultSet rs = st.executeQuery()) {
					while (rs.next()) {
						String u = rs.getString(3);
						out.add(new ChronicleEvent(rs.getLong(1), rs.getString(2), u == null ? null : UUID.fromString(u),
								rs.getString(4), rs.getString(5), rs.getInt(6), json(rs.getString(7))));
					}
				}
			}
			return out;
		});
	}

	private void batch(String what, Database.Task task) {
		db.execute(what, c -> {
			boolean auto = c.getAutoCommit();
			c.setAutoCommit(false);
			try {
				task.run(c);
				c.commit();
			} catch (SQLException e) {
				c.rollback();
				throw e;
			} finally {
				c.setAutoCommit(auto);
			}
		});
	}

	static JsonObject json(String s) {
		if (s == null || s.isBlank()) {
			return new JsonObject();
		}
		try {
			return JsonParser.parseString(s).getAsJsonObject();
		} catch (RuntimeException e) {
			return new JsonObject();
		}
	}
}
