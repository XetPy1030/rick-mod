package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ночные пакеты через Batch API (половина цены): создать пакет для маршрута, опрашивать, пока
 * не завершится, разобрать каждый ответ как живой (схема, ai_log, бюджет) и отдать обработчику
 * в главном потоке. Пакет пишется в ai_batch и после перезапуска опрашивается дальше.
 */
public final class BatchService {
	static final long POLL_SECONDS = 90;

	/** Запрос пакета: meta — что это (пул, ключ…), обработчик прочитает. */
	public record Req(String customId, String system, String user, JsonObject meta) {
	}

	/** Что делать с результатами пакета одного назначения. */
	public interface Handler {
		/** Ответ на запрос: result.ok() — прошёл схему. Главный поток. */
		void completed(JsonObject meta, AiResult result);

		/** Пакет не удался целиком (failed, expired, отказ при создании). Главный поток. */
		default void failed(String reason) {
		}
	}

	private record Pending(String id, String route, String model, String purpose, String schema, Map<String, JsonObject> requests) {
	}

	private final Logger log;
	private final Clock clock;
	private final AiService ai;
	private final Database db;
	private final Executor main;
	private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
	private final Map<String, Pending> pending = new ConcurrentHashMap<>();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "rikoshet-batch");
		t.setDaemon(true);
		return t;
	});

	public BatchService(Logger log, Clock clock, AiService ai, Database db, Executor main) {
		this.log = log;
		this.clock = clock;
		this.ai = ai;
		this.db = db;
		this.main = main;
	}

	public void register(String purpose, Handler handler) {
		handlers.put(purpose, handler);
	}

	/** После старта: продолжить опрос незавершённых пакетов. */
	public void resume() throws SQLException {
		List<Pending> list = db.call(c -> {
			List<Pending> out = new ArrayList<>();
			try (PreparedStatement st = c.prepareStatement(
					"SELECT id, route, model, purpose, requests FROM ai_batch WHERE status NOT IN ('completed', 'failed', 'expired', 'cancelled')");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					JsonObject req = JsonParser.parseString(rs.getString(5)).getAsJsonObject();
					Map<String, JsonObject> m = new ConcurrentHashMap<>();
					String schema = req.has("_schema") ? req.get("_schema").getAsString() : "lines";
					req.entrySet().forEach(e -> {
						if (!e.getKey().startsWith("_")) {
							m.put(e.getKey(), e.getValue().getAsJsonObject());
						}
					});
					out.add(new Pending(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), schema, m));
				}
			}
			return out;
		});
		for (Pending p : list) {
			pending.put(p.id(), p);
			schedule(p, 5);
		}
		if (!list.isEmpty()) {
			log.info("[пакеты] продолжаю опрос: {}", list.size());
		}
	}

	public void stop() {
		timer.shutdownNow();
	}

	public int pendingCount() {
		return pending.size();
	}

	/**
	 * Отправить пакет. Модели маршрута перебираются, пока не найдётся с пакетным вариантом.
	 * Возвращает id пакета; ошибка — если отправить нельзя (тогда назначение делает живые запросы).
	 */
	public CompletableFuture<String> submit(String purpose, String route, String schema, List<Req> requests) {
		String refuse = ai.refuseBatch();
		if (refuse != null) {
			return CompletableFuture.failedFuture(new IllegalStateException(refuse));
		}
		AiRoute r = ai.config().ai().route(route);
		JsonObject schemaJson = ai.schema(schema);
		return CompletableFuture.supplyAsync(() -> {
			BatchClient client = ai.batchClient();
			String lastError = "нет моделей";
			for (ModelSpec m : r.models()) {
				List<Map.Entry<String, JsonObject>> bodies = new ArrayList<>();
				for (Req q : requests) {
					bodies.add(Map.entry(q.customId(), OpenRouterClient.body(m, r, q.system(), q.user(), schema, schemaJson)));
				}
				BatchClient.Created c = client.create(m.id(), bodies);
				if (c.ok()) {
					Map<String, JsonObject> meta = new ConcurrentHashMap<>();
					requests.forEach(q -> meta.put(q.customId(), q.meta()));
					Pending p = new Pending(c.id(), route, m.id(), purpose, schema, meta);
					save(p, c.status());
					pending.put(p.id(), p);
					schedule(p, POLL_SECONDS);
					log.info("[пакеты] {}: {} запросов → {} ({})", purpose, requests.size(), m.id(), c.id());
					return c.id();
				}
				lastError = m.id() + ": " + c.error();
				if (!c.noBatchModel()) {
					break;
				}
			}
			throw new IllegalStateException(lastError);
		}, timer);
	}

	private void schedule(Pending p, long seconds) {
		timer.schedule(() -> poll(p), seconds, TimeUnit.SECONDS);
	}

	private void poll(Pending p) {
		BatchClient.Polled res = ai.batchClient().poll(p.id());
		if (res.status() == null) {
			log.info("[пакеты] {}: опрос не удался ({}), повтор", p.id(), res.error());
			schedule(p, POLL_SECONDS * 2);
			return;
		}
		if (!res.terminal()) {
			schedule(p, POLL_SECONDS);
			return;
		}
		pending.remove(p.id());
		Handler h = handlers.get(p.purpose());
		if (!"completed".equals(res.status())) {
			finish(p, res.status(), 0);
			log.warn("[пакеты] {} {}: {}", p.purpose(), res.status(), res.error());
			if (h != null) {
				main.execute(() -> h.failed(res.status()));
			}
			return;
		}
		JsonObject schema = ai.schema(p.schema());
		List<OpenRouterClient.Attempt> attempts = new ArrayList<>();
		double own = 0;
		for (BatchClient.Result r : res.results()) {
			OpenRouterClient.Attempt a = r.body() == null
					? new OpenRouterClient.Attempt(r.status(), null, null, null, p.model(), null, OpenRouterClient.Usage.NONE, 0,
							r.error() == null ? "пустой результат" : r.error())
					: OpenRouterClient.parse(r.status() == 0 ? 200 : r.status(), r.body(), 0);
			attempts.add(a);
			own += a.usage().cost();
		}
		// В ответах пакета своей цены нет — только общая на пакет: делим поровну, чтобы попала в бюджет
		double share = own == 0 && !attempts.isEmpty() ? res.cost() / attempts.size() : -1;
		double cost = 0;
		List<Runnable> deliver = new ArrayList<>();
		for (int i = 0; i < attempts.size(); i++) {
			BatchClient.Result r = res.results().get(i);
			OpenRouterClient.Attempt a = attempts.get(i);
			OpenRouterClient.Usage u = share < 0 ? a.usage() : new OpenRouterClient.Usage(a.usage().promptTokens(),
					a.usage().cachedTokens(), a.usage().completionTokens(), a.usage().reasoningTokens(), share);
			JsonObject meta = p.requests().get(r.customId());
			ResponseParser.Parsed parsed = a.answered() ? ResponseParser.parse(a.content(), schema) : null;
			String status = parsed == null ? "error" : parsed.ok() ? "ok" : "invalid";
			String error = a.error() != null ? a.error() : parsed != null && !parsed.ok() ? String.join("; ", parsed.errors()) : null;
			ai.recordBatchResult(p.route(), p.purpose() + ":batch", a.model() != null ? a.model() : p.model(), status, u, a.content(), error);
			cost += u.cost();
			AiResult result = new AiResult("ok".equals(status) ? AiStatus.OK : AiStatus.FAILED, "ok".equals(status) ? parsed.value() : null,
					a.model(), u.cost(), 0, 1, error);
			if (h != null && meta != null) {
				deliver.add(() -> h.completed(meta, result));
			}
		}
		double total = cost;
		finish(p, "completed", total);
		log.info("[пакеты] {} готов: {} ответов, ${}", p.purpose(), res.results().size(), String.format("%.4f", total));
		main.execute(() -> deliver.forEach(Runnable::run));
	}

	private void save(Pending p, String status) {
		JsonObject req = new JsonObject();
		req.addProperty("_schema", p.schema());
		p.requests().forEach(req::add);
		long now = clock.millis();
		db.execute("batch", c -> {
			try (PreparedStatement st = c.prepareStatement(
					"INSERT INTO ai_batch (id, created_at, route, model, purpose, requests, status) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
				st.setString(1, p.id());
				st.setLong(2, now);
				st.setString(3, p.route());
				st.setString(4, p.model());
				st.setString(5, p.purpose());
				st.setString(6, req.toString());
				st.setString(7, status == null ? "validating" : status);
				st.executeUpdate();
			}
		});
	}

	private void finish(Pending p, String status, double cost) {
		long now = clock.millis();
		db.execute("batch done", c -> {
			try (PreparedStatement st = c.prepareStatement("UPDATE ai_batch SET status = ?, done_at = ?, cost_usd = ? WHERE id = ?")) {
				st.setString(1, status);
				st.setLong(2, now);
				st.setDouble(3, cost);
				st.setString(4, p.id());
				st.executeUpdate();
			}
		});
	}
}
