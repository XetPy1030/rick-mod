package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchClientTest {
	@Test
	void parseCompletedBatchAsLiveResponses() {
		// Формат — из документации OpenRouter (docs/batch-quickstart)
		BatchClient.Polled p = BatchClient.parsePoll(JsonParser.parseString("""
				{"id": "batch_123", "object": "batch", "status": "completed",
				 "usage": {"prompt_tokens": 20, "completion_tokens": 40, "cost": 0.000225},
				 "results": [
				   {"id": "r1", "custom_id": "p0", "response": {"status_code": 200, "request_id": "q",
				     "body": {"model": "moonshotai/kimi-k3", "choices": [{"index": 0, "message": {"role": "assistant",
				       "content": "{\\"lines\\": [\\"Опять лава, {player}?\\"]}"}, "finish_reason": "stop"}],
				       "usage": {"prompt_tokens": 10, "completion_tokens": 20, "cost": 0.0001}}}, "error": null},
				   {"id": "r2", "custom_id": "p1", "response": null, "error": {"message": "provider error"}}
				 ], "error": null}""").getAsJsonObject());
		assertTrue(p.terminal());
		assertEquals("completed", p.status());
		assertEquals(0.000225, p.cost(), 1e-9);
		assertEquals(2, p.results().size());
		BatchClient.Result ok = p.results().getFirst();
		assertEquals("p0", ok.customId());
		assertNull(ok.error());
		OpenRouterClient.Attempt a = OpenRouterClient.parse(ok.status(), ok.body(), 0);
		assertTrue(a.answered());
		assertEquals("moonshotai/kimi-k3", a.model());
		assertEquals(0.0001, a.usage().cost(), 1e-9);
		assertTrue(a.content().contains("Опять лава"));
		BatchClient.Result bad = p.results().getLast();
		assertNull(bad.body());
		assertTrue(bad.error().contains("provider error"));
	}

	@Test
	void inProgressIsNotTerminal() {
		BatchClient.Polled p = BatchClient.parsePoll(JsonParser.parseString(
				"{\"id\": \"b\", \"status\": \"in_progress\", \"usage\": null, \"results\": null, \"error\": null}").getAsJsonObject());
		assertFalse(p.terminal());
		assertTrue(p.results().isEmpty());
	}

	@Test
	void noBatchModelIsRecognized() {
		BatchClient.Created c = new BatchClient.Created(400, null, null, "Model 'anthropic/claude-sonnet-5' does not have a :batch endpoint.");
		assertTrue(c.noBatchModel());
		assertFalse(c.ok());
	}
}
