package ru.xetpy.rikoshet.storage;

import ru.xetpy.rikoshet.persona.Archetype;
import ru.xetpy.rikoshet.persona.Role;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Роли игроков: в памяти, запись в БД в фоне. Меняет только админ. */
public final class RoleStore {
	private final Database db;
	private final Map<UUID, Role> roles = new ConcurrentHashMap<>();

	public RoleStore(Database db) {
		this.db = db;
	}

	public void load() throws SQLException {
		roles.clear();
		db.call(c -> {
			try (PreparedStatement st = c.prepareStatement(
					"SELECT uuid, name, title, archetype, archetype_manual, note, set_at FROM player_role");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					Role r = new Role(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
							Archetype.byId(rs.getString(4)).orElse(Archetype.OTHER), rs.getInt(5) != 0, rs.getString(6), rs.getLong(7));
					roles.put(r.uuid(), r);
				}
			}
			return null;
		});
	}

	public Role get(UUID uuid) {
		return roles.get(uuid);
	}

	public List<Role> all() {
		List<Role> out = new ArrayList<>(roles.values());
		out.sort(Comparator.comparing(Role::name, String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	/** Назначить или переименовать роль. Ручной архетип сохраняется. */
	public Role set(UUID uuid, String name, String title, long now) {
		Role old = roles.get(uuid);
		Role r = old == null
				? new Role(uuid, name, title, Archetype.detect(title), false, null, now)
				: old.withName(name).withTitle(title, now);
		put(r);
		return r;
	}

	/** Заметку и архетип можно задать только тому, у кого есть роль. */
	public Role note(UUID uuid, String note, long now) {
		Role old = roles.get(uuid);
		if (old == null) {
			return null;
		}
		Role r = old.withNote(note == null || note.isBlank() ? null : note.strip(), now);
		put(r);
		return r;
	}

	public Role archetype(UUID uuid, Archetype archetype, long now) {
		Role old = roles.get(uuid);
		if (old == null) {
			return null;
		}
		Role r = old.withArchetype(archetype, now);
		put(r);
		return r;
	}

	/** Игрок сменил ник: роль остаётся за UUID, ник в ней обновляем. */
	public void renameIfNeeded(UUID uuid, String name) {
		Role old = roles.get(uuid);
		if (old != null && !old.name().equals(name)) {
			put(old.withName(name));
		}
	}

	public Role clear(UUID uuid) {
		Role old = roles.remove(uuid);
		if (old != null) {
			db.execute("role clear", c -> {
				try (PreparedStatement st = c.prepareStatement("DELETE FROM player_role WHERE uuid = ?")) {
					st.setString(1, uuid.toString());
					st.executeUpdate();
				}
			});
		}
		return old;
	}

	private void put(Role r) {
		roles.put(r.uuid(), r);
		db.execute("role set", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO player_role (uuid, name, title, archetype, archetype_manual, note, set_at)
					VALUES (?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, title = excluded.title,
						archetype = excluded.archetype, archetype_manual = excluded.archetype_manual,
						note = excluded.note, set_at = excluded.set_at""")) {
				st.setString(1, r.uuid().toString());
				st.setString(2, r.name());
				st.setString(3, r.title());
				st.setString(4, r.archetype().id());
				st.setInt(5, r.archetypeManual() ? 1 : 0);
				st.setString(6, r.note());
				st.setLong(7, r.setAt());
				st.executeUpdate();
			}
		});
	}
}
