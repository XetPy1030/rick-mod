package ru.xetpy.rikoshet.quest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelTest {
	@Test
	void boundariesFromRickDoc() {
		assertEquals(Level.BIOMASS, Level.of(-100));
		assertEquals(Level.BIOMASS, Level.of(-51));
		assertEquals(Level.SUBJECT, Level.of(-50));
		assertEquals(Level.SUBJECT, Level.of(-1));
		assertEquals(Level.LAB, Level.of(0));
		assertEquals(Level.LAB, Level.of(29));
		assertEquals(Level.ASSISTANT, Level.of(30));
		assertEquals(Level.ALMOST_MORTY, Level.of(60));
		assertEquals(Level.ALMOST_MORTY, Level.of(89));
		assertEquals(Level.GENIUS, Level.of(90));
		assertEquals(Level.GENIUS, Level.of(100));
	}

	@Test
	void orderForRewards() {
		assertTrue(Level.ASSISTANT.atLeast(Level.LAB));
		assertTrue(Level.LAB.atLeast(Level.LAB));
		assertFalse(Level.SUBJECT.atLeast(Level.LAB));
		assertEquals(Level.ALMOST_MORTY, Level.byId("almost_morty"));
		assertEquals(null, Level.byId("admin"));
	}

	@Test
	void clampedToScale() {
		assertEquals(100, Reputation.clamp(250));
		assertEquals(-100, Reputation.clamp(-101));
		assertEquals(7, Reputation.clamp(7));
	}
}
