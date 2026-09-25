package ru.xetpy.rikoshet.ai;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseParserTest {
	private final PromptLibrary lib = new PromptLibrary(Path.of("does-not-exist"));

	@Test
	void plainFencedAndWrapped() {
		assertEquals("a", ResponseParser.parse("{\"say\":\"a\"}", lib.schema("line")).value().get("say").getAsString());
		assertTrue(ResponseParser.parse("```json\n{\"say\":\"a\"}\n```", lib.schema("line")).ok());
		assertTrue(ResponseParser.parse("Вот ответ: {\"say\":\"a\"} — готово", lib.schema("line")).ok());
	}

	@Test
	void failures() {
		assertFalse(ResponseParser.parse("", lib.schema("line")).ok());
		assertFalse(ResponseParser.parse("просто текст", lib.schema("line")).ok());
		assertFalse(ResponseParser.parse("[1,2]", lib.schema("line")).ok());
		assertFalse(ResponseParser.parse("{\"say\":\"a\"", lib.schema("line")).ok());
		assertFalse(ResponseParser.parse("{\"text\":\"a\"}", lib.schema("line")).ok());
	}
}
