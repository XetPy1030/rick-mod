package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRouterClientTest {
	@Test
	void body() {
		AiRoute route = new AiRoute("flavor", List.of(ModelSpec.parse("a/b", null)), "none", 1024, 5);
		JsonObject b = OpenRouterClient.body(ModelSpec.parse("x-ai/grok-4.7@minimal", null), route, "SYS", "USER", "line", new JsonObject());
		assertEquals("x-ai/grok-4.7", b.get("model").getAsString());
		assertEquals("minimal", b.getAsJsonObject("reasoning").get("effort").getAsString());
		assertEquals("ephemeral", b.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content")
				.get(0).getAsJsonObject().getAsJsonObject("cache_control").get("type").getAsString());
		assertTrue(b.getAsJsonObject("response_format").getAsJsonObject("json_schema").get("strict").getAsBoolean());
		assertTrue(b.getAsJsonObject("provider").get("require_parameters").getAsBoolean());
		assertEquals(1024, b.get("max_tokens").getAsInt());
		JsonObject noReasoning = OpenRouterClient.body(ModelSpec.parse("a/b@default", null), route, "S", "U", "line", new JsonObject());
		assertFalse(noReasoning.has("reasoning"));
	}

	@Test
	void parseOk() {
		OpenRouterClient.Attempt a = OpenRouterClient.parse(200, """
				{"model":"deepseek/deepseek-v4.1-flash","provider":"DeepSeek",
				 "choices":[{"finish_reason":"stop","message":{"content":"{\\"say\\":\\"hi\\"}"}}],
				 "usage":{"prompt_tokens":1000,"completion_tokens":30,"cost":0.00012,
				          "prompt_tokens_details":{"cached_tokens":800},"completion_tokens_details":{"reasoning_tokens":0}}}""", 900);
		assertTrue(a.answered());
		assertEquals("{\"say\":\"hi\"}", a.content());
		assertEquals(800, a.usage().cachedTokens());
		assertEquals(0.00012, a.usage().cost(), 1e-9);
		assertEquals("DeepSeek", a.provider());
	}

	@Test
	void parseErrors() {
		OpenRouterClient.Attempt inner = OpenRouterClient.parse(200, "{\"error\":{\"code\":429,\"message\":\"slow down\"}}", 10);
		assertEquals(429, inner.http());
		assertTrue(inner.retryable());
		OpenRouterClient.Attempt key = OpenRouterClient.parse(401, "{\"error\":{\"code\":401,\"message\":\"No auth\"}}", 10);
		assertTrue(key.keyProblem());
		OpenRouterClient.Attempt credits = OpenRouterClient.parse(402, "{\"error\":{\"code\":402,\"message\":\"credits\"}}", 10);
		assertTrue(credits.keyProblem());
		OpenRouterClient.Attempt html = OpenRouterClient.parse(502, "<html>bad gateway</html>", 10);
		assertTrue(html.retryable());
		assertNull(html.content());
		OpenRouterClient.Attempt filtered = OpenRouterClient.parse(200,
				"{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"\"}}]}", 10);
		assertFalse(filtered.answered());
		assertEquals("content_filter", filtered.finishReason());
		OpenRouterClient.Attempt refusal = OpenRouterClient.parse(200,
				"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"refusal\":\"no\"}}]}", 10);
		assertEquals("no", refusal.refusal());
	}
}
