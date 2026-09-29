package ru.xetpy.rikoshet.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLimitsTest {
	private static final ChatLimits.Settings S = new ChatLimits.Settings(15_000, 3, 4, 600_000);
	private final UUID a = UUID.randomUUID();
	private final UUID b = UUID.randomUUID();

	@Test
	void cooldownBetweenReplies() {
		ChatLimits l = new ChatLimits();
		assertEquals(1000, l.nextAllowed(a, 1000, S));
		l.replied(a, 1000);
		assertEquals(16_000, l.nextAllowed(a, 2000, S));
		assertEquals(20_000, l.nextAllowed(a, 20_000, S));
		assertEquals(2000, l.nextAllowed(b, 2000, S), "другого игрока пауза не касается");
	}

	@Test
	void hourAndServerLimits() {
		ChatLimits l = new ChatLimits();
		for (int i = 0; i < 3; i++) {
			l.replied(a, i * 20_000L);
		}
		assertTrue(l.hourFull(a, 60_000, S));
		assertFalse(l.hourFull(a, ChatLimits.HOUR + 1, S), "через час старые ответы не считаются");
		assertFalse(l.serverFull(61_000, S));
		l.replied(b, 61_000);
		l.replied(b, 62_000);
		l.replied(b, 63_000);
		l.replied(b, 64_000);
		assertTrue(l.serverFull(65_000, S));
		assertFalse(l.serverFull(64_000 + ChatLimits.MINUTE, S));
	}

	@Test
	void ignoreExpires() {
		ChatLimits l = new ChatLimits();
		l.ignore(a, 0, S);
		assertTrue(l.ignored(a, 599_999));
		assertEquals(1, l.ignoredNow(1000).size());
		assertFalse(l.ignored(a, 600_000));
		assertEquals(0, l.ignoredNow(600_000).size());
	}
}
