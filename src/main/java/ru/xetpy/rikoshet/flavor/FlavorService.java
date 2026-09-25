package ru.xetpy.rikoshet.flavor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.ai.AiRequest;
import ru.xetpy.rikoshet.ai.AiResult;
import ru.xetpy.rikoshet.ai.AiService;
import ru.xetpy.rikoshet.ai.PromptBuilder;
import ru.xetpy.rikoshet.ai.PromptLibrary;
import ru.xetpy.rikoshet.ai.TextFilter;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.integration.AuthTracker;
import ru.xetpy.rikoshet.persona.Persona;
import ru.xetpy.rikoshet.persona.Role;
import ru.xetpy.rikoshet.persona.Roster;
import ru.xetpy.rikoshet.persona.Speaker;
import ru.xetpy.rikoshet.storage.DailyStats;
import ru.xetpy.rikoshet.storage.NoteStore;
import ru.xetpy.rikoshet.storage.PlayerRecord;
import ru.xetpy.rikoshet.storage.PlayerStore;
import ru.xetpy.rikoshet.storage.RoleStore;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Комментарии Рика к смерти, входу и выходу (docs/design/flavor.md). Контекст собирается
 * в главном потоке, запрос уходит в {@link AiService}, ответ возвращается в главный поток
 * через server.execute. Живой ответ не пришёл — выводится заготовка.
 */
public final class FlavorService {
	private static final Persona PERSONA = Persona.RICK;
	private static final double NEARBY = 32;
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

