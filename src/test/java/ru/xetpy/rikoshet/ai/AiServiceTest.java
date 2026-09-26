package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.core.Secrets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AiService против локального HTTP-сервера, который притворяется OpenRouter. */
class AiServiceTest {
	private HttpServer http;
	private final List<String> models = Collections.synchronizedList(new ArrayList<>());
	private final List<AiLogEntry> log = Collections.synchronizedList(new ArrayList<>());
	/** Ответ по имени модели: код HTTP и тело. */
	private volatile Function<String, Object[]> reply;

	@BeforeEach
	void start() throws IOException {
		http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		http.createContext("/api/v1/chat/completions", ex -> {
			JsonObject body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			String model = body.get("model").getAsString();
			models.add(model);
			Object[] r = reply.apply(model);
			if (r[0] instanceof Integer sleep && sleep < 0) {
				try {
					Thread.sleep(-sleep);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				r = new Object[] {200, r[1]};
			}
			byte[] bytes = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders((Integer) r[0], bytes.length);
			ex.getResponseBody().write(bytes);
			ex.close();
		});
		http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		http.start();
	}

	@AfterEach
	void stop() {
		http.stop(0);
	}

	private static String ok(String say, double cost) {
		return "{\"model\":\"m\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
				+ JsonParser.parseString("\"" + say.replace("\"", "\\\"") + "\"") + "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"cost\":" + cost + "}}";
	}

	private AiService service(int timeoutSeconds, double budget, boolean overloaded) throws Exception {
		RikoshetConfig d = RikoshetConfig.defaults();
		AiRoute flavor = new AiRoute("flavor", List.of(ModelSpec.parse("a/main", null), ModelSpec.parse("b/backup", null)), "none", 256, timeoutSeconds);
		RikoshetConfig.Ai ai = new RikoshetConfig.Ai(true, "http://127.0.0.1:" + http.getAddress().getPort() + "/api/v1",
				budget, 2, 100, Map.of("flavor", flavor));
		RikoshetConfig cfg = new RikoshetConfig(d.protocolVersion(), d.timezone(), d.features(), ai, d.flavor(), d.content(), d.performance(), d.storage(),
				d.chronicle(), d.newspaper());
		return new AiService(LoggerFactory.getLogger("test"), Clock.systemUTC(), cfg, secrets(), new PromptLibrary(Path.of("none")),
				log::add, () -> overloaded, msg -> { });
	}

	private static Secrets secrets() throws Exception {
		// Ключ для теста — через файл во временной папке, окружение не трогаем
		Path dir = java.nio.file.Files.createTempDirectory("rk");
		java.nio.file.Files.writeString(dir.resolve("secrets.json5"), "{ openrouter_api_key: \"test\" }");
		Secrets s = Secrets.load(dir, new ArrayList<>());
		return s;
	}

	private AiResult call(AiService s) throws Exception {
		return s.submit(new AiRequest("flavor", "line", "sys", "user", "test", null)).get(10, TimeUnit.SECONDS);
	}

	@Test
	void mainModelAnswers() throws Exception {
		reply = m -> new Object[] {200, ok("{\"say\":\"Привет, Морти\"}", 0.001)};
		AiResult r = call(service(5, 1, false));
		assertEquals(AiStatus.OK, r.status());
		assertEquals("Привет, Морти", r.value().get("say").getAsString());
		assertEquals(List.of("a/main"), models);
		assertEquals("ok", log.getFirst().status());
	}

	@Test
	void invalidJsonFallsBackToBackup() throws Exception {
		reply = m -> m.equals("a/main") ? new Object[] {200, ok("не JSON", 0.001)} : new Object[] {200, ok("{\"say\":\"запасной\"}", 0.002)};
		AiService s = service(5, 1, false);
		AiResult r = call(s);
		assertEquals(AiStatus.OK, r.status());
		assertEquals("запасной", r.value().get("say").getAsString());
		assertEquals(List.of("a/main", "b/backup"), models);
		assertEquals(List.of("invalid", "ok"), log.stream().map(AiLogEntry::status).toList());
		assertEquals(0.003, s.budget().spentToday(), 1e-9);
	}

	@Test
	void serverErrorThenBackup() throws Exception {
		reply = m -> m.equals("a/main") ? new Object[] {503, "{\"error\":{\"code\":503,\"message\":\"down\"}}"} : new Object[] {200, ok("{\"say\":\"ок\"}", 0)};
		assertEquals(AiStatus.OK, call(service(5, 1, false)).status());
	}

	@Test
	void refusalsGiveRefused() throws Exception {
		reply = m -> new Object[] {200, "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"\"}}]}"};
		assertEquals(AiStatus.REFUSED, call(service(5, 1, false)).status());
	}

	@Test
	void slowAnswerTimesOutAndIsLoggedLate() throws Exception {
		reply = m -> new Object[] {-2500, ok("{\"say\":\"поздно\"}", 0.001)};
		AiService s = service(1, 1, false);
		AiResult r = call(s);
		assertEquals(AiStatus.TIMEOUT, r.status());
		assertTrue(r.latencyMs() < 2000, "future завершился по дедлайну: " + r.latencyMs());
	}

	@Test
	void badKeyBlocksUntilReload() throws Exception {
		reply = m -> new Object[] {401, "{\"error\":{\"code\":401,\"message\":\"No auth credentials\"}}"};
		AiService s = service(5, 1, false);
		assertEquals(AiStatus.PAUSED, call(s).status());
		assertEquals(List.of("a/main"), models, "после 401 запасную модель не пробуем");
		assertEquals(AiStatus.PAUSED, call(s).status());
		assertEquals(1, models.size(), "второй запрос не ушёл в сеть");
		s.reconfigure(s.config(), s.secrets());
		reply = m -> new Object[] {200, ok("{\"say\":\"снова\"}", 0)};
		assertEquals(AiStatus.OK, call(s).status());
	}

	@Test
	void localRefusals() throws Exception {
		reply = m -> new Object[] {200, ok("{\"say\":\"x\"}", 0)};
		assertEquals(AiStatus.BUDGET, call(service(5, 0, false)).status());
		assertEquals(AiStatus.OVERLOAD, call(service(5, 1, true)).status());
		AiService paused = service(5, 1, false);
		paused.setPaused(true);
		assertEquals(AiStatus.PAUSED, call(paused).status());
		assertEquals(List.of(), models);
		AtomicBoolean unused = new AtomicBoolean();
		assertTrue(!unused.get());
	}
}
