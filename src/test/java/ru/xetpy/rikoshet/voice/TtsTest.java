package ru.xetpy.rikoshet.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsTest {
	@Test
	void verbatimReadIsFullMatch() {
		assertEquals(1.0, Tts.fidelity("Ты опять здесь? Я думал, тебя съели.", "Ты опять здесь?! Я думал — тебя съели..."), 1e-9);
	}

	@Test
	void stageDirectionsAndYoAreIgnored() {
		assertEquals(1.0, Tts.fidelity("Это не ошибка, это *рыг* эксперимент.", "Это не ошибка, это эксперимент. *рыгает*"), 1e-9);
		assertEquals(1.0, Tts.fidelity("Всё, пьян", "все пьян"), 1e-9);
	}

	@Test
	void improvisationFallsBelowThreshold() {
		// С образцов 29.09: модель ответила вместо того, чтобы прочитать
		assertTrue(Tts.fidelity("Это не ошибка, это *рыг* эксперимент. Ошибка — это ты.",
				"Понял, сейчас постараюсь озвучить в определённом стиле.") < 0.8);
		// …и дописала своё
		assertTrue(Tts.fidelity("Уаббалаббадабдаб!", "Уаббалаббадабдаб! Правда? Ну слушай, я тебе сейчас покажу, как всё работает") < 0.8);
	}

	@Test
	void upsampleDoublesWithMidpoints() {
		byte[] pcm = {10, 0, 30, 0, -20, -1};  // 10, 30, -20 в little-endian
		assertArrayEquals(new short[] {10, 20, 30, 5, -20, -20}, Tts.to48k(pcm));
	}
}
