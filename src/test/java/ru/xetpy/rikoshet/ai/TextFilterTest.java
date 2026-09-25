package ru.xetpy.rikoshet.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFilterTest {
	private static String ok(String s) {
		TextFilter.Result r = TextFilter.apply(s, 150, List.of("жопа"));
		assertTrue(r.ok(), () -> "отклонено: " + r.reason());
		return r.text();
	}

	private static String reject(String s) {
		TextFilter.Result r = TextFilter.apply(s, 150, List.of("жопа"));
		assertFalse(r.ok(), () -> "пропущено: " + r.text());
		return r.reason();
	}

	@Test
	void cleans() {
		assertEquals("Красный текст", ok("§cКрасный §lтекст"));
		assertEquals("Жирный и код", ok("**Жирный** и `код`"));
		assertEquals("Заголовок", ok("# Заголовок"));
		assertEquals("Привет, Морти", ok("Привет, Морти 🎉🔥"));
		assertEquals("Сердце \u2764", ok("Сердце \u2764\uFE0F"));
		assertEquals("две строки", ok("две\n\nстроки"));
		assertEquals("Цитата", ok("«Цитата»"));
		assertEquals("Он сказал «нет» и ушёл", ok("Он сказал «нет» и ушёл"));
		assertEquals("*рыг* Морти", ok("\"*рыг* Морти\""));
	}

	@Test
	void rejects() {
		assertEquals("ссылка", reject("Заходи на https://example.com"));
		assertEquals("ссылка", reject("пиши в discord.gg/abc"));
		assertEquals("ссылка", reject("сайт рик.рф"));
		assertEquals("иероглифы", reject("Рик 你好"));
		assertEquals("стоп-слово", reject("Ну ты и ЖОПА"));
		assertEquals("пусто", reject("  🎉 "));
	}

	@Test
	void keepsPlainDots() {
		assertEquals("Т.е. ты опять умер. Итог: 3 раза.", ok("Т.е. ты опять умер. Итог: 3 раза."));
		assertEquals("Версия 26.2 — огонь", ok("Версия 26.2 — огонь"));
	}

	@Test
	void truncates() {
		String s = "Первая фраза здесь. " + "Вторая фраза очень длинная ".repeat(10);
		assertEquals("Первая фраза здесь.", TextFilter.truncate(s, 30));
		String words = "слово ".repeat(30).strip();
		String cut = TextFilter.truncate(words, 50);
		assertTrue(cut.length() <= 50, cut);
		assertTrue(cut.endsWith("…"), cut);
		assertFalse(cut.contains("  "));
	}
}
