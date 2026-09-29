package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.core.Secrets;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Все запросы к ИИ. Проверки «можно ли» — синхронно в вызывающем потоке, сеть — в своём пуле.
 * Модели маршрута перебираются по порядку, пока не придёт JSON по схеме или не кончится
 * timeout_seconds маршрута. Future завершается не позже дедлайна; то, что пришло позже,
 * пишется в ai_log со статусом late и в чат не идёт.
 */
public final class AiService {
	/** Маршруты, которые при MSPT выше mspt_hard сразу уходят на заготовки. */
	private static final Set<String> SHED_ON_HARD = Set.of("flavor");
	private static final long RETRY_PAUSE_MS = 300;
	/** Меньше этого до дедлайна новую попытку не начинаем. */
	private static final long MIN_ATTEMPT_MS = 500;
	private static final long CREDITS_BLOCK_MINUTES = 15;
	private static final int OUTPUT_LOG_LIMIT = 4000;

	private final Logger log;
	private final Clock clock;
	private final PromptLibrary prompts;
	private final AiLogSink sink;
	private final BooleanSupplier overloaded;
	private final Consumer<String> alert;
	private final Budget budget;
	private final RateLimiter limiter;
	private final AiStats stats = new AiStats();
	private final ThreadPoolExecutor pool;
	/** Попытки гонки: поток пула ждёт обе, сам запрос не делает. */
	private final ExecutorService racers = Executors.newCachedThreadPool(daemonThreads("rikoshet-ai-race-"));

	private volatile RikoshetConfig config;
	private volatile Secrets secrets;
	private volatile OpenRouterClient client;
	private volatile boolean paused;
	/** Ключ не принят (Long.MAX_VALUE — до reload) или кончились кредиты. */
	private volatile long blockedUntilMillis;
	private volatile String blockReason;

