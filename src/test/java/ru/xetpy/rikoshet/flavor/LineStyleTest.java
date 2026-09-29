package ru.xetpy.rikoshet.flavor;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineStyleTest {
	private static final List<String> NOTES = Arrays.asList(
			"Мистер Жопосранчик, «Ууу-ии!». В каноне настоящий друг", null, "Просто Морти: нервный внук Рика");

	@Test
	void catchphrasesFromQuotes() {
		assertEquals(Set.of("Ууу-ии!"), LineStyle.catchphrases(NOTES));
		assertEquals(Set.of(), LineStyle.catchphrases(List.of("без ёлочек")));
	}

	@Test
	void sharesAreNearConstants() {
		Random rnd = new Random(42);
		int n = 4000;
		int burp = 0;
		int phrase = 0;
		int shortLine = 0;
		for (int i = 0; i < n; i++) {
			String t = LineStyle.tail(rnd, NOTES);
			burp += t.contains("Без *рыг*") ? 0 : 1;
			phrase += t.contains("Без «Ууу-ии!».") ? 1 : 0;
			shortLine += t.contains("Совсем коротко") ? 1 : 0;
		}
		assertEquals(LineStyle.BURP, burp / (double) n, 0.03);
		assertEquals(LineStyle.NO_CATCHPHRASE, phrase / (double) n, 0.03);
		assertEquals(LineStyle.SHORT, shortLine / (double) n, 0.03);
	}

	@Test
	void emptyWhenNothingDecided() {
		// Кубики выпали «можно рыгать», фраз нет, не коротко — хвоста нет
		Random all = new Random() {
			@Override
			public double nextDouble() {
				return 0.1;
			}
		};
		assertEquals("", LineStyle.tail(new Random() {
			@Override
			public double nextDouble() {
				return 0.3;
			}
		}, List.of()));
		assertTrue(LineStyle.tail(all, NOTES).startsWith("\n\n## Эта реплика"));
	}
}
