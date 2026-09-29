package ru.xetpy.rikoshet.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsTest {
	private static boolean ok(String text, String transcript) {
		return Tts.match(text, transcript).ok(0.8);
	}

	@Test
	void verbatimReadIsFullMatch() {
		assertEquals(1.0, Tts.match("Ты опять здесь? Я думал, тебя съели.", "Ты опять здесь?! Я думал — тебя съели...").recall(), 1e-9);
		assertTrue(ok("Ты опять здесь? Я думал, тебя съели.", "Ты опять здесь?! Я думал — тебя съели..."));
	}

	@Test
	void stageDirectionsAndYoAreIgnored() {
		assertTrue(ok("Это не ошибка, это *рыг* эксперимент.", "Это не ошибка, это эксперимент. *рыгает*"));
		assertTrue(ok("Всё, пьян", "все пьян"));
	}

	@Test
	void nickSpokenInRussianAndCutWordsPass() {
		// Копия сервера 29.09: ник по-русски, «оборуд…», «экспе…» — модель прочитала верно, сверка 0,72 отбросила зря
		assertTrue(ok("kate_cat, пальцем в оборудование не тычь. Хочешь быть полезным — принеси 4 пчелиные соты для эксперимента дня. Только сегодня.",
				"Кейт_кэт, *хрип* пальцем в оборуд... не тычь! Хочешь быть полезным – принеси 4 пчелиные соты для экспе... дня. *рюк* Только с-егодня!"));
	}

	@Test
	void stretchedWordsAreOneWord() {
		// Копия 29.09: «ядо-о-о-вито» и «прин-есёшь» считались лишними словами
		assertTrue(ok("Цитадель нашёл, а соты — нет? Не тыкай: ядовито.", "Цитадель нашёл, а соты — нет? Не тыкай: ядо-о-о-о-о-о-о-вито!"));
		assertTrue(ok("Светопыль сама не прилетит, пока ты не принесёшь хоть одну.", "Свето-пыль сама не прил-етит, пока ты не прин-есёшь хоть одну!"));
	}

	@Test
	void improvisationIsRejected() {
		// С образцов 29.09: модель ответила вместо того, чтобы прочитать
		assertFalse(ok("Это не ошибка, это *рыг* эксперимент. Ошибка — это ты.", "Понял, сейчас постараюсь озвучить в определённом стиле."));
		// …и дописала своё
		assertFalse(ok("Уаббалаббадабдаб!", "Уаббалаббадабдаб! Правда? Ну слушай, я тебе сейчас покажу, как всё работает"));
		assertFalse(ok("Ладно… ты молодец. Не привыкай.", "Ладно… ты молодец. Не привыкай. Реально задолбало, всё, я пошёл отсюда, пока вы тут все не сдохли"));
	}

	@Test
	void shortCutsDoNotCount() {
		assertFalse(Tts.same("по", "пока"), "обрывок короче 4 букв — не слово");
		assertTrue(Tts.same("экспе", "эксперимента"));
	}

	@Test
	void upsampleDoublesWithMidpoints() {
		byte[] pcm = {10, 0, 30, 0, -20, -1};  // 10, 30, -20 в little-endian
		assertArrayEquals(new short[] {10, 20, 30, 5, -20, -20}, Tts.to48k(pcm));
	}
}
