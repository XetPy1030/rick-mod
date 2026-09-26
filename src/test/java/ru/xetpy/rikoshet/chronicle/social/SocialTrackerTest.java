package ru.xetpy.rikoshet.chronicle.social;

import org.junit.jupiter.api.Test;
import ru.xetpy.rikoshet.chronicle.Activity;
import ru.xetpy.rikoshet.chronicle.Keys;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialTrackerTest {
	private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private final UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");

	private static SocialTracker.Pos pos(UUID u, double x, double z) {
		return new SocialTracker.Pos(u, "overworld", x, 64, z);
	}

	@Test
	void togetherCloseAndOverlap() {
		SocialTracker s = new SocialTracker();
		s.sample(List.of(pos(a, 0, 0), pos(b, 5, 0), pos(c, 500, 0)), 15, 0);
		Map<String, Long> p = s.drain();
		String ab = PairKeys.pair(a, b);
		assertEquals(15L, p.get(ab + "|" + PairKeys.TOGETHER));
		assertEquals(15L, p.get(ab + "|" + PairKeys.CLOSE));
		assertEquals(15L, p.get(ab + "|" + PairKeys.OVERLAP));
		assertEquals(15L, p.get(PairKeys.pair(a, c) + "|" + PairKeys.OVERLAP));
		assertNull(p.get(PairKeys.pair(a, c) + "|" + PairKeys.TOGETHER));
		assertTrue(s.drain().isEmpty(), "drain очищает");
	}

	@Test
	void otherDimensionIsNotTogether() {
		SocialTracker s = new SocialTracker();
		s.sample(List.of(pos(a, 0, 0), new SocialTracker.Pos(b, "the_nether", 0, 64, 0)), 15, 0);
		Map<String, Long> p = s.drain();
		assertNull(p.get(PairKeys.pair(a, b) + "|" + PairKeys.TOGETHER));
		assertEquals(15L, p.get(PairKeys.pair(a, b) + "|" + PairKeys.OVERLAP));
	}

	@Test
	void visitWithoutOwnerBecomesEventOncePerDay() {
		SocialTracker s = new SocialTracker();
		s.setHomes(Map.of(b, new SocialTracker.Home("overworld", 10, 10)));
		List<SocialTracker.Event> ev = List.of();
		for (int i = 0; i < 4; i++) {
			ev = s.sample(List.of(pos(a, 10 * 64 + 5, 10 * 64 + 5)), 15, i * 15_000L);
		}
		assertEquals(1, ev.size());
		assertEquals("visit", ev.getFirst().type());
		assertEquals(25, ev.getFirst().score(), "хозяина нет на сервере — значимее");
		assertTrue(s.sample(List.of(pos(a, 645, 645)), 15, 70_000).isEmpty(), "второй раз за день — без события");
		assertEquals(75L, s.drain().get(PairKeys.directed(a, b) + "|" + PairKeys.VISIT));
		s.newDay();
		for (int i = 0; i < 4; i++) {
			ev = s.sample(List.of(pos(a, 645, 645)), 15, 100_000 + i * 15_000L);
		}
		assertEquals(1, ev.size(), "на следующий день снова");
	}

	@Test
	void visitWithOwnerHomeIsHosting() {
		SocialTracker s = new SocialTracker();
		s.setHomes(Map.of(b, new SocialTracker.Home("overworld", 0, 0)));
		for (int i = 0; i < 5; i++) {
			assertTrue(s.sample(List.of(pos(a, 10, 10), pos(b, 20, 10)), 15, i * 15_000L).isEmpty());
		}
		Map<String, Long> p = s.drain();
		assertEquals(75L, p.get(PairKeys.directed(a, b) + "|" + PairKeys.HOST));
		assertNull(p.get(PairKeys.directed(a, b) + "|" + PairKeys.VISIT));
	}

	@Test
	void giftInference() {
		assertEquals(0, SocialTracker.gift(Map.of("dropped:cobblestone", 64L), Map.of("picked_up:cobblestone", 900L)).value(),
				"подобрал куда больше — собирал своё");
		SocialTracker.Gift g = SocialTracker.gift(Map.of("dropped:diamond", 10L, "dropped:dirt", 3L),
				Map.of("picked_up:diamond", 10L, "picked_up:dirt", 3L));
		assertEquals("diamond", g.item());
		assertEquals(10, g.count());
		assertEquals(102, g.value(), "10 алмазов по 10 + грязь почти даром");
	}

	@Test
	void cycleFindsJointActivityGiftAndBuildingAtHome() {
		SocialTracker s = new SocialTracker();
		s.setHomes(Map.of(b, new SocialTracker.Home("overworld", 0, 0)));
		for (int i = 0; i < 20; i++) {
			s.sample(List.of(pos(a, 10, 10), pos(b, 12, 10)), 15, i * 15_000L);
		}
		s.drain();
		var events = s.cycle(
				Map.of(a, Map.of("dropped:netherite_ingot", 2L, Keys.PLACED, 300L),
						b, Map.of("picked_up:netherite_ingot", 2L, Keys.PLACED, 250L)),
				Map.of(a, Activity.BUILDING, b, Activity.BUILDING),
				Map.of(a, 300L, b, 300L));
		Map<String, Long> p = s.drain();
		assertEquals(300L, p.get(PairKeys.pair(a, b) + "|" + PairKeys.JOINT + "building"));
		assertEquals(100L, p.get(PairKeys.directed(a, b) + "|" + PairKeys.GIFT));
		assertEquals(300L, p.get(PairKeys.directed(a, b) + "|" + PairKeys.BUILD_AT), "строил у дома b при хозяине");
		assertTrue(events.stream().anyMatch(e -> e.type().equals("gift") && e.actor().equals(a) && e.target().equals(b)));
	}

	@Test
	void digAtHomeWhileOwnerAway() {
		SocialTracker s = new SocialTracker();
		s.setHomes(Map.of(b, new SocialTracker.Home("overworld", 0, 0)));
		for (int i = 0; i < 20; i++) {
			s.sample(List.of(pos(a, 10, 10)), 15, i * 15_000L);
		}
		var events = s.cycle(Map.of(a, Map.of(Keys.MINED, 120L)), Map.of(a, Activity.MINING), Map.of(a, 300L));
		assertTrue(events.stream().anyMatch(e -> e.type().equals("dig_at_home")));
		assertEquals(120L, s.drain().get(PairKeys.directed(a, b) + "|" + PairKeys.MINE_ABSENT));
	}

	@Test
	void rescueAndRevenge() {
		SocialTracker s = new SocialTracker();
		UUID zombie = UUID.randomUUID();
		UUID creeper = UUID.randomUUID();
		var ev = s.mobKilled(zombie, a, b, true, 0.2f, 0);
		assertEquals("rescue", ev.getFirst().type());
		s.playerDeath(c, null, creeper, List.of(), null, Set.of(), 1000);
		ev = s.mobKilled(creeper, a, null, false, 1, 60_000);
		assertEquals("revenge", ev.getFirst().type());
		assertEquals(c, ev.getFirst().target());
		assertTrue(s.mobKilled(creeper, a, null, false, 1, 61_000).isEmpty(), "месть одна");
		Map<String, Long> p = s.drain();
		assertEquals(1L, p.get(PairKeys.directed(a, b) + "|" + PairKeys.RESCUE));
		assertEquals(1L, p.get(PairKeys.directed(a, c) + "|" + PairKeys.REVENGE));
	}

	@Test
	void lootFromDeathSite() {
		SocialTracker s = new SocialTracker();
		s.playerDeath(b, null, null, List.of(a), pos(b, 100, 100), Set.of("diamond_pickaxe", "diamond"), 0);
		s.sample(List.of(pos(a, 105, 100)), 15, 10_000);
		var ev = s.cycle(Map.of(a, Map.of("picked_up:diamond", 5L)), Map.of(a, Activity.OTHER), Map.of(a, 300L));
		assertEquals("loot", ev.getFirst().type());
		Map<String, Long> p = s.drain();
		assertEquals(1L, p.get(PairKeys.directed(a, b) + "|" + PairKeys.WITNESS));
		assertEquals(50L, p.get(PairKeys.directed(a, b) + "|" + PairKeys.LOOT));
	}

	@Test
	void chatMentionsAndDialogue() {
		SocialTracker s = new SocialTracker();
		Map<UUID, List<String>> names = Map.of(a, List.of("kate"), b, List.of("benjurist", "злой морти"));
		s.chat(a, "злой морти, иди сюда", names, 0);
		s.chat(b, "иду", names, 10_000);
		s.chat(a, "ок", names, 100_000);
		Map<String, Long> p = s.drain();
		assertEquals(1L, p.get(PairKeys.directed(a, b) + "|" + PairKeys.MENTION));
		assertEquals(1L, p.get(PairKeys.pair(a, b) + "|" + PairKeys.DIALOGUE), "ответ через 90 с — уже не диалог");
	}

	@Test
	void pvpDamageIgnoresSelf() {
		SocialTracker s = new SocialTracker();
		s.pvpDamage(a, a, 5);
		s.pvpDamage(a, b, 3.5f);
		Map<String, Long> p = s.drain();
		assertEquals(1, p.size());
		assertEquals(35L, p.get(PairKeys.directed(a, b) + "|" + PairKeys.PVP_DAMAGE));
		assertFalse(PairKeys.symmetric(PairKeys.PVP_DAMAGE));
		assertTrue(PairKeys.symmetric(PairKeys.JOINT + "mining"));
	}
}
