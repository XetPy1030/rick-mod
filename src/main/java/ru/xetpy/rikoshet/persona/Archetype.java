package ru.xetpy.rikoshet.persona;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Семейство роли: для механик, ключей заготовок и отношения персонажей по умолчанию. */
public enum Archetype {
	RICK("rick", Set.of("рик", "рика", "рику", "риком", "рике", "рики", "риков", "рикам", "риками", "риках", "rick", "ricks")),
	MORTY("morty", Set.of("морти", "morty", "mortys", "morties")),
	JERRY("jerry", Set.of("джерри", "jerry")),
	SUMMER("summer", Set.of("саммер", "summer")),
	BETH("beth", Set.of("бет", "бэт", "бетти", "beth")),
	OTHER("other", Set.of());

	private final String id;
	private final Set<String> words;

	Archetype(String id, Set<String> words) {
		this.id = id;
		this.words = words;
	}

	public String id() {
		return id;
	}

	public static Optional<Archetype> byId(String id) {
		for (Archetype a : values()) {
			if (a.id.equals(id)) {
				return Optional.of(a);
			}
		}
		return Optional.empty();
	}

	/**
	 * Архетип по названию роли: первое слово, которое узнаётся. «Дуфус Джерри» — jerry,
	 * «Злой коп Рик / Токсик Рик» — rick, «Джессика» — other.
	 */
	public static Archetype detect(String title) {
		String norm = title.toLowerCase(Locale.ROOT).replace('ё', 'е');
		for (String token : norm.split("[^\\p{L}]+")) {
			for (Archetype a : values()) {
				if (a.words.contains(token)) {
					return a;
				}
			}
		}
		return OTHER;
	}

	public static final Map<String, String> RU = Map.of(
			"rick", "Рик", "morty", "Морти", "jerry", "Джерри", "summer", "Саммер", "beth", "Бет", "other", "другое");
}
