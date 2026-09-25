package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Одна попытка запроса к OpenRouter, без повторов и без выбора модели — это делает {@link AiService}.
 * Блокирующий вызов: только из пула ИИ, никогда из главного потока.
 */
public final class OpenRouterClient {
	private final HttpClient http;
	private final String baseUrl;
	private final Supplier<String> key;

	public OpenRouterClient(String baseUrl, Supplier<String> key) {
		this.baseUrl = baseUrl;
		this.key = key;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	public record Usage(int promptTokens, int cachedTokens, int completionTokens, int reasoningTokens, double cost) {
		static final Usage NONE = new Usage(0, 0, 0, 0, 0);
	}

	/**
	 * @param http          код HTTP; 0 — сеть или таймаут; для ошибки внутри ответа 200 — её code
	 * @param content       текст ответа модели или null
	 * @param finishReason  stop, length, content_filter…
	 * @param model         какая модель ответила на самом деле
	 * @param error         текст ошибки для лога или null
	 */
	public record Attempt(int http, String content, String finishReason, String refusal, String model, String provider,
			Usage usage, long latencyMs, String error) {
		public boolean answered() {
			return error == null && content != null;
		}

		/** Стоит ли пробовать ещё раз (ту же или запасную модель). */
		public boolean retryable() {
			return http == 0 || http == 408 || http == 429 || http >= 500;
		}

		/** Ключ не принят или кончились деньги: дальше пробовать бессмысленно. */
		public boolean keyProblem() {
			return http == 401 || http == 402 || http == 403;
		}

		public boolean timedOut() {
			return http == 0 && error != null && error.startsWith("таймаут");
		}
	}

	static JsonObject body(ModelSpec model, AiRoute route, String system, String user, String schemaName, JsonObject schema) {
		JsonObject sysPart = new JsonObject();
		sysPart.addProperty("type", "text");
		sysPart.addProperty("text", system);
		JsonObject cache = new JsonObject();
		cache.addProperty("type", "ephemeral");
		sysPart.add("cache_control", cache);
		JsonArray sysContent = new JsonArray();
		sysContent.add(sysPart);

		JsonObject sysMsg = new JsonObject();
		sysMsg.addProperty("role", "system");
		sysMsg.add("content", sysContent);
		JsonObject userMsg = new JsonObject();
		userMsg.addProperty("role", "user");
		userMsg.addProperty("content", user);
		JsonArray messages = new JsonArray();
		messages.add(sysMsg);
		messages.add(userMsg);

		JsonObject js = new JsonObject();
		js.addProperty("name", schemaName);
		js.addProperty("strict", true);
		js.add("schema", schema);
		JsonObject format = new JsonObject();
		format.addProperty("type", "json_schema");
		format.add("json_schema", js);

		JsonObject provider = new JsonObject();
		provider.addProperty("require_parameters", true);
		JsonObject usage = new JsonObject();
		usage.addProperty("include", true);

		JsonObject body = new JsonObject();
		body.addProperty("model", model.id());
		body.add("messages", messages);
		body.add("response_format", format);
		body.add("provider", provider);
		body.add("usage", usage);
		body.addProperty("max_tokens", route.maxTokens());
		String effort = model.effortOr(route.reasoning());
		if (!"default".equals(effort)) {
			JsonObject reasoning = new JsonObject();
			reasoning.addProperty("effort", effort);
			body.add("reasoning", reasoning);
		}
		return body;
	}

	public Attempt send(JsonObject body, Duration timeout) {
		String k = key.get();
		HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
				.timeout(timeout)
				.header("Authorization", "Bearer " + k)
				.header("Content-Type", "application/json")
				.header("X-Title", "Rikoshet")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		long t0 = System.nanoTime();
		HttpResponse<String> resp;
		try {
			resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (HttpTimeoutException e) {
			return fail(0, "таймаут " + timeout.toMillis() + " мс", t0);
		} catch (IOException e) {
			return fail(0, "сеть: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()), t0);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return fail(0, "прервано", t0);
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		return parse(resp.statusCode(), resp.body(), ms);
	}

	static Attempt parse(int status, String raw, long ms) {
		JsonObject root = null;
		try {
			JsonElement el = JsonParser.parseString(raw);
			if (el.isJsonObject()) {
				root = el.getAsJsonObject();
			}
		} catch (JsonParseException | IllegalStateException e) {
			// ниже станет ошибкой
		}
		if (root == null) {
			return new Attempt(status == 200 ? 0 : status, null, null, null, null, null, Usage.NONE, ms,
					"HTTP " + status + ": " + clip(raw));
		}
		// OpenRouter иногда отдаёт 200 с error внутри и без choices
		if (root.has("error") && (!root.has("choices") || status != 200)) {
			JsonObject err = root.get("error").isJsonObject() ? root.getAsJsonObject("error") : new JsonObject();
			int code = err.has("code") && err.get("code").isJsonPrimitive() && err.getAsJsonPrimitive("code").isNumber()
					? err.get("code").getAsInt() : (status == 200 ? 502 : status);
			String msg = str(err, "message");
			return new Attempt(code, null, null, null, str(root, "model"), str(root, "provider"), usage(root), ms,
					"HTTP " + code + ": " + clip(msg == null ? err.toString() : msg));
		}
		if (status != 200) {
			return new Attempt(status, null, null, null, null, null, Usage.NONE, ms, "HTTP " + status + ": " + clip(raw));
		}
		JsonObject choice = null;
		if (root.has("choices") && root.get("choices").isJsonArray() && !root.getAsJsonArray("choices").isEmpty()) {
			JsonElement c = root.getAsJsonArray("choices").get(0);
			choice = c.isJsonObject() ? c.getAsJsonObject() : null;
		}
		JsonObject message = choice != null && choice.has("message") && choice.get("message").isJsonObject()
				? choice.getAsJsonObject("message") : new JsonObject();
		String content = str(message, "content");
		String refusal = str(message, "refusal");
		String finish = choice == null ? null : str(choice, "finish_reason");
		String error = null;
		if (content == null || content.isBlank()) {
			error = refusal != null ? "отказ: " + clip(refusal) : "пустой ответ, finish_reason " + finish;
			content = null;
		}
		return new Attempt(200, content, finish, refusal, str(root, "model"), str(root, "provider"), usage(root), ms, error);
	}

	private static Usage usage(JsonObject root) {
		if (!root.has("usage") || !root.get("usage").isJsonObject()) {
			return Usage.NONE;
		}
		JsonObject u = root.getAsJsonObject("usage");
		return new Usage(
				integer(u, "prompt_tokens"),
				integer(sub(u, "prompt_tokens_details"), "cached_tokens"),
				integer(u, "completion_tokens"),
				integer(sub(u, "completion_tokens_details"), "reasoning_tokens"),
				u.has("cost") && u.get("cost").isJsonPrimitive() ? u.get("cost").getAsDouble() : 0);
	}

	private static JsonObject sub(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
	}

	private static int integer(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isNumber() ? o.get(key).getAsInt() : 0;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}

	private static Attempt fail(int http, String error, long t0) {
		return new Attempt(http, null, null, null, null, null, Usage.NONE, (System.nanoTime() - t0) / 1_000_000, error);
	}

	private static String clip(String s) {
		if (s == null) {
			return "";
		}
		String one = s.replaceAll("\\s+", " ").strip();
		return one.length() > 300 ? one.substring(0, 300) + "…" : one;
	}
}
