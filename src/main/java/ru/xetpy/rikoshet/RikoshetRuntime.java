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

		prompts = new PromptLibrary(paths.dataDir().resolve("prompts"));
		load = new LoadMonitor(config.performance());
		ai = new AiService(log, clock, config, secrets, prompts, aiLog, load::hard,
				msg -> server.execute(() -> alertAdmins(msg)));
		ai.budget().restore(today(), aiLog.spent(today()));
		speaker = new Speaker(log);
		EasyAuthBridge easyAuth = EasyAuthBridge.create(log);
		auth = new AuthTracker(easyAuth, this::onAuthenticated);
		flavor = new FlavorService(log, clock, this::config, ai, prompts, players, roles, stats, notes, speaker, auth,
				FallbackLines.load("rick", paths.dataDir().resolve("fallback"), new Random()));
		log.info("Рикошет запущен: игроков {}, ролей {}, потрачено сегодня ${}",
				players.names().size(), roles.all().size(), String.format("%.4f", ai.budget().spentToday()));
	}

	public static RikoshetRuntime start(MinecraftServer server, Logger log) throws IOException, SQLException {
		return new RikoshetRuntime(server, log);
	}

	/** Игрок вошёл и ввёл пароль EasyAuth (или EasyAuth нет). */
	private void onAuthenticated(ServerPlayer p) {
		flavor.onJoin(p);
		int open = reports.open();
		if (open > 0 && isAdmin(p)) {
			p.sendSystemMessage(Component.literal("[Рикошет] ").withStyle(ChatFormatting.GOLD)
					.append(Component.literal("Жалоб на реплики: " + open + " — /rickadmin report list").withStyle(ChatFormatting.YELLOW)));
		}
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
		try {
			flavor.setFallback(FallbackLines.load("rick", paths.dataDir().resolve("fallback"), new Random()));
		} catch (RuntimeException e) {
			warnings.add("заготовки не перечитаны: " + e.getMessage());
		}
		ai.reconfigure(next, secrets);
		load.configure(next.performance());
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
		flavor.stopping();
		ai.shutdown();
		// Время сессий засчитываем сейчас: при остановке прощаний не будет
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			flavor.onLeave(p, auth.onLeave(p));
		}
	}

	public void close() {
		db.close();
	}
}
