package ru.xetpy.rikoshet.persona;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchetypeTest {
	@Test
	void serverRoles() {
		// Роли с сервера на 2026-09-26 (docs/design/player-roles.md)
		Map<String, Archetype> expected = Map.ofEntries(
				Map.entry("Джессика", Archetype.OTHER),
				Map.entry("Джерри", Archetype.JERRY),
				Map.entry("Жопосранчик", Archetype.OTHER),
				Map.entry("Дуфус Джерри", Archetype.JERRY),
				Map.entry("Немой Морти", Archetype.MORTY),
				Map.entry("Морти", Archetype.MORTY),
				Map.entry("Злой коп Рик / Токсик Рик", Archetype.RICK),
				Map.entry("Фермер Рик", Archetype.RICK),
				Map.entry("Злой Морти", Archetype.MORTY),
				Map.entry("Даен", Archetype.OTHER),
				Map.entry("Дуфус Рик", Archetype.RICK),
				Map.entry("Токсик Морти", Archetype.MORTY));
		expected.forEach((title, a) -> assertEquals(a, Archetype.detect(title), title));
	}

	@Test
	void wordFormsAndFalseFriends() {
		assertEquals(Archetype.RICK, Archetype.detect("Друг Рика"));
		assertEquals(Archetype.RICK, Archetype.detect("Toxic Rick"));
		assertEquals(Archetype.BETH, Archetype.detect("Космо-Бет"));
		assertEquals(Archetype.SUMMER, Archetype.detect("саммер"));
		assertEquals(Archetype.OTHER, Archetype.detect("Рикошет"));
		assertEquals(Archetype.OTHER, Archetype.detect("Бетон"));
		assertEquals(Archetype.OTHER, Archetype.detect("Мортимер"));
		assertEquals(Archetype.MORTY, Archetype.detect("Морти-Рик"), "первое узнанное слово");
	}

	@Test
	void rosterIsStableAndHidesOptOut() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		List<Role> roles = List.of(
				new Role(b, "zed", "Джерри", Archetype.JERRY, false, null, 0),
				new Role(a, "Alice", "Фермер Рик", Archetype.RICK, false, "носит сапоги", 0),
				new Role(c, "hidden", "Морти", Archetype.MORTY, false, null, 0));
		String block = Roster.block("## Кто есть кто\n\nшапка", roles, Map.of(b, "Zed_New"), u -> !u.equals(c));
		assertEquals("""
				## Кто есть кто

				шапка

				- Alice — Фермер Рик (rick): носит сапоги
				- Zed_New — Джерри (jerry)""", block);
	}
}
