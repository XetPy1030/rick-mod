package ru.xetpy.rikoshet.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Лимиты ответов в чате (docs/design/chat.md#лимиты): пауза между ответами игроку, ответов
 * игроку в час, ответов серверу в минуту, игнор после спама. Время — снаружи. Главный поток.
 */
final class ChatLimits {
	static final long HOUR = 3_600_000;
	static final long MINUTE = 60_000;

	record Settings(long cooldownMs, int perHour, int serverPerMinute, long ignoreMs) {
	}

	private final Map<UUID, Deque<Long>> replies = new HashMap<>();
	private final Deque<Long> server = new ArrayDeque<>();
	private final Map<UUID, Long> ignoredUntil = new HashMap<>();

	boolean ignored(UUID u, long now) {
		Long until = ignoredUntil.get(u);
		if (until != null && until <= now) {
			ignoredUntil.remove(u);
			return false;
		}
		return until != null;
	}

	/** Когда игроку можно ответить: не раньше паузы после прошлого ответа. */
	long nextAllowed(UUID u, long now, Settings s) {
		Deque<Long> d = replies.get(u);
		return d == null || d.isEmpty() ? now : Math.max(now, d.peekLast() + s.cooldownMs());
	}

	boolean hourFull(UUID u, long now, Settings s) {
		Deque<Long> d = replies.get(u);
		if (d == null) {
			return false;
		}
		trim(d, now - HOUR);
		return d.size() >= s.perHour();
	}

	boolean serverFull(long now, Settings s) {
		trim(server, now - MINUTE);
		return server.size() >= s.serverPerMinute();
	}

	void replied(UUID u, long now) {
		replies.computeIfAbsent(u, k -> new ArrayDeque<>()).addLast(now);
		server.addLast(now);
	}

	void ignore(UUID u, long now, Settings s) {
		ignoredUntil.put(u, now + s.ignoreMs());
	}

	/** Кто сейчас в игноре и до какого момента. */
	Map<UUID, Long> ignoredNow(long now) {
		ignoredUntil.values().removeIf(t -> t <= now);
		return new LinkedHashMap<>(ignoredUntil);
	}

	void forget(UUID u) {
		replies.remove(u);
		ignoredUntil.remove(u);
	}

	private static void trim(Deque<Long> d, long since) {
		while (!d.isEmpty() && d.peekFirst() < since) {
			d.removeFirst();
		}
	}
}
