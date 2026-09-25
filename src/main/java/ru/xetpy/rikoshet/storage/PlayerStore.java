package ru.xetpy.rikoshet.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Профили игроков: в памяти целиком (игроков — десятки), запись в БД в фоне.
 * Читается из главного потока без обращения к БД.
 */
public final class PlayerStore {
	private final Database db;
	private final Map<UUID, PlayerRecord> players = new ConcurrentHashMap<>();

	public PlayerStore(Database db) {
		this.db = db;
	}

	public void load() throws SQLException {
		players.clear();
		db.call(c -> {
			try (PreparedStatement st = c.prepareStatement(
					"SELECT uuid, name, first_seen, last_seen, playtime_s, ai_opt_out FROM player");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					PlayerRecord p = new PlayerRecord(UUID.fromString(rs.getString(1)), rs.getString(2),
							rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getInt(6) != 0);
					players.put(p.uuid(), p);
				}
			}
			return null;
		});
	}

	public PlayerRecord get(UUID uuid) {
		return players.get(uuid);
	}

	/** Игрок по нику без учёта регистра; при совпадении — тот, кто заходил последним. */
	public Optional<PlayerRecord> byName(String name) {
		return players.values().stream()
				.filter(p -> p.name().equalsIgnoreCase(name))
				.max(Comparator.comparingLong(PlayerRecord::lastSeen));
	}

	public List<String> names() {
		List<String> out = new ArrayList<>();
		players.values().forEach(p -> out.add(p.name()));
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	public Map<UUID, String> nameMap() {
		Map<UUID, String> out = new java.util.HashMap<>();
		players.values().forEach(p -> out.put(p.uuid(), p.name()));
		return out;
	}

	public boolean optedOut(UUID uuid) {
		PlayerRecord p = players.get(uuid);
		return p != null && p.aiOptOut();
	}

	/**
	 * Игрок вошёл (после пароля). Возвращает прошлый профиль или null, если игрок новый.
	 */
	public PlayerRecord join(UUID uuid, String name, long now) {
		PlayerRecord old = players.get(uuid);
		PlayerRecord p = old == null
				? new PlayerRecord(uuid, name, now, now, 0, false)
				: new PlayerRecord(uuid, name, old.firstSeen(), now, old.playtimeSeconds(), old.aiOptOut());
		players.put(uuid, p);
		db.execute("player join", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO player (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?)
					ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen""")) {
				st.setString(1, uuid.toString());
				st.setString(2, name);
				st.setLong(3, p.firstSeen());
				st.setLong(4, now);
				st.executeUpdate();
			}
		});
		return old;
	}

	/** Игрок вышел: прибавить время сессии. */
	public void leave(UUID uuid, long now, long sessionSeconds) {
		PlayerRecord old = players.get(uuid);
		if (old == null) {
			return;
		}
		players.put(uuid, new PlayerRecord(uuid, old.name(), old.firstSeen(), now, old.playtimeSeconds() + sessionSeconds, old.aiOptOut()));
		db.execute("player leave", c -> {
			try (PreparedStatement st = c.prepareStatement(
					"UPDATE player SET last_seen = ?, playtime_s = playtime_s + ? WHERE uuid = ?")) {
				st.setLong(1, now);
				st.setLong(2, sessionSeconds);
				st.setString(3, uuid.toString());
				st.executeUpdate();
			}
		});
	}

	public void setOptOut(UUID uuid, boolean optOut) {
		PlayerRecord old = players.get(uuid);
		if (old == null) {
			return;
		}
		players.put(uuid, new PlayerRecord(uuid, old.name(), old.firstSeen(), old.lastSeen(), old.playtimeSeconds(), optOut));
		db.execute("player opt-out", c -> {
			try (PreparedStatement st = c.prepareStatement("UPDATE player SET ai_opt_out = ? WHERE uuid = ?")) {
				st.setInt(1, optOut ? 1 : 0);
				st.setString(2, uuid.toString());
				st.executeUpdate();
			}
		});
	}
}
