package ru.xetpy.rikoshet.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelSpecTest {
	@Test
	void parses() {
		ModelSpec m = ModelSpec.parse("x-ai/grok-4.7@minimal", null);
		assertEquals("x-ai/grok-4.7", m.id());
		assertEquals("minimal", m.effortOr("low"));
		assertEquals("low", ModelSpec.parse("openai/gpt-6-luna", null).effortOr("low"));
		assertEquals("deepseek/deepseek-v4.1-flash", ModelSpec.parse(" deepseek/deepseek-v4.1-flash ", null).toString());
	}

	@Test
	void rejects() {
		List<String> errors = new ArrayList<>();
		assertNull(ModelSpec.parse("gpt", errors));
		assertNull(ModelSpec.parse("openai/gpt@turbo", errors));
		assertNull(ModelSpec.parse("openai/gpt 6", errors));
		assertEquals(3, errors.size());
	}
}
