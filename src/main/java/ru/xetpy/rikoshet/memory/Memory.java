package ru.xetpy.rikoshet.memory;

import java.util.Set;
import java.util.UUID;

/**
 * Одно воспоминание-кандидат: эпизод или знание. text — как увидит модель; about — о ком;
 * tags — для совпадения с ситуацией; key — чтобы не брать два одинаковых по сути.
 */
public record Memory(String text, Set<UUID> about, Set<String> tags, int importance, long ts, double halfLifeDays, String key) {
	/** Период полураспада эпизода по важности (docs/architecture/memory.md#эпизоды). */
	public static double halfLife(int importance) {
		if (importance >= 50) {
			return 90;
		}
		if (importance >= 20) {
			return 21;
		}
		if (importance >= 10) {
			return 7;
		}
		return 2;
	}
}
