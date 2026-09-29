package ru.xetpy.rikoshet.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.xetpy.rikoshet.chat.ChatDetector.Kind.ADDRESS;
import static ru.xetpy.rikoshet.chat.ChatDetector.Kind.MENTION;
import static ru.xetpy.rikoshet.chat.ChatDetector.Kind.NONE;

class ChatDetectorTest {
	private static final Set<String> Q = ChatDetector.qualifiers(List.of("Токсик Рик", "Фермер Рик", "Злой коп Рик", "Морти", "Жопосранчик"));

	@Test
	void qualifiersFromRoles() {
		assertEquals(Set.of("токсик", "фермер", "злой коп"), Q);
	}

	@Test
	void liveServerLines() {
		// 28.09, после подколок Рика
		assertEquals(ADDRESS, ChatDetector.classify("рик хуесос", Q));
		assertEquals(ADDRESS, ChatDetector.classify("рик ты хуесос", Q));
		assertEquals(NONE, ChatDetector.classify("ну блин", Q));
		assertEquals(NONE, ChatDetector.classify("hello", Q));
	}

	@Test
	void address() {
		for (String s : List.of("Рик, ты тут?", "эй рик", "RICK где алмазы", "рикки ты где", "спокойной ночи рик", "рик!!!", "@рик",
				"рик, дай алмаз", "слышь, рик, помоги", "ну ты и рик", "а ты что думаешь, рик?", "ё-моё, рик: помоги")) {
			assertEquals(ADDRESS, ChatDetector.classify(s, Q), s);
		}
	}

	@Test
	void mention() {
		for (String s : List.of("что с риком", "спасибо рику за помощь", "а рику норм?", "это всё из-за рика", "мы с риком не дружим")) {
			assertEquals(MENTION, ChatDetector.classify(s, Q), s);
		}
	}

	@Test
	void notRick() {
		for (String s : List.of("токсик рик где ты", "фермер рик опять грядки копает", "Токсик Рик, го в незер", "злой коп рик забанил меня",
				"рикошет это название мода?", "америка", "трики", "кирпич", "го в незер", "африка", "эрик пришёл", "ёлки")) {
			assertEquals(NONE, ChatDetector.classify(s, Q), s);
		}
	}

	@Test
	void playerRickAndNpcInOneLine() {
		assertEquals(ADDRESS, ChatDetector.classify("рик, скажи токсик рику, что он лох", Q));
	}

	@Test
	void addressedToOther() {
		List<String> names = List.of("морти", "жопосранчик", "salt115", "немой/виар морти");
		assertTrue(ChatDetector.addressedToOther("морти, иди сюда", names));
		assertTrue(ChatDetector.addressedToOther("@salt115 ты где", names));
		assertTrue(ChatDetector.addressedToOther("Жопосранчик го", names));
		assertFalse(ChatDetector.addressedToOther("ну блин", names));
		assertFalse(ChatDetector.addressedToOther("мортис", names));
		assertEquals(2, ChatDetector.wordCount("ну блин!!"));
	}
}
