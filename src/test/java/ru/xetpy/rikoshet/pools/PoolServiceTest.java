package ru.xetpy.rikoshet.pools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolServiceTest {
	static PoolService.Spec spec(String pool, String key) {
		return PoolService.SPECS.stream().filter(s -> s.pool().equals(pool) && s.key().equals(key)).findFirst().orElseThrow();
	}

	@Test
	void flavorLinesKeepAllowedPlaceholdersOnly() {
		PoolService.Spec lava = spec("death", "lava");
		assertEquals("Лава тёплая, {player}? *рыг*", PoolService.accept("Лава тёплая, {player}? *рыг*", lava, List.of()));
		assertNull(PoolService.accept("Опять ты, {killer}", lava, List.of()), "{killer} в пуле лавы не заполнится");
		assertNull(PoolService.accept("Смотри на http://evil.example", lava, List.of()), "ссылки — нет");
		PoolService.Spec back = spec("join", "back");
		assertEquals("{days} дней? Я думал, ты сдох.", PoolService.accept("{days} дней? Я думал, ты сдох.", back, List.of()));
	}

	@Test
	void newsMustFitWithoutClipping() {
		PoolService.Spec motd = spec("motd", "news");
		assertEquals("Плюмбусы подорожали", PoolService.accept("Плюмбусы подорожали", motd, List.of()));
		assertNull(PoolService.accept("Очень длинная новость про то, как огурцы захватили Цитадель Риков и Морти", motd, List.of()));
		assertNull(PoolService.accept("Новость про {player}", motd, List.of()), "в новостях плейсхолдеров нет");
	}

	@Test
	void specsCoverFallbackSections() {
		assertTrue(PoolService.SPECS.stream().anyMatch(s -> s.pool().equals("death_archetype") && s.key().equals("jerry")));
		assertEquals(26, PoolService.SPECS.size());
	}
}
