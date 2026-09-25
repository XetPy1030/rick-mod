package ru.xetpy.rikoshet.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Заметки персонажа об игроке — действие remember. Лежат в dialogue_log с ролью note и живут
 * столько же, сколько диалоги (dialogue_retention_days); сжатая память — этап 3.
 * Последние заметки держим в памяти для блока {@code <memory>}.
 */
public final class NoteStore {
	public static final int KEEP = 5;

	private final Database db;
	private final Map<String, Deque<String>> notes = new ConcurrentHashMap<>();

	public NoteStore(Database db) {
		this.db = db;
	}

	public void load() throws SQLException {
		notes.clear();
		db.call(c -> {
			try (PreparedStatement st = c.prepareStatement(
					"SELECT uuid, persona_id, text FROM dialogue_log WHERE role = 'note' ORDER BY ts");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					push(key(UUID.fromString(rs.getString(1)), rs.getString(2)), rs.getString(3));
				}
			}
			return null;
		});
	}

	public List<String> recent(UUID player, String persona) {
		Deque<String> d = notes.get(key(player, persona));
		if (d == null) {
			return List.of();
		}
		synchronized (d) {
			return new ArrayList<>(d);
		}
	}

	public void add(UUID player, String persona, String note, long now) {
		push(key(player, persona), note);
		db.execute("remember", c -> {
			try (PreparedStatement st = c.prepareStatement(
					"INSERT INTO dialogue_log (uuid, persona_id, role, text, ts) VALUES (?, ?, 'note', ?, ?)")) {
				st.setString(1, player.toString());
				st.setString(2, persona);
				st.setString(3, note);
				st.setLong(4, now);
				st.executeUpdate();
			}
		});
	}

	private void push(String key, String note) {
		Deque<String> d = notes.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (d) {
			d.remove(note);
			d.addLast(note);
			while (d.size() > KEEP) {
				d.removeFirst();
			}
		}
	}

	private static String key(UUID player, String persona) {
		return player + "|" + persona;
	}
}
