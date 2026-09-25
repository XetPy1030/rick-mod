package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaValidatorTest {
	private final PromptLibrary lib = new PromptLibrary(Path.of("does-not-exist"));

	private java.util.List<String> check(String schema, String json) {
		return SchemaValidator.validate(JsonParser.parseString(json), lib.schema(schema));
	}

	@Test
	void line() {
		assertEquals(java.util.List.of(), check("line", "{\"say\":\"привет\"}"));
		assertTrue(check("line", "{}").getFirst().contains("say"));
		assertTrue(check("line", "{\"say\":1}").getFirst().contains("string"));
		assertTrue(check("line", "{\"say\":\"a\",\"extra\":1}").getFirst().contains("extra"));
	}

	@Test
	void dialogue() {
		String good = """
				{"say":"Ну?","mood":"bored","memory_note":"",
				 "actions":[{"type":"remember","note":"любит лаву","delta":null,"reason":null,"item":null,"count":null}]}""";
		assertEquals(java.util.List.of(), check("dialogue", good));
		assertTrue(check("dialogue", good.replace("bored", "sleepy")).getFirst().contains("sleepy"));
		assertTrue(check("dialogue", good.replace("\"count\":null", "\"count\":1.5")).getFirst().contains("integer"));
		assertEquals(java.util.List.of(), check("dialogue", good.replace("\"count\":null", "\"count\":2.0")));
		assertTrue(check("dialogue", good.replace("\"remember\"", "\"op_player\"")).getFirst().contains("op_player"));
	}

	@Test
	void newspaper() {
		JsonObject s = lib.schema("newspaper");
		assertTrue(s.getAsJsonArray("required").size() >= 4);
		assertTrue(check("newspaper", "{\"headline\":\"h\",\"articles\":[{\"title\":\"t\"}],\"ad\":\"a\",\"weather\":\"w\"}")
				.getFirst().contains("body"));
	}
}