	public AiService(Logger log, Clock clock, RikoshetConfig config, Secrets secrets, PromptLibrary prompts,
			AiLogSink sink, BooleanSupplier overloaded, Consumer<String> alert) {
		this.log = log;
		this.clock = clock;
		this.prompts = prompts;
		this.sink = sink;
		this.overloaded = overloaded;
		this.alert = alert;
		this.config = config;
		this.secrets = secrets;
		this.budget = new Budget(clock, config.timezone(), config.ai().dailyBudgetUsd());
		this.limiter = new RateLimiter(config.ai().requestsPerMinute(), System::nanoTime);
		int n = config.ai().maxConcurrent();
		this.pool = new ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), daemonThreads("rikoshet-ai-"));
		this.pool.allowCoreThreadTimeOut(true);
		this.client = new OpenRouterClient(config.ai().baseUrl(), this::key);
	}

	private String key() {
		return secrets.openRouterKey();
	}

	/** После /rickadmin reload. Блокировка по ключу снимается: вдруг ключ поменяли. */
	public void reconfigure(RikoshetConfig config, Secrets secrets) {
		RikoshetConfig old = this.config;
		this.config = config;
		this.secrets = secrets;
		budget.configure(config.timezone(), config.ai().dailyBudgetUsd());
		limiter.configure(config.ai().requestsPerMinute());
		int n = config.ai().maxConcurrent();
		if (n > pool.getMaximumPoolSize()) {
			pool.setMaximumPoolSize(n);
			pool.setCorePoolSize(n);
		} else {
			pool.setCorePoolSize(n);
			pool.setMaximumPoolSize(n);
		}
		if (!old.ai().baseUrl().equals(config.ai().baseUrl())) {
			client = new OpenRouterClient(config.ai().baseUrl(), this::key);
		}
		blockedUntilMillis = 0;
		blockReason = null;
	}

	public CompletableFuture<AiResult> submit(AiRequest req) {
		RikoshetConfig cfg = config;
		AiRoute route = cfg.ai().route(req.route());
		AiResult refused = precheck(cfg, req, route);
		if (refused != null) {
			stats.result(req.route(), refused);
			return CompletableFuture.completedFuture(refused);
		}
		long timeoutMs = route.timeoutSeconds() * 1000L;
		long deadline = System.nanoTime() + timeoutMs * 1_000_000;
		CompletableFuture<AiResult> future = new CompletableFuture<>();
		try {
			pool.execute(() -> {
				AiResult r = run(req, route, deadline, future);
				if (future.complete(r)) {
					stats.result(req.route(), r);
				}
			});
		} catch (RejectedExecutionException e) {
			AiResult r = AiResult.local(AiStatus.OVERLOAD, "очередь ИИ полна");
			stats.result(req.route(), r);
			return CompletableFuture.completedFuture(r);
		}
		// Страховка: сетевой таймаут стоит на каждой попытке, но очередь пула тоже съедает время
		AiResult timeout = new AiResult(AiStatus.TIMEOUT, null, null, 0, timeoutMs, 0, "дедлайн " + route.timeoutSeconds() + " с");
		future.completeOnTimeout(timeout, timeoutMs + 100, TimeUnit.MILLISECONDS);
		future.thenAccept(r -> {
			if (r == timeout) {
				stats.result(req.route(), r);
			}
		});
		return future;
	}

	private AiResult precheck(RikoshetConfig cfg, AiRequest req, AiRoute route) {
		if (route == null) {
			return AiResult.local(AiStatus.FAILED, "нет маршрута " + req.route());
		}
		if (!cfg.ai().enabled()) {
			return AiResult.local(AiStatus.DISABLED, null);
		}
		if (!secrets.hasKey()) {
			return AiResult.local(AiStatus.NO_KEY, null);
		}
		if (paused) {
			return AiResult.local(AiStatus.PAUSED, "пауза админа");
		}
		if (blockedUntilMillis > clock.millis()) {
			return AiResult.local(AiStatus.PAUSED, blockReason);
		}
		if (!budget.canSpend()) {
			return AiResult.local(AiStatus.BUDGET, null);
		}
		if (SHED_ON_HARD.contains(req.route()) && overloaded.getAsBoolean()) {
			return AiResult.local(AiStatus.OVERLOAD, "MSPT выше mspt_hard");
		}
		if (!limiter.tryAcquire()) {
			return AiResult.local(AiStatus.RATE_LIMIT, null);
		}
		return null;
	}

	/** Одна попытка к одной модели — уже записанная в ai_log и бюджет. */
	private record Step(String status, ResponseParser.Parsed parsed, OpenRouterClient.Attempt attempt, String model, String error) {
		boolean ok() {
			return "ok".equals(status);
		}
	}

	private AiResult run(AiRequest req, AiRoute route, long deadline, CompletableFuture<AiResult> future) {
		long t0 = System.nanoTime();
		JsonObject schema = prompts.schema(req.schema());
		List<ModelSpec> order = new ArrayList<>(route.models());
		if (order.size() == 1) {
			order.add(order.getFirst()); // одна модель — один повтор
		}
		AtomicBoolean won = new AtomicBoolean();
		List<Step> steps = new ArrayList<>();
		int next = 0;
		boolean timedOut = false;
		if (route.hedgeMillis() > 0 && route.models().size() >= 2) {
			next = race(req, route, order, schema, deadline, future, won, steps);
			Step last = steps.isEmpty() ? null : steps.getLast();
			if (last != null && last.ok()) {
				return result(AiStatus.OK, last.parsed().value(), last.model(), steps, t0, null);
			}
			if (last != null && last.attempt().keyProblem()) {
				return result(AiStatus.PAUSED, null, last.model(), steps, t0, blockReason);
			}
			timedOut = last == null || last.attempt().timedOut() || remaining(deadline) < MIN_ATTEMPT_MS;
			if (next == 1 && last != null && last.attempt().retryable() && !last.attempt().timedOut()) {
				sleepQuietly(RETRY_PAUSE_MS);
			}
		}
		for (int i = next; i < order.size(); i++) {
			long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
			if (future.isDone() || remainingMs < MIN_ATTEMPT_MS) {
				timedOut = true;
				break;
			}
			if (!budget.canSpend()) {
				return result(AiStatus.BUDGET, null, lastModel(steps), steps, t0, "бюджет кончился во время запроса");
			}
			Step s = attempt(req, route, order.get(i), schema, deadline, future, won);
			steps.add(s);
			if (s.ok()) {
				return result(AiStatus.OK, s.parsed().value(), s.model(), steps, t0, null);
			}
			if ("late".equals(s.status())) {
				return result(AiStatus.TIMEOUT, null, s.model(), steps, t0, "ответ после дедлайна");
			}
			if (s.attempt().keyProblem()) {
				return result(AiStatus.PAUSED, null, s.model(), steps, t0, blockReason);
			}
			timedOut = s.attempt().timedOut();
			if (s.attempt().retryable() && !s.attempt().timedOut()) {
				sleepQuietly(RETRY_PAUSE_MS);
			}
		}
		String lastError = steps.isEmpty() ? null : steps.getLast().model() + ": " + steps.getLast().error();
		if (timedOut) {
			return result(AiStatus.TIMEOUT, null, lastModel(steps), steps, t0, lastError);
		}
		boolean allRefused = !steps.isEmpty() && steps.stream().allMatch(s -> "refused".equals(s.status()));
		return result(allRefused ? AiStatus.REFUSED : AiStatus.FAILED, null, lastModel(steps), steps, t0, lastError);
	}

	/**
	 * Гонка первых двух моделей маршрута. Первая молчит hedgeMillis — вторая стартует параллельно,
	 * берётся первый годный ответ; ответ проигравшей пишется в ai_log как lost. Первая успела
	 * до старта гонки, но без годного ответа — возвращает 1: вторая пойдёт обычной запасной.
	 * Шаги кладёт в steps, последний — итог гонки. Возвращает индекс следующей модели.
	 */
	private int race(AiRequest req, AiRoute route, List<ModelSpec> order, JsonObject schema, long deadline,
			CompletableFuture<AiResult> future, AtomicBoolean won, List<Step> steps) {
		CompletableFuture<Step> first = CompletableFuture.supplyAsync(
				() -> attempt(req, route, order.get(0), schema, deadline, future, won), racers);
		Step s = await(first, Math.min(route.hedgeMillis(), remaining(deadline)));
		if (s != null) {
			steps.add(s);
			return 1;
		}
		if (remaining(deadline) < MIN_ATTEMPT_MS || !budget.canSpend()) {
			Step only = await(first, remaining(deadline));
			if (only != null) {
				steps.add(only);
			}
			return order.size();
		}
		CompletableFuture<Step> second = CompletableFuture.supplyAsync(
				() -> attempt(req, route, order.get(1), schema, deadline, future, won), racers);
		Step a = await(CompletableFuture.anyOf(first, second).thenApply(Step.class::cast), remaining(deadline));
		if (a == null) {
			return order.size(); // обе молчат до дедлайна
		}
		if (a.ok()) {
			steps.add(a);
			return order.size();
		}
		CompletableFuture<Step> other = first.isDone() && first.join() == a ? second : first;
		Step b = await(other, remaining(deadline));
		steps.add(a);
		if (b != null) {
			steps.add(b);
		}
		return order.size();
	}

	private static long remaining(long deadline) {
		return Math.max(0, (deadline - System.nanoTime()) / 1_000_000);
	}

	/** Результат future за ms миллисекунд или null. */
	private static <T> T await(CompletableFuture<T> f, long ms) {
		try {
			return f.get(ms, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException e) {
			return null;
		}
	}

	private Step attempt(AiRequest req, AiRoute route, ModelSpec model, JsonObject schema, long deadline,
			CompletableFuture<AiResult> future, AtomicBoolean won) {
		JsonObject body = OpenRouterClient.body(model, route, req.system(), req.user(), req.schema(), schema);
		OpenRouterClient.Attempt a = client.send(body, Duration.ofMillis(Math.max(MIN_ATTEMPT_MS, remaining(deadline))));
		budget.add(a.usage().cost());
		stats.attempt(req.route(), a.usage());
		String used = a.model() != null ? a.model() : model.id();

		String status;
		ResponseParser.Parsed parsed = null;
		if (a.keyProblem()) {
			status = "error";
			blockKey(a);
		} else if (a.refusal() != null || "content_filter".equals(a.finishReason())) {
			status = "refused";
		} else if (a.answered()) {
			parsed = ResponseParser.parse(a.content(), schema);
			status = parsed.ok() ? "ok" : "invalid";
		} else {
			status = a.timedOut() ? "timeout" : "error";
		}
		boolean done = future.isDone();
		if ("ok".equals(status)) {
			// Годный ответ после чужой победы в гонке — lost; после дедлайна — late
			if (!won.compareAndSet(false, true)) {
				status = "lost";
			} else if (done) {
				status = "late";
			}
		} else if (won.get()) {
			status = "lost";
		} else if (done) {
			status = "late";
		}
		String error = a.error() != null ? a.error()
				: parsed != null && !parsed.ok() ? String.join("; ", parsed.errors())
				: "refused".equals(status) ? "finish_reason " + a.finishReason() : null;
		writeLog(req, route, used, status, a, error);
		return new Step(status, parsed, a, used, error);
	}

	private static String lastModel(List<Step> steps) {
		return steps.isEmpty() ? null : steps.getLast().model();
	}

	private void blockKey(OpenRouterClient.Attempt a) {
		if (a.http() == 402) {
			blockedUntilMillis = clock.millis() + TimeUnit.MINUTES.toMillis(CREDITS_BLOCK_MINUTES);
			blockReason = "на OpenRouter кончились кредиты; повтор через " + CREDITS_BLOCK_MINUTES + " мин";
		} else {
			blockedUntilMillis = Long.MAX_VALUE;
			blockReason = "OpenRouter не принял ключ (HTTP " + a.http() + "); после замены — /rickadmin reload";
		}
		log.warn("[ИИ] {}", blockReason);
		alert.accept(blockReason);
	}

	private void writeLog(AiRequest req, AiRoute route, String model, String status, OpenRouterClient.Attempt a, String error) {
		Instant now = clock.instant();
		String out = a.content();
		if (out != null && out.length() > OUTPUT_LOG_LIMIT) {
			out = out.substring(0, OUTPUT_LOG_LIMIT);
		}
		OpenRouterClient.Usage u = a.usage();
		try {
			sink.write(new AiLogEntry(now, budget.day(), route.name(), req.tag(), model, status,
					u.promptTokens(), u.cachedTokens(), u.completionTokens(), u.reasoningTokens(),
					a.latencyMs(), u.cost(), req.player(), out, error));
		} catch (RuntimeException e) {
			log.warn("[ИИ] ai_log не записан: {}", e.toString());
		}
		if (!"ok".equals(status) && !"lost".equals(status)) {
			log.info("[ИИ] {} {} {}: {}", route.name(), model, status, error);
		}
	}

	private static AiResult result(AiStatus s, JsonObject value, String model, List<Step> steps, long t0, String error) {
		double cost = steps.stream().mapToDouble(x -> x.attempt().usage().cost()).sum();
		return new AiResult(s, value, model, cost, (System.nanoTime() - t0) / 1_000_000, steps.size(), error);
	}

	private static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ---------- пакеты (Batch API) ----------

	/** Почему пакет сейчас отправлять нельзя, или null. Лимит запросов в минуту к пакетам не относится. */
	public String refuseBatch() {
		RikoshetConfig cfg = config;
		if (!cfg.ai().enabled()) {
			return "ИИ выключен в конфиге";
		}
		if (!secrets.hasKey()) {
			return "нет ключа";
		}
		if (paused) {
			return "пауза админа";
		}
		if (blockedUntilMillis > clock.millis()) {
			return blockReason;
		}
		if (!budget.canSpend()) {
			return "дневной бюджет исчерпан";
		}
		return null;
	}

	/** Клиент пакетов с тем же адресом и ключом. */
	public BatchClient batchClient() {
		return new BatchClient(config.ai().baseUrl(), this::key);
	}

	/** Схема ответа по имени — для разбора результатов пакета. */
	public JsonObject schema(String name) {
		return prompts.schema(name);
	}

	/** Один результат пакета: в ai_log, в бюджет и в статистику маршрута. */
	public void recordBatchResult(String route, String tag, String model, String status, OpenRouterClient.Usage usage,
			String output, String error) {
		budget.add(usage.cost());
		stats.attempt(route, usage);
		String out = output != null && output.length() > OUTPUT_LOG_LIMIT ? output.substring(0, OUTPUT_LOG_LIMIT) : output;
		try {
			sink.write(new AiLogEntry(clock.instant(), budget.day(), route, tag, model, status, usage.promptTokens(),
					usage.cachedTokens(), usage.completionTokens(), usage.reasoningTokens(), 0, usage.cost(), null, out, error));
		} catch (RuntimeException e) {
			log.warn("[ИИ] ai_log не записан: {}", e.toString());
		}
	}

	public void setPaused(boolean paused) {
		this.paused = paused;
		if (!paused) {
			blockedUntilMillis = 0;
			blockReason = null;
		}
	}

	public boolean paused() {
		return paused;
	}

	/** Почему ИИ сейчас заблокирован (ключ, кредиты) или null. */
	public String blockReason() {
		return blockedUntilMillis > clock.millis() ? blockReason : null;
	}

	public Budget budget() {
		return budget;
	}

	public RateLimiter limiter() {
		return limiter;
	}

	public AiStats stats() {
		return stats;
	}

	public Secrets secrets() {
		return secrets;
	}

	public RikoshetConfig config() {
		return config;
	}

	public int active() {
		return pool.getActiveCount();
	}

	public int queued() {
		return pool.getQueue().size();
	}

	public void shutdown() {
		pool.shutdownNow();
		racers.shutdownNow();
		try {
			pool.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static ThreadFactory daemonThreads(String prefix) {
		AtomicInteger n = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, prefix + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}
}