	private final Logger log;
	private final Clock clock;
	private final Supplier<RikoshetConfig> config;
	private final AiService ai;
	private final PromptLibrary prompts;
	private final PlayerStore players;
	private final RoleStore roles;
	private final DailyStats stats;
	private final NoteStore notes;
	private final Speaker speaker;
	private final AuthTracker auth;
	private final SessionTracker sessions = new SessionTracker();
	private final Map<UUID, PendingLeave> pendingLeaves = new ConcurrentHashMap<>();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "rikoshet-timer");
		t.setDaemon(true);
		return t;
	});
	private volatile FallbackLines fallback;
	private volatile boolean stopping;

	private record PendingLeave(UUID uuid, String nick, String core, int sessionDeaths, long sessionSeconds) {
	}

	public FlavorService(Logger log, Clock clock, Supplier<RikoshetConfig> config, AiService ai, PromptLibrary prompts,
			PlayerStore players, RoleStore roles, DailyStats stats, NoteStore notes, Speaker speaker, AuthTracker auth,
			FallbackLines fallback) {
		this.log = log;
		this.clock = clock;
		this.config = config;
		this.ai = ai;
		this.prompts = prompts;
		this.players = players;
		this.roles = roles;
		this.stats = stats;
		this.notes = notes;
		this.speaker = speaker;
		this.auth = auth;
		this.fallback = fallback;
	}

	public void setFallback(FallbackLines fallback) {
		this.fallback = fallback;
	}

	public void stopping() {
		stopping = true;
		pendingLeaves.clear();
		timer.shutdownNow();
	}

	/** Кто видит реплики персонажей. */
	public boolean canSee(ServerPlayer p) {
		return auth.isAuthenticated(p) && !players.optedOut(p.getUUID());
	}

	// ---------- смерть ----------

	public void onDeath(ServerPlayer player, DamageSource source) {
		long now = clock.millis();
		RikoshetConfig cfg = config.get();
		UUID uuid = player.getUUID();
		int today = stats.increment(uuid, DailyStats.DEATHS);
		int series = sessions.death(uuid, now, Duration.ofMinutes(cfg.flavor().seriesWindowMinutes()).toMillis());
		String typeId = source.typeHolder().getRegisteredName();
		DeathCauses.Cause cause = DeathCauses.of(typeId);
		stats.increment(uuid, "death." + cause.group());

		if (!cfg.feature("rick") || !cfg.feature("death_messages") || stopping) {
			return;
		}
		if (!auth.isAuthenticated(player) || players.optedOut(uuid)) {
			return;
		}
		if (!sessions.tryComment(uuid, now, cfg.flavor().deathCooldownSeconds() * 1000L)) {
			return;
		}

		MinecraftServer server = player.level().getServer();
		String nick = player.getScoreboardName();
		Role role = roles.get(uuid);
		Killer killer = killer(player, source);
		boolean creeper = "minecraft:creeper".equals(killer.entityType);

		StringBuilder ctx = new StringBuilder("Событие: смерть\n");
		ctx.append("Игрок: ").append(Roster.describe(nick, role)).append('\n');
		ctx.append("Причина: ").append(cause.text());
		if (killer.text != null) {
			ctx.append(", убийца: ").append(killer.text);
		}
		ctx.append('\n');
		if (!killer.hidden) {
			ctx.append("Сообщение игры: ").append(source.getLocalizedDeathMessage(player).getString()).append('\n');
		}
		ServerLevel level = player.level();
		ctx.append("Где: ").append(dimension(level.dimension())).append(", биом ")
				.append(level.getBiome(player.blockPosition()).getRegisteredName())
				.append(", высота Y ").append(player.blockPosition().getY()).append('\n');
		ItemStack hand = player.getMainHandItem();
		ctx.append("В руке: ").append(hand.isEmpty() ? "пусто" : BuiltInRegistries.ITEM.getKey(hand.getItem()).toString()).append('\n');
		ctx.append("Смертей сегодня: ").append(today).append('\n');
		if (series >= 2) {
			ctx.append("Серия: ").append(series).append(" смертей за ").append(cfg.flavor().seriesWindowMinutes()).append(" минут\n");
		}
		ctx.append("Рядом: ").append(nearby(player)).append('\n');
		ctx.append(time(cfg, level, server));

		Map<String, String> vars = vars(nick, role);
		vars.put("count", Integer.toString(today));
		if (killer.text != null && !killer.hidden) {
			vars.put("killer", killer.short_);
		}
		List<FallbackLines.Choice> choices = new ArrayList<>();
		if (today >= 3) {
			choices.add(new FallbackLines.Choice("death", "series", 0.5));
		}
		if (role != null) {
			choices.add(new FallbackLines.Choice("death_archetype", role.archetype().id(), 0.3));
		}
		if (creeper) {
			choices.add(new FallbackLines.Choice("death", "creeper", 0.8));
		}
		choices.add(new FallbackLines.Choice("death", killer.group != null ? killer.group : cause.group(), 0.7));
		choices.add(new FallbackLines.Choice("death", "any", 1));

		request(server, "death", uuid, ctx.toString(), choices, vars);
	}

	private record Killer(String text, String short_, String entityType, String group, boolean hidden) {
	}

	private Killer killer(ServerPlayer victim, DamageSource source) {
		Entity e = source.getEntity();
		if (e == null || e == victim) {
			return new Killer(null, null, null, null, false);
		}
		if (e instanceof ServerPlayer p) {
			if (players.optedOut(p.getUUID()) || !auth.isAuthenticated(p)) {
				return new Killer("другой игрок", null, null, "player", true);
			}
			Role r = roles.get(p.getUUID());
			return new Killer("игрок " + Roster.describe(p.getScoreboardName(), r), address(p.getScoreboardName(), r), null, "player", false);
		}
		String type = EntityType.getKey(e.getType()).toString();
		String name = DeathCauses.mob(type);
		var custom = e.getCustomName();
		if (custom != null) {
			name = name + " по имени «" + custom.getString() + "»";
		}
		return new Killer(name, name, type, null, false);
	}

	private String nearby(ServerPlayer victim) {
		List<String> out = new ArrayList<>();
		for (ServerPlayer p : victim.level().players()) {
			if (p == victim || !canSee(p) || p.distanceToSqr(victim) > NEARBY * NEARBY) {
				continue;
			}
			out.add(Roster.describe(p.getScoreboardName(), roles.get(p.getUUID())));
		}
		return out.isEmpty() ? "никого" : String.join(", ", out);
	}

	// ---------- вход и выход ----------

	/** Игрок вошёл по паролю (или сразу, если EasyAuth нет). */
	public void onJoin(ServerPlayer player) {
		long now = clock.millis();
		RikoshetConfig cfg = config.get();
		UUID uuid = player.getUUID();
		String nick = player.getScoreboardName();
		PendingLeave cancelled = pendingLeaves.remove(uuid);
		PlayerRecord before = players.join(uuid, nick, now);
		sessions.start(uuid, now);
		roles.renameIfNeeded(uuid, nick);

		if (!cfg.feature("rick") || !cfg.feature("join_leave") || stopping || players.optedOut(uuid)) {
			return;
		}
		if (cancelled != null) {
			return; // вернулся раньше, чем ушло прощание — будто и не выходил
		}
		if (before != null && now - before.lastSeen() < Duration.ofMinutes(cfg.flavor().rejoinQuietMinutes()).toMillis()) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		Role role = roles.get(uuid);
		StringBuilder ctx = new StringBuilder("Событие: вход\n");
		ctx.append("Игрок: ").append(Roster.describe(nick, role)).append('\n');
		Map<String, String> vars = vars(nick, role);
		List<FallbackLines.Choice> choices = new ArrayList<>();
		if (before == null) {
			ctx.append("На сервере: впервые\n");
			choices.add(new FallbackLines.Choice("join", "new", 1));
		} else {
			long away = now - before.lastSeen();
			long days = Duration.ofMillis(away).toDays();
			ctx.append("Не было: ").append(ago(away)).append('\n');
			ctx.append("Сыграно всего: ").append(duration(before.playtimeSeconds())).append('\n');
			int deathsToday = stats.get(uuid, DailyStats.DEATHS);
			if (deathsToday > 0) {
				ctx.append("Смертей сегодня: ").append(deathsToday).append('\n');
			}
			if (days >= 3) {
				vars.put("days", Long.toString(days));
				choices.add(new FallbackLines.Choice("join", "back", 0.8));
			}
		}
		choices.add(new FallbackLines.Choice("join", "any", 1));
		ctx.append("Онлайн: ").append(online(server, uuid)).append('\n');
		ctx.append(time(cfg, server.overworld(), server));
		request(server, "join", uuid, ctx.toString(), choices, vars);
	}

	/** Игрок отключился. authenticated — входил ли он по паролю. */
	public void onLeave(ServerPlayer player, boolean authenticated) {
		long now = clock.millis();
		UUID uuid = player.getUUID();
		SessionTracker.Session s = sessions.end(uuid);
		if (!authenticated || s == null) {
			return; // короткий заход без пароля не считается
		}
		long seconds = Math.max(0, (now - s.start()) / 1000);
		players.leave(uuid, now, seconds);
		RikoshetConfig cfg = config.get();
		if (!cfg.feature("rick") || !cfg.feature("join_leave") || stopping || players.optedOut(uuid)) {
			return;
		}
		String nick = player.getScoreboardName();
		Role role = roles.get(uuid);
		String core = "Событие: выход\n"
				+ "Игрок: " + Roster.describe(nick, role) + '\n'
				+ "Сессия: " + duration(seconds) + '\n'
				+ "Смертей за сессию: " + s.deaths() + '\n';
		PendingLeave l = new PendingLeave(uuid, nick, core, s.deaths(), seconds);
		pendingLeaves.put(uuid, l);
		scheduleLeave(player.level().getServer(), l, cfg.flavor().leaveDelaySeconds() * 1000L);
	}

	/**
	 * Прощание по таймеру, а не по тикам: когда сервер пуст, ванилла ставит его на паузу
	 * (pause-when-empty-seconds) и тики останавливаются, а очередь server.execute — нет.
	 */
	private void scheduleLeave(MinecraftServer server, PendingLeave l, long delayMillis) {
		timer.schedule(() -> server.execute(() -> fireLeave(server, l)), delayMillis, TimeUnit.MILLISECONDS);
	}

	private void fireLeave(MinecraftServer server, PendingLeave l) {
		// Игрок вернулся — onJoin уже убрал его из очереди
		if (!pendingLeaves.remove(l.uuid, l) || stopping || server.getPlayerList().getPlayer(l.uuid) != null) {
			return;
		}
		RikoshetConfig cfg = config.get();
		Role role = roles.get(l.uuid);
		String ctx = l.core + "Онлайн остались: " + online(server, l.uuid) + '\n' + time(cfg, server.overworld(), server);
		Map<String, String> vars = vars(l.nick, role);
		vars.put("deaths", Integer.toString(l.sessionDeaths));
		List<FallbackLines.Choice> choices = new ArrayList<>();
		if (l.sessionDeaths == 0 && l.sessionSeconds >= 1800) {
			choices.add(new FallbackLines.Choice("leave", "deathless", 0.6));
		}
		if (l.sessionDeaths >= 3) {
			choices.add(new FallbackLines.Choice("leave", "deaths", 0.6));
		}
		choices.add(new FallbackLines.Choice("leave", "any", 1));
		request(server, "leave", l.uuid, ctx, choices, vars);
	}

	// ---------- запрос и вывод ----------

	private void request(MinecraftServer server, String task, UUID player, String context,
			List<FallbackLines.Choice> choices, Map<String, String> vars) {
		String system = PromptBuilder.system(prompts, rosterBlock(), PERSONA.id(), task);
		List<String> memo = notes.recent(player, PERSONA.id());
		String user = PromptBuilder.user(memo.isEmpty() ? null : "- " + String.join("\n- ", memo), context, null, null);
		ai.submit(new AiRequest("flavor", "line", system, user, task, player))
				.whenComplete((res, err) -> server.execute(() -> deliver(server, task, player, res, err, choices, vars)));
	}

	private void deliver(MinecraftServer server, String task, UUID player, AiResult res, Throwable err,
			List<FallbackLines.Choice> choices, Map<String, String> vars) {
		RikoshetConfig cfg = config.get();
		if (stopping || !cfg.feature("rick") || players.optedOut(player)) {
			return;
		}
		String text = null;
		String why;
		if (err != null) {
			why = "ошибка " + err;
		} else if (res.ok()) {
			TextFilter.Result f = TextFilter.apply(res.value().get("say").getAsString(), cfg.content().maxMessageLength(), cfg.content().blocklist());
			text = f.text();
			why = f.ok() ? null : "фильтр: " + f.reason();
		} else {
			why = res.status() + (res.error() == null ? "" : " (" + res.error() + ")");
		}
		if (text == null) {
			text = fallback.pick(choices, vars);
			// Отсев фильтром — сигнал про промпт или модель, его админ должен видеть; остальное — шум
			if (res != null && res.ok()) {
				log.info("[флейвор] {}: ответ ИИ не прошёл {}, выведена заготовка", task, why);
			} else {
				log.debug("[флейвор] {}: заготовка, причина — {}", task, why);
			}
		}
		if (text != null) {
			speaker.say(server, PERSONA, text, this::canSee);
		}
	}

	public String rosterBlock() {
		return Roster.block(prompts.text("roster.md"), roles.all(), players.nameMap(), uuid -> !players.optedOut(uuid));
	}

	private String online(MinecraftServer server, UUID except) {
		List<String> out = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.getUUID().equals(except) && canSee(p)) {
				out.add(Roster.describe(p.getScoreboardName(), roles.get(p.getUUID())));
			}
		}
		return out.isEmpty() ? "никого" : String.join(", ", out);
	}

	private String time(RikoshetConfig cfg, Level level, MinecraftServer server) {
		ZonedDateTime now = ZonedDateTime.now(clock.withZone(cfg.timezone()));
		long online = server.getPlayerList().getPlayers().stream().filter(this::canSee).count();
		return "Время: " + HHMM.format(now) + " по часам сервера, в игре " + (level.isBrightOutside() ? "день" : "ночь")
				+ ", онлайн " + online;
	}

	private static Map<String, String> vars(String nick, Role role) {
		Map<String, String> v = new HashMap<>();
		v.put("player", address(nick, role));
		v.put("nick", nick);
		if (role != null) {
			v.put("role", role.title());
		}
		return v;
	}

	/** Как Рик обращается к игроку: по роли, без роли — по нику. */
	static String address(String nick, Role role) {
		return role == null ? nick : role.title();
	}

	static String dimension(ResourceKey<Level> key) {
		if (key == Level.OVERWORLD) {
			return "Верхний мир";
		}
		if (key == Level.NETHER) {
			return "Незер";
		}
		if (key == Level.END) {
			return "Край";
		}
		return key.identifier().toString();
	}

	static String duration(long seconds) {
		long h = seconds / 3600;
		long m = seconds % 3600 / 60;
		return h > 0 ? h + " ч " + m + " мин" : m + " мин";
	}

	static String ago(long millis) {
		Duration d = Duration.ofMillis(millis);
		if (d.toDays() >= 1) {
			return d.toDays() + " дн.";
		}
		if (d.toHours() >= 1) {
			return d.toHours() + " ч";
		}
		return Math.max(1, d.toMinutes()) + " мин";
	}
}
