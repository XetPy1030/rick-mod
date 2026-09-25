package ru.xetpy.rikoshet.flavor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackLinesTest {
	private static final Set<String> VARS = Set.of("player", "nick", "role", "killer", "count", "days", "deaths");
	private final FallbackLines rick = FallbackLines.load("rick", Path.of("does-not-exist"), new Random(1));

	private static Map<String, String> longest() {
		Map<String, String> v = new HashMap<>();
		v.put("player", "Злой коп Рик / Токсик Рик");
		v.put("nick", "Very_Long_Nick16");
		v.put("role", "Злой коп Рик / Токсик Рик");
		v.put("killer", "зомбифицированный пиглин по имени «Геннадий»");
		v.put("count", "12");
		v.put("days", "30");
		v.put("deaths", "15");
		return v;
	}

	@Test
	void allLinesFitAndUseKnownVars() {
		Pattern var = Pattern.compile("\\{([a-z_]+)}");
		int n = 0;
		for (var section : rick.sections().entrySet()) {
			for (var key : section.getValue().entrySet()) {
				assertFalse(key.getValue().isEmpty(), section.getKey() + "." + key.getKey());
				for (String line : key.getValue()) {
					Matcher m = var.matcher(line);
					while (m.find()) {
						assertTrue(VARS.contains(m.group(1)), line);
					}
					String filled = FallbackLines.fill(line, longest());
					assertTrue(filled.length() <= 150, filled.length() + ": " + filled);
					assertFalse(filled.contains("{"), filled);
					n++;
				}
			}
		}
		assertTrue(n >= 60, "реплик " + n);
	}

	@Test
	void everyDeathGroupHasLines() {
		Set<String> groups = new HashSet<>();
		for (String t : List.of("fall", "lava", "in_fire", "drown", "explosion", "mob_attack", "player_attack", "arrow",
				"fell_out_of_world", "starve", "in_wall", "magic", "cactus")) {
			groups.add(DeathCauses.of("minecraft:" + t).group());
		}
		groups.remove("other");
		for (String g : groups) {
			assertTrue(rick.sections().get("death").containsKey(g), "нет заготовок для " + g);
		}
		assertEquals("other", DeathCauses.of("modded:laser").group());
	}

	@Test
	void pickSkipsLinesWithMissingVarsAndAvoidsRepeats() {
		Map<String, String> vars = Map.of("player", "Морти", "nick", "m");
		List<FallbackLines.Choice> mob = List.of(new FallbackLines.Choice("death", "mob", 1), new FallbackLines.Choice("death", "any", 1));
		String line = rick.pick(mob, vars);
		assertNotNull(line);
		assertFalse(line.contains("{"), line);
		Set<String> seen = new HashSet<>();
		List<FallbackLines.Choice> any = List.of(new FallbackLines.Choice("death", "any", 1));
		String prev = null;
		for (int i = 0; i < 30; i++) {
			String l = rick.pick(any, vars);
			assertFalse(l.equals(prev), "повтор подряд: " + l);
			seen.add(l);
			prev = l;
		}
		assertTrue(seen.size() >= 8);
	}

	@Test
	void sessionSeries() {
		SessionTracker s = new SessionTracker();
		java.util.UUID u = java.util.UUID.randomUUID();
		s.start(u, 0);
		assertEquals(1, s.death(u, 1_000, 600_000));
		assertEquals(2, s.death(u, 2_000, 600_000));
		assertEquals(1, s.death(u, 700_000, 600_000));
		assertTrue(s.tryComment(u, 700_000, 20_000));
		assertFalse(s.tryComment(u, 710_000, 20_000));
		assertTrue(s.tryComment(u, 721_000, 20_000));
		assertEquals(3, s.get(u).deaths());
	}
}
