package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Batch API OpenRouter: POST /batches — пакет запросов к одной модели, GET /batches/{id} —
 * статус и результаты (docs/architecture/ai-integration.md#провайдер). Половина цены, ответ
 * обычно за минуты, гарантия — 24 часа. Вызовы блокирующие: только не из главного потока.
 */
public final class BatchClient {
	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	private final String baseUrl;
	private final Supplier<String> key;

	public BatchClient(String baseUrl, Supplier<String> key) {
		this.baseUrl = baseUrl;
		this.key = key;
	}

	/** http 0 — сеть. error — текст ошибки OpenRouter, если был. */
	public record Created(int http, String id, String status, String error) {
		public boolean ok() {
			return id != null && error == null;
		}

		/** Модель не умеет пакеты — пробуем следующую из маршрута. */
		public boolean noBatchModel() {
			return http == 400 && error != null && error.contains(":batch");
		}
	}

	/** Результат одного запроса пакета: HTTP-код и тело ответа как у обычного chat/completions. */
	public record Result(String customId, int status, String body, String error) {
	}

	public record Polled(int http, String status, List<Result> results, double cost, String error) {
		public boolean terminal() {
			return "completed".equals(status) || "failed".equals(status) || "expired".equals(status) || "cancelled".equals(status);
		}
	}

	/** requests — custom_id → тело запроса (как для живого, model совпадает с пакетом). */
	public Created create(String model, List<Map.Entry<String, JsonObject>> requests) {
		JsonArray arr = new JsonArray();
		for (var r : requests) {
			JsonObject o = new JsonObject();
			o.addProperty("custom_id", r.getKey());
			o.add("body", r.getValue());
			arr.add(o);
		}
		JsonObject root = new JsonObject();
		root.addProperty("endpoint", "/v1/chat/completions");
		root.addProperty("model", model);
		root.add("requests", arr);
		Response resp = send(HttpRequest.newBuilder(URI.create(baseUrl + "/batches"))
				.POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8)));
		if (resp.json == null) {
			return new Created(resp.status, null, null, resp.error);
		}
		String err = error(resp.json);
		if (err != null || resp.status >= 300) {
			return new Created(resp.status, null, null, err != null ? err : "HTTP " + resp.status);
		}
		return new Created(resp.status, str(resp.json, "id"), str(resp.json, "status"), null);
	}

	public Polled poll(String id) {
		Response resp = send(HttpRequest.newBuilder(URI.create(baseUrl + "/batches/" + id)).GET());
		if (resp.json == null) {
			return new Polled(resp.status, null, List.of(), 0, resp.error);
		}
		String err = error(resp.json);
		if (resp.status >= 300) {
			return new Polled(resp.status, null, List.of(), 0, err != null ? err : "HTTP " + resp.status);
		}
		return parsePoll(resp.json);
	}

	static Polled parsePoll(JsonObject root) {
		List<Result> results = new ArrayList<>();
		JsonElement r = root.get("results");
		if (r != null && r.isJsonArray()) {
			for (JsonElement el : r.getAsJsonArray()) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject o = el.getAsJsonObject();
				JsonObject response = o.has("response") && o.get("response").isJsonObject() ? o.getAsJsonObject("response") : null;
				int status = response != null && response.has("status_code") ? response.get("status_code").getAsInt() : 0;
				String body = response != null && response.has("body") && !response.get("body").isJsonNull() ? response.get("body").toString() : null;
				JsonElement e = o.get("error");
				String error = e == null || e.isJsonNull() ? null : e.isJsonPrimitive() ? e.getAsString() : e.toString();
				results.add(new Result(str(o, "custom_id"), status, body, error));
			}
		}
		double cost = 0;
		if (root.has("usage") && root.get("usage").isJsonObject() && root.getAsJsonObject("usage").has("cost")) {
			cost = root.getAsJsonObject("usage").get("cost").getAsDouble();
		}
		JsonElement e = root.get("error");
		String error = e == null || e.isJsonNull() ? null : e.isJsonPrimitive() ? e.getAsString() : e.toString();
		return new Polled(200, str(root, "status"), results, cost, error);
	}

	private record Response(int status, JsonObject json, String error) {
	}

	private Response send(HttpRequest.Builder b) {
		HttpRequest req = b.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + key.get())
				.header("Content-Type", "application/json")
				.header("X-Title", "Rikoshet")
				.build();
		try {
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			try {
				JsonElement el = JsonParser.parseString(resp.body());
				return new Response(resp.statusCode(), el.isJsonObject() ? el.getAsJsonObject() : null, "HTTP " + resp.statusCode());
			} catch (RuntimeException e) {
				return new Response(resp.statusCode(), null, "HTTP " + resp.statusCode() + ": не JSON");
			}
		} catch (IOException e) {
			return new Response(0, null, "сеть: " + e.getClass().getSimpleName());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Response(0, null, "прервано");
		}
	}

	private static String error(JsonObject root) {
		JsonElement e = root.get("error");
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonObject() && e.getAsJsonObject().has("message")) {
			return e.getAsJsonObject().get("message").getAsString();
		}
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}
}
