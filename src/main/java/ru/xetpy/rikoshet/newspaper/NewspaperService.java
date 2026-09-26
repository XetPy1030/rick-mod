package ru.xetpy.rikoshet.newspaper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.ai.AiRequest;
import ru.xetpy.rikoshet.ai.AiResult;
import ru.xetpy.rikoshet.ai.AiService;
import ru.xetpy.rikoshet.ai.PromptBuilder;
import ru.xetpy.rikoshet.ai.PromptLibrary;
import ru.xetpy.rikoshet.chronicle.ChronicleService;
import ru.xetpy.rikoshet.chronicle.analysis.DayReport;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.memory.MemoryService;
import ru.xetpy.rikoshet.storage.PlayerRecord;
import ru.xetpy.rikoshet.storage.PlayerStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * «Межпространственный вестник» (docs/design/flavor.md#ежедневная-газета). В newspaper.hour —
 * выпуск за вчера: итоги дня из летописи → план номера от аналитика (дешёвая модель) →
 * текст от редакции (Opus). Не вышло — выпуск из фактов без ИИ. Никто вчера не играл — выпуска нет.
 */
public final class NewspaperService {
	/** Заголовок показывается при входе, пока выпуску меньше стольких часов. */
	static final long FRESH_HOURS = 20;

	private final Logger log;
	private final Clock clock;
	private final Supplier<RikoshetConfig> config;
	private final Supplier<LocalDate> today;
	private final AiService ai;
	private final PromptLibrary prompts;
	private final ChronicleService chronicle;
	private final MemoryService memory;
	private final NewspaperStore store;
	private final PlayerStore players;
	private final Supplier<String> roster;
	private final Predicate<ServerPlayer> canSee;
	private final Path dataDir;
	private final Random rnd = new Random();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "rikoshet-news");
		t.setDaemon(true);
		return t;
	});
	private final Set<UUID> notified = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile Map<String, List<String>> extras;
	private volatile Issue current;
	private volatile MinecraftServer server;
	private volatile boolean stopping;

	public NewspaperService(Logger log, Clock clock, Supplier<RikoshetConfig> config, Supplier<LocalDate> today, AiService ai,
			PromptLibrary prompts, ChronicleService chronicle, MemoryService memory, NewspaperStore store, PlayerStore players,
			Supplier<String> roster, Predicate<ServerPlayer> canSee, Path dataDir) throws SQLException {
		this.log = log;
		this.clock = clock;
		this.config = config;
		this.today = today;
		this.ai = ai;
		this.prompts = prompts;
		this.chronicle = chronicle;
		this.memory = memory;
		this.store = store;
		this.players = players;
		this.roster = roster;
		this.canSee = canSee;
		this.dataDir = dataDir;
		this.current = store.latest();
		reloadExtras();
	}

	public boolean enabled() {
		RikoshetConfig c = config.get();
		return c.feature("newspaper") && c.feature("chronicle");
	}

	/** Расписание: выпуск в newspaper.hour и догоняющий выпуск через минуту после старта. */
	public void start(MinecraftServer server) {
		this.server = server;
		scheduleNext();
		timer.schedule(() -> server.execute(this::due), 60, TimeUnit.SECONDS);
	}

	public void stopping() {
		stopping = true;
		timer.shutdownNow();
	}

	public void reloadExtras() {
		Map<String, List<String>> m = new HashMap<>();
		try (InputStream in = NewspaperService.class.getResourceAsStream("/rikoshet/fallback/newspaper.json")) {
			if (in != null) {
				read(m, new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			Path over = dataDir.resolve("fallback").resolve("newspaper.json");
			if (Files.exists(over)) {
				read(m, Files.readString(over));
			}
		} catch (IOException | RuntimeException e) {
			log.warn("[газета] заготовки не прочитаны: {}", e.toString());
		}
		extras = m;
	}

	private static void read(Map<String, List<String>> m, String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		for (String k : List.of("ads", "weather", "prophecy")) {
			if (o.has(k) && o.get(k).isJsonArray()) {
				List<String> l = new ArrayList<>();
				o.getAsJsonArray(k).forEach(e -> l.add(e.getAsString()));
				m.put(k, l);
			}
		}
	}

	private void scheduleNext() {
		if (stopping) {
			return;
		}
		RikoshetConfig c = config.get();
		ZonedDateTime now = ZonedDateTime.now(clock.withZone(c.timezone()));
		ZonedDateTime next = now.toLocalDate().atTime(c.newspaper().hour(), 0).atZone(c.timezone());
		if (!next.isAfter(now)) {
			next = next.plusDays(1);
		}
		long delay = Duration.between(now, next).toMillis();
		MinecraftServer s = server;
		timer.schedule(() -> s.execute(() -> {
			scheduleNext();
			due();
		}), delay, TimeUnit.MILLISECONDS);
	}

	/** Пора ли выпускать: после часа выхода, за вчера, если выпуска ещё нет. */
	private void due() {
		if (stopping || !enabled()) {
			return;
		}
		ZonedDateTime now = ZonedDateTime.now(clock.withZone(config.get().timezone()));
		if (now.getHour() < config.get().newspaper().hour()) {
			return;
		}
		LocalDate yesterday = today.get().minusDays(1);
		if (current != null && current.day().equals(yesterday.toString())) {
			return;
		}
		publish(yesterday).exceptionally(e -> {
			log.warn("[газета] выпуск за {} не вышел: {}", yesterday, e.toString());
			return null;
		});
	}

	/**
	 * Сделать и опубликовать выпуск за день. Возвращает выпуск или null, если выпускать не о чем.
	 * Два выпуска сразу не делаем.
	 */
	public CompletableFuture<Issue> publish(LocalDate day) {
		if (!running.compareAndSet(false, true)) {
			return CompletableFuture.failedFuture(new IllegalStateException("выпуск уже готовится"));
		}
		return generate(day).thenApplyAsync(issue -> {
			if (issue != null) {
				Issue done = issue.withPublished(clock.millis());
				store.save(done);
				current = done;
				notified.clear();
				broadcast(done);
				log.info("[газета] вышел выпуск за {} ({}{}, ${}): {}", day, done.source(),
						done.model() == null ? "" : " " + done.model(), String.format("%.4f", done.costUsd()), done.headline());
			} else {
				log.info("[газета] за {} выпуска нет: никто не играл", day);
			}
			return issue;
		}, main()).whenComplete((i, e) -> running.set(false));
	}

	/** Собрать выпуск, не публикуя. null — за день нечего писать. */
	public CompletableFuture<Issue> generate(LocalDate day) {
		return chronicle.report(day)
				.thenCompose(r -> r != null ? CompletableFuture.completedFuture(r)
						: chronicle.hasData(day).thenCompose(has -> has ? onMain(() -> chronicle.analyze(day)) : CompletableFuture.completedFuture(null)))
				.thenCompose(r -> {
					if (r == null || r.server() == null || r.server().players() == 0) {
						return CompletableFuture.completedFuture(null);
					}
					return store.headlinesBefore(day.toString(), 5).thenCompose(h -> onMain(() -> write(day, r, h)));
				});
	}

	/** Главный поток: промпты (ростер, ники с /rick off), дальше — сеть. */
	private CompletableFuture<Issue> write(LocalDate day, DayReport r, List<String> headlines) {
		RikoshetConfig cfg = config.get();
		Editorial.Extras x = new Editorial.Extras(memory.activeStories(), memory.changesFor(day), headlines);
		Set<String> hidden = new HashSet<>();
		for (String n : players.names()) {
			players.byName(n).filter(PlayerRecord::aiOptOut).ifPresent(p -> hidden.add(p.name()));
		}
		String rosterBlock = roster.get();
		String briefSystem = PromptBuilder.system(prompts, rosterBlock, "editor", "brief");
		String briefUser = PromptBuilder.user(null, Editorial.briefInput(r, x), null, null);
		return ai.submit(new AiRequest("analyst", "brief", briefSystem, briefUser, "brief", null))
				.thenCompose(b -> onMain(() -> {
					Editorial.Brief brief = b.ok() ? Editorial.brief(b.value(), r.facts()) : null;
					if (b.ok() && brief == null) {
						log.info("[газета] план номера от аналитика непригоден — обычная сводка");
					}
					String pkg = brief != null ? Editorial.packageFrom(brief, r, x) : Editorial.packagePlain(r, x, cfg.newspaper().maxFacts());
					String system = PromptBuilder.system(prompts, rosterBlock, "rick", "newspaper");
					String user = PromptBuilder.user(null, pkg, null, null);
					return ai.submit(new AiRequest("newspaper", "newspaper", system, user, "newspaper", null))
							.thenApply(res -> finish(day, r, res, b, brief != null, cfg, hidden));
				}));
	}

	private Issue finish(LocalDate day, DayReport r, AiResult res, AiResult brief, boolean usedBrief, RikoshetConfig cfg, Set<String> hidden) {
		double cost = res.costUsd() + brief.costUsd();
		if (res.ok()) {
			Issue i = Editorial.check(res.value(), day.toString(), cfg.content().blocklist(), hidden,
					res.model() + (usedBrief ? " + план " + brief.model() : ""), cost);
			if (i != null) {
				return i;
			}
			log.info("[газета] ответ редакции не прошёл проверку — выпуск из фактов");
		} else {
			log.info("[газета] редакция не ответила ({}) — выпуск из фактов", res.status());
		}
		Issue f = Editorial.fallback(r, extras, rnd);
		return new Issue(f.day(), f.headline(), f.articles(), f.ad(), f.weather(), f.forecast(), f.source(), null, cost, 0);
	}

	// ---------- показ ----------

	public Issue current() {
		return current;
	}

	/** Вход игрока: свежий выпуск, которого он ещё не видел, — заголовком. */
	public void onJoin(ServerPlayer p) {
		Issue i = current;
		if (!enabled() || i == null || !canSee.test(p) || clock.millis() - i.publishedAt() > TimeUnit.HOURS.toMillis(FRESH_HOURS)) {
			return;
		}
		if (notified.add(p.getUUID())) {
			p.sendSystemMessage(IssueView.headline(i));
		}
	}

	private void broadcast(Issue i) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (canSee.test(p)) {
				p.sendSystemMessage(IssueView.headline(i));
				notified.add(p.getUUID());
			}
		}
	}

	private Executor main() {
		MinecraftServer s = server;
		return s != null ? s : Runnable::run;
	}

	private <T> CompletableFuture<T> onMain(Supplier<CompletableFuture<T>> work) {
		return CompletableFuture.supplyAsync(work, main()).thenCompose(f -> f);
	}

	static String str(JsonElement e) {
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}
}
