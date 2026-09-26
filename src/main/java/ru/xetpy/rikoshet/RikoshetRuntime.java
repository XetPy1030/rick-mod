package ru.xetpy.rikoshet;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.ai.AiService;
import ru.xetpy.rikoshet.ai.PromptLibrary;
import ru.xetpy.rikoshet.chronicle.ChronicleService;
import ru.xetpy.rikoshet.chronicle.ChronicleStore;
import ru.xetpy.rikoshet.chronicle.analysis.DayReport;
import ru.xetpy.rikoshet.memory.MemoryService;
import ru.xetpy.rikoshet.memory.MemoryStore;
import ru.xetpy.rikoshet.newspaper.NewspaperService;
import ru.xetpy.rikoshet.newspaper.NewspaperStore;
import ru.xetpy.rikoshet.core.ConfigLoader;
import ru.xetpy.rikoshet.core.LoadMonitor;
import ru.xetpy.rikoshet.core.ModPaths;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.core.Secrets;
import ru.xetpy.rikoshet.flavor.FallbackLines;
import ru.xetpy.rikoshet.flavor.FlavorService;
import ru.xetpy.rikoshet.integration.AuthTracker;
import ru.xetpy.rikoshet.integration.EasyAuthBridge;
import ru.xetpy.rikoshet.persona.Speaker;
import ru.xetpy.rikoshet.storage.AiLogStore;
import ru.xetpy.rikoshet.storage.DailyStats;
import ru.xetpy.rikoshet.storage.Database;
import ru.xetpy.rikoshet.storage.NoteStore;
import ru.xetpy.rikoshet.storage.PlayerStore;
import ru.xetpy.rikoshet.storage.ReportStore;
import ru.xetpy.rikoshet.storage.Retention;
import ru.xetpy.rikoshet.storage.RoleStore;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Всё, что живёт от старта до остановки сервера. Создаётся на SERVER_STARTING,
 * закрывается на SERVER_STOPPED.
 */
public final class RikoshetRuntime {
	public final Logger log;
	public final Clock clock = Clock.systemUTC();
	public final MinecraftServer server;
	public final ModPaths paths;
	public final Database db;
	public final PlayerStore players;
	public final RoleStore roles;
	public final DailyStats stats;
	public final NoteStore notes;
	public final ReportStore reports;
	public final PromptLibrary prompts;
	public final AiService ai;
	public final LoadMonitor load;
	public final Speaker speaker;
	public final AuthTracker auth;
	public final FlavorService flavor;
	public final ChronicleService chronicle;
	public final MemoryService memory;
	public final NewspaperService newspaper;
	/** Проблемы конфига при старте: пока они есть, действуют значения по умолчанию. */
	public final List<String> startupProblems = new ArrayList<>();

	private volatile RikoshetConfig config;

	private RikoshetRuntime(MinecraftServer server, Logger log) throws IOException, SQLException {
		this.server = server;
		this.log = log;
		this.paths = ModPaths.of(server.getServerDirectory(), FabricLoader.getInstance().getConfigDir());

		ConfigLoader.Result cfg = ConfigLoader.load(paths.configFile());
		cfg.warnings().forEach(w -> log.warn("[конфиг] {}", w));
		if (cfg.ok()) {
			config = cfg.config();
		} else {
			cfg.errors().forEach(e -> log.error("[конфиг] {}", e));
			startupProblems.addAll(cfg.errors());
			config = RikoshetConfig.defaults();
			log.error("[конфиг] ошибки в {} — действуют значения по умолчанию", paths.configFile());
		}
		List<String> secretWarnings = new ArrayList<>();
		Secrets secrets = Secrets.load(paths.dataDir(), secretWarnings);
		secretWarnings.forEach(w -> log.warn("[секреты] {}", w));
		log.info("OpenRouter: {}", secrets);

		db = Database.open(paths.resolveFromServer(config.storage().path()), log);
		Retention.purge(db, log, clock.millis(), config.storage().dialogueRetentionDays(), config.storage().aiLogRetentionDays());
		players = new PlayerStore(db);
		players.load();
		roles = new RoleStore(db);
		roles.load();
		stats = new DailyStats(db, this::today);
		stats.load();
		notes = new NoteStore(db);
		notes.load();
		reports = new ReportStore(db);
		reports.load();
		AiLogStore aiLog = new AiLogStore(db);
		ChronicleStore chronicleStore = new ChronicleStore(db);
		chronicle = new ChronicleService(log, clock, this::config, this::today, chronicleStore, stats, players, roles);
		memory = new MemoryService(log, clock, () -> config.timezone(), new MemoryStore(db), chronicleStore, chronicle::whoPublic);
		chronicle.onEvent(memory::onEvent);
		// Итоги дня приходят из потока БД — дальше работаем в главном
		chronicle.onReport((day, report) -> server.execute(() -> nightly(day, report)));

		prompts = new PromptLibrary(paths.dataDir().resolve("prompts"));
		load = new LoadMonitor(config.performance());
		ai = new AiService(log, clock, config, secrets, prompts, aiLog, load::hard,
				msg -> server.execute(() -> alertAdmins(msg)));
		ai.budget().restore(today(), aiLog.spent(today()));
		speaker = new Speaker(log);
		EasyAuthBridge easyAuth = EasyAuthBridge.create(log);
		auth = new AuthTracker(easyAuth, this::onAuthenticated);
		flavor = new FlavorService(log, clock, this::config, ai, prompts, players, roles, stats, notes, speaker, auth, memory, chronicle,
				FallbackLines.load("rick", paths.dataDir().resolve("fallback"), new Random()));
		newspaper = new NewspaperService(log, clock, this::config, this::today, ai, prompts, chronicle, memory, new NewspaperStore(db),
				players, flavor::rosterBlock, flavor::canSee, paths.dataDir());
		log.info("Рикошет запущен: игроков {}, ролей {}, потрачено сегодня ${}",
				players.names().size(), roles.all().size(), String.format("%.4f", ai.budget().spentToday()));
	}

