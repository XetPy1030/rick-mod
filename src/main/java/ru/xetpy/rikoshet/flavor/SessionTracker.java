package ru.xetpy.rikoshet.flavor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Сессии игроков с момента входа по паролю: длительность, смерти, серии. Главный поток. */
public final class SessionTracker {
	public static final class Session {
		final long start;
		int deaths;
		final Deque<Long> deathTimes = new ArrayDeque<>();
		long lastComment = Long.MIN_VALUE;

		Session(long start) {
			this.start = start;
		}

		public long start() {
			return start;
		}

		public int deaths() {
			return deaths;
		}
	}

	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

	public void start(UUID player, long now) {
		sessions.put(player, new Session(now));
	}

	public Session end(UUID player) {
		return sessions.remove(player);
	}

	public Session get(UUID player) {
		return sessions.get(player);
	}

	/** Засчитать смерть; вернуть длину серии — сколько смертей за окно, включая эту. */
	public int death(UUID player, long now, long windowMillis) {
		Session s = sessions.computeIfAbsent(player, p -> new Session(now));
		s.deaths++;
		s.deathTimes.addLast(now);
		while (!s.deathTimes.isEmpty() && now - s.deathTimes.peekFirst() > windowMillis) {
			s.deathTimes.removeFirst();
		}
		return s.deathTimes.size();
	}

	/** Можно ли комментировать смерть: кулдаун на игрока. Если да — отмечает комментарий. */
	public boolean tryComment(UUID player, long now, long cooldownMillis) {
		Session s = sessions.get(player);
		if (s == null) {
			return true;
		}
		if (s.lastComment != Long.MIN_VALUE && now - s.lastComment < cooldownMillis) {
			return false;
		}
		s.lastComment = now;
		return true;
	}
}