	public static RikoshetRuntime start(MinecraftServer server, Logger log) throws IOException, SQLException {
		return new RikoshetRuntime(server, log);
	}

	/** Дни, за которые дневник уже написан с этого запуска. */
	private final java.util.Set<LocalDate> diaryDone = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Итоги дня готовы: консолидация памяти и дневник дня. Газета читает их сама в час выхода. Главный поток. */
	private void nightly(LocalDate day, DayReport report) {
		try {
			memory.consolidate(day, report, today());
		} catch (RuntimeException e) {
			log.warn("[память] консолидация {}: {}", day, e.toString());
		}
		if (report.players().isEmpty() || day.isBefore(today().minusDays(1)) || !diaryDone.add(day)) {
			return;
		}
		String system = ru.xetpy.rikoshet.ai.PromptBuilder.system(prompts, flavor.rosterBlock(), "editor", "diary");
		String user = ru.xetpy.rikoshet.ai.PromptBuilder.user(null, ru.xetpy.rikoshet.memory.Diary.input(report), null, null);
		ai.submit(new ru.xetpy.rikoshet.ai.AiRequest("analyst", "diary", system, user, "diary", null))
				.thenAccept(res -> server.execute(() -> {
					if (!res.ok()) {
						diaryDone.remove(day);
						return;
					}
					long now = clock.millis();
					var entries = ru.xetpy.rikoshet.memory.Diary.parse(res.value(), report, config.content().blocklist());
					entries.forEach((uuid, text) -> {
						com.google.gson.JsonObject d = new com.google.gson.JsonObject();
						d.addProperty("text", text);
						chronicle.record(new ru.xetpy.rikoshet.chronicle.ChronicleEvent(now, day.toString(), uuid, "diary", null, 10, d));
					});
					log.info("[память] дневник {}: {} записей, ${}", day, entries.size(), String.format("%.4f", res.costUsd()));
				}));
	}

	/** Игрок вошёл и ввёл пароль EasyAuth (или EasyAuth нет). */
	private void onAuthenticated(ServerPlayer p) {
		chronicle.track(p);
		flavor.onJoin(p);
		newspaper.onJoin(p);
		int open = reports.open();
		if (open > 0 && isAdmin(p)) {
			p.sendSystemMessage(Component.literal("[Рикошет] ").withStyle(ChatFormatting.GOLD)
					.append(Component.literal("Жалоб на реплики: " + open + " — /rickadmin report list").withStyle(ChatFormatting.YELLOW)));
		}
	}

	/** Сервер запущен: расписания. */
	public void started() {
		newspaper.start(server);
	}

	public RikoshetConfig config() {
		return config;
	}

	public LocalDate today() {
		return LocalDate.now(clock.withZone(config.timezone()));
	}

	/** Итог /rickadmin reload. */
	public record Reload(boolean applied, List<String> warnings, List<String> errors) {
	}

	/**
	 * Перечитать конфиг, ключ, промпты и заготовки. Конфиг с ошибками не применяется — остаётся прежний.
	 * Путь к БД меняется только перезапуском.
	 */
	public Reload reload() {
		ConfigLoader.Result r = ConfigLoader.load(paths.configFile());
		List<String> warnings = new ArrayList<>(r.warnings());
		if (!r.ok()) {
			return new Reload(false, warnings, r.errors());
		}
		RikoshetConfig next = r.config();
		if (!next.storage().path().equals(config.storage().path())) {
			warnings.add("storage.path меняется только перезапуском сервера");
		}
		Secrets secrets = Secrets.load(paths.dataDir(), warnings);
		config = next;
		startupProblems.clear();
		prompts.clear();
		newspaper.reloadExtras();
		try {
			flavor.setFallback(FallbackLines.load("rick", paths.dataDir().resolve("fallback"), new Random()));
		} catch (RuntimeException e) {
			warnings.add("заготовки не перечитаны: " + e.getMessage());
		}
		ai.reconfigure(next, secrets);
		load.configure(next.performance());
		chronicle.reconfigure(server, auth::isAuthenticated);
		return new Reload(true, warnings, List.of());
	}

	/** Раз в секунду из главного потока. */
	public void everySecond() {
		LoadMonitor.Level changed = load.sample(server.getAverageTickTimeNanos() / 1_000_000.0, clock.millis());
		if (changed == LoadMonitor.Level.HARD) {
			String msg = String.format("MSPT %.1f выше mspt_hard: флейвор на заготовках", load.mspt());
			log.warn("[нагрузка] {}", msg);
			alertAdmins(msg);
		} else if (changed != null) {
			log.info("[нагрузка] уровень {} (MSPT {})", changed, String.format("%.1f", load.mspt()));
		}
		auth.poll(server);
		chronicle.everySecond(server);
	}

	/** Сообщение всем админам онлайн. Только из главного потока. */
	public void alertAdmins(String text) {
		Component msg = Component.literal("[Рикошет] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(text).withStyle(ChatFormatting.YELLOW));
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (isAdmin(p)) {
				p.sendSystemMessage(msg);
			}
		}
	}

	public static boolean isAdmin(ServerPlayer p) {
		return p.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
	}

	public void stopping() {
		newspaper.stopping();
		chronicle.stopping(server);
		flavor.stopping();
		ai.shutdown();
		// Время сессий засчитываем сейчас: при остановке прощаний не будет
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			flavor.onLeave(p, auth.onLeave(p), null);
		}
	}

	public void close() {
		db.close();
	}
}
