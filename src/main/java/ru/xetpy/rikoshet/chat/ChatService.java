package ru.xetpy.rikoshet.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.ai.AiRequest;
import ru.xetpy.rikoshet.ai.AiResult;
import ru.xetpy.rikoshet.ai.AiService;
import ru.xetpy.rikoshet.ai.PromptBuilder;
import ru.xetpy.rikoshet.ai.PromptLibrary;
import ru.xetpy.rikoshet.ai.TextFilter;
import ru.xetpy.rikoshet.chronicle.ChronicleEvent;
import ru.xetpy.rikoshet.chronicle.ChronicleService;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.flavor.FallbackLines;
import ru.xetpy.rikoshet.flavor.FlavorService;
import ru.xetpy.rikoshet.flavor.LineStyle;
import ru.xetpy.rikoshet.integration.AuthTracker;
import ru.xetpy.rikoshet.memory.MemoryService;
import ru.xetpy.rikoshet.persona.Persona;
import ru.xetpy.rikoshet.persona.Role;
import ru.xetpy.rikoshet.persona.Roster;
import ru.xetpy.rikoshet.persona.Speaker;
import ru.xetpy.rikoshet.storage.DailyStats;
import ru.xetpy.rikoshet.storage.NoteStore;
import ru.xetpy.rikoshet.storage.PlayerStore;
import ru.xetpy.rikoshet.storage.RoleStore;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Рик в чате (docs/design/chat.md): отвечает на обращения, упоминания и ответы на свои реплики,
 * ведёт короткий разговор, запоминает обиды и похвалу, изредка реагирует в мире. Детектор и
 * лимиты — в главном потоке, запрос — через {@link AiService}, ответ — снова в главном.
 */
public final class ChatService {
	private static final Persona PERSONA = Persona.RICK;
	/** Игрок часто пишет в несколько строк: ждём, пока допишет, и отвечаем на всё сразу. */
	static final long DEBOUNCE_MS = 1200;
	static final int MAX_LINES = 3;
	static final int LINE_CHARS = 200;
	static final int HISTORY = 8;
	static final long HISTORY_MS = TimeUnit.MINUTES.toMillis(3);
	/** Ответ Рику без «рик» обычно короткий: «ну блин», «сам такой». */
	static final int REPLY_MAX_WORDS = 8;
	static final int RECALL_CHARS = 400;
	/** На прямое обращение, когда ИИ не ответил, — заготовка с таким шансом, иначе молчание. */
	static final double BUSY_CHANCE = 0.5;
	static final Set<String> RECALL_TAGS = NoteSaver.RECALL_TAGS;
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

	enum Trigger {
		ADDRESS("обращение к тебе в чате"),
		MENTION("о тебе говорят в чате"),
		CONVERSATION("продолжение разговора с тобой"),
		REPLY("ответ на твою реплику — а может, и не тебе");

		final String text;

		Trigger(String text) {
			this.text = text;
		}
	}

	private record ChatLine(long ts, UUID uuid, String who, String text) {
	}

	private static final class Pending {
		final List<String> lines = new ArrayList<>();
		long since;
		Trigger trigger;
		ServerPlayer player;
	}

	private record Conversation(long until, int turns) {
	}

	private final Logger log;
	private final Clock clock;
	private final Supplier<RikoshetConfig> config;
	private final Supplier<LocalDate> today;
	private final AiService ai;
	private final PromptLibrary prompts;
	private final PlayerStore players;
	private final RoleStore roles;
	private final DailyStats stats;
	private final NoteStore notes;
	private final Speaker speaker;
	private final AuthTracker auth;
	private final MemoryService memory;
	private final ChronicleService chronicle;
	private final FlavorService flavor;
	private final ChatReactions reactions;
	private final NoteSaver noteSaver;
	/** Игрок сейчас говорит с Риком в лаборатории — чат его не слушает. */
	private java.util.function.Predicate<UUID> talking = u -> false;
	/** «рик, забери меня» — портал в Цитадель вместо ответа ИИ. */
	private java.util.function.Consumer<ServerPlayer> portal = p -> { };
	private final ChatLimits limits = new ChatLimits();
	private final Random rnd = new Random();
	private final Deque<ChatLine> history = new ArrayDeque<>();
	private final Map<UUID, Pending> pending = new HashMap<>();
	private final Map<UUID, Conversation> conversations = new HashMap<>();
	/** Обиды и похвала за последний час: настроение вечера. [ts, 1 — обозвали, 2 — похвалили]. */
	private final Deque<long[]> mood = new ArrayDeque<>();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "rikoshet-chat");
		t.setDaemon(true);
		return t;
	});
	private volatile boolean stopping;
	private int replies;
	private int silent;
	private int busy;
	private int dropped;

	public ChatService(Logger log, Clock clock, Supplier<RikoshetConfig> config, Supplier<LocalDate> today, AiService ai, PromptLibrary prompts,
			PlayerStore players, RoleStore roles, DailyStats stats, NoteStore notes, Speaker speaker, AuthTracker auth, MemoryService memory,
			ChronicleService chronicle, FlavorService flavor) {
		this.log = log;
		this.clock = clock;
		this.config = config;
		this.today = today;
		this.ai = ai;
		this.prompts = prompts;
		this.players = players;
		this.roles = roles;
		this.stats = stats;
		this.notes = notes;
		this.speaker = speaker;
		this.auth = auth;
		this.memory = memory;
		this.chronicle = chronicle;
		this.flavor = flavor;
		this.reactions = new ChatReactions(stats, memory, rnd);
		this.noteSaver = new NoteSaver(config, today, stats, chronicle);
	}

	/** Связь с Цитаделью: кто сейчас в разговоре у манекена и как открыть портал. */
	public void citadel(java.util.function.Predicate<UUID> talking, java.util.function.Consumer<ServerPlayer> portal) {
		this.talking = talking;
		this.portal = portal;
	}

	public void stopping() {
		stopping = true;
		timer.shutdownNow();
	}

	// ---------- сообщение ----------

	/** Игрок написал в общий чат. Главный поток. */
	public void onMessage(ServerPlayer p, String raw) {
		RikoshetConfig cfg = config.get();
		if (stopping || !cfg.feature("rick") || !cfg.feature("chat")) {
			return;
		}
		UUID u = p.getUUID();
		if (!auth.isAuthenticated(p) || players.optedOut(u)) {
			return; // для персонажей такого игрока нет — ни ответа, ни строки в истории
		}
		if (talking.test(u)) {
			return; // говорит с Риком в лаборатории — там свой разговор
		}
		String text = clip(raw.strip(), LINE_CHARS);
		if (text.isEmpty()) {
			return;
		}
		long now = clock.millis();
		history.addLast(new ChatLine(now, u, Roster.describe(p.getScoreboardName(), roles.get(u)), text));
		while (history.size() > HISTORY * 3 || !history.isEmpty() && history.peekFirst().ts() < now - HISTORY_MS) {
			history.removeFirst();
		}
		Trigger t = trigger(p, text, now, cfg);
		if (t == null) {
			return;
		}
		if ((t == Trigger.ADDRESS || t == Trigger.CONVERSATION) && ChatDetector.portalRequest(text)) {
			portal.accept(p);
			return;
		}
		ChatLimits.Settings s = settings(cfg);
		if (limits.ignored(u, now)) {
			dropped++;
			return;
		}
		MinecraftServer server = p.level().getServer();
		if (limits.hourFull(u, now, s)) {
			limits.ignore(u, now, s);
			conversations.remove(u);
			pending.remove(u);
			String line = flavor.fallback().pick(List.of(new FallbackLines.Choice("chat", "ignore", 1)), vars(p));
			if (line != null) {
				speaker.say(server, PERSONA, line, flavor::canSee, Set.of(u));
			}
			log.info("[чат] {} в игноре на {} мин", p.getScoreboardName(), cfg.chat().ignoreMinutes());
			return;
		}
		Pending pd = pending.get(u);
		boolean first = pd == null;
		if (first) {
			pd = new Pending();
			pd.since = now;
			pending.put(u, pd);
		}
		pd.lines.add(text);
		while (pd.lines.size() > MAX_LINES) {
			pd.lines.removeFirst();
		}
		pd.player = p;
		if (pd.trigger == null || t.ordinal() < pd.trigger.ordinal()) {
			pd.trigger = t;
		}
		if (first) {
			long at = Math.max(now + DEBOUNCE_MS, limits.nextAllowed(u, now, s));
			timer.schedule(() -> server.execute(() -> flush(server, u)), at - now, TimeUnit.MILLISECONDS);
		}
	}

	/** Повод ответить или null. */
	private Trigger trigger(ServerPlayer p, String text, long now, RikoshetConfig cfg) {
		ChatDetector.Kind k = ChatDetector.classify(text, qualifiers());
		if (k == ChatDetector.Kind.ADDRESS) {
			return Trigger.ADDRESS;
		}
		if (k == ChatDetector.Kind.MENTION) {
			return rnd.nextDouble() < cfg.chat().mentionChance() ? Trigger.MENTION : null;
		}
		UUID u = p.getUUID();
		if (ChatDetector.addressedToOther(text, otherNames(u))) {
			return null;
		}
		Conversation c = conversations.get(u);
		if (c != null && c.until() >= now) {
			return Trigger.CONVERSATION;
		}
		long about = speaker.lastAbout(u);
		if (about > 0 && now - about <= cfg.chat().replyWindowSeconds() * 1000L && ChatDetector.wordCount(text) <= REPLY_MAX_WORDS) {
			return Trigger.REPLY;
		}
		return null;
	}

	private Set<String> qualifiers() {
		return ChatDetector.qualifiers(roles.all().stream().map(Role::title).toList());
	}

	/** Как зовут остальных: ники, роли целиком и последнее слово роли («морти»), кроме «рик». */
	private List<String> otherNames(UUID self) {
		Set<String> out = new HashSet<>();
		players.nameMap().forEach((u, n) -> {
			if (!u.equals(self)) {
				out.add(n.toLowerCase(Locale.ROOT));
			}
		});
		for (Role r : roles.all()) {
			if (r.uuid().equals(self)) {
				continue;
			}
			String title = r.title().toLowerCase(Locale.ROOT);
			out.add(title);
			String[] w = title.split("[\\s/]+");
			String last = w[w.length - 1];
			if (ChatDetector.classify(last, Set.of()) == ChatDetector.Kind.NONE) {
				out.add(last);
			}
		}
		// Длинные первыми: «немой/виар морти» раньше «морти»
		return out.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
	}

	// ---------- запрос ----------

	private void flush(MinecraftServer server, UUID u) {
		Pending pd = pending.remove(u);
		RikoshetConfig cfg = config.get();
		if (pd == null || stopping || !cfg.feature("rick") || !cfg.feature("chat")) {
			return;
		}
		long now = clock.millis();
		ChatLimits.Settings s = settings(cfg);
		if (limits.ignored(u, now) || limits.serverFull(now, s)
				|| ai.budget().spentToday() >= ai.budget().limit() * cfg.chat().budgetShare()) {
			dropped++;
			return;
		}
		ServerPlayer p = pd.player;
		String nick = p.getScoreboardName();
		Role role = roles.get(u);
		Conversation c = conversations.get(u);
		int turn = c != null && c.until() + DEBOUNCE_MS >= pd.since ? c.turns() + 1 : 1;
		int maxTurns = cfg.chat().conversationTurns();
		boolean last = turn >= maxTurns;

		StringBuilder ctx = new StringBuilder("Событие: ").append(pd.trigger.text);
		if (turn > 1) {
			ctx.append(", обмен ").append(turn).append(" из ").append(maxTurns);
		}
		ctx.append('\n');
		ctx.append("Пишет: ").append(Roster.describe(nick, role)).append('\n');
		ctx.append("Онлайн: ").append(online(server, u)).append('\n');
		int deaths = stats.get(u, DailyStats.DEATHS);
		if (deaths > 0) {
			ctx.append("Смертей сегодня: ").append(deaths).append('\n');
		}
		String session = chronicle.currentSession(u, now);
		if (session != null) {
			ctx.append("Чем занят: ").append(session).append('\n');
		}
		String m = mood(now);
		if (m != null) {
			ctx.append(m).append('\n');
		}
		List<String> said = speaker.recentSeenBy(u, 3);
		if (!said.isEmpty()) {
			ctx.append("Твои последние реплики ему — не повторяй их: «").append(String.join("», «", said)).append("»\n");
		}
		ZonedDateTime t = ZonedDateTime.now(clock.withZone(cfg.timezone()));
		ctx.append("Время: ").append(HHMM.format(t)).append(" по часам сервера, в игре ")
				.append(server.overworld().isBrightOutside() ? "день" : "ночь").append('\n');

		List<String> blocks = new ArrayList<>();
		List<String> memo = notes.recent(u, PERSONA.id());
		if (!memo.isEmpty()) {
			blocks.add("- " + String.join("\n- ", memo));
		}
		String recalled = memory.recall(List.of(u), RECALL_TAGS, RECALL_CHARS, 0);
		if (recalled != null) {
			blocks.add(recalled);
		}
		String system = PromptBuilder.system(prompts, flavor.rosterBlock(), PERSONA.id(), "chat")
				+ LineStyle.tail(rnd, flavor.roleNotes(server, u),
						last ? List.of("- Это последний ответ в разговоре: закончи его в образе, у тебя дела.") : List.of());
		String user = PromptBuilder.user(blocks.isEmpty() ? null : String.join("\n", blocks), ctx.toString(), history(pd.since, now),
				String.join("\n", pd.lines));
		limits.replied(u, now);
		ai.submit(new AiRequest("chat", "chat", system, user, "chat", u))
				.whenComplete((res, err) -> server.execute(() -> deliver(server, u, pd, turn, last, res, err)));
	}

	/** Последние сообщения чата до этого (без него самого) и реплики Рика, старые первыми. */
	private List<String> history(long before, long now) {
		record Item(long ts, String line) {
		}
		List<Item> items = new ArrayList<>();
		for (ChatLine l : history) {
			if (l.ts() < before && l.ts() >= now - HISTORY_MS) {
				items.add(new Item(l.ts(), l.who() + ": " + l.text()));
			}
		}
		for (Speaker.Line l : speaker.since(now - HISTORY_MS)) {
			if (l.persona() == PERSONA) {
				items.add(new Item(l.ts(), PERSONA.displayName() + ": " + l.text()));
			}
		}
		items.sort(Comparator.comparingLong(Item::ts));
		List<String> out = items.stream().map(Item::line).toList();
		return out.size() > HISTORY ? out.subList(out.size() - HISTORY, out.size()) : out;
	}

	// ---------- ответ ----------

	private void deliver(MinecraftServer server, UUID u, Pending pd, int turn, boolean last, AiResult res, Throwable err) {
		RikoshetConfig cfg = config.get();
		if (stopping || !cfg.feature("rick") || !cfg.feature("chat") || players.optedOut(u)) {
			return;
		}
		long now = clock.millis();
		if (err != null || res == null || !res.ok()) {
			if (pd.trigger == Trigger.ADDRESS && rnd.nextDouble() < BUSY_CHANCE) {
				String line = flavor.fallback().pick(List.of(new FallbackLines.Choice("chat", "busy", 1)), vars(pd.player));
				if (line != null) {
					speaker.say(server, PERSONA, line, flavor::canSee, Set.of(u));
					busy++;
				}
			}
			log.debug("[чат] без ответа: {}", err != null ? err.toString() : res == null ? "null" : res.status() + " " + res.error());
			return;
		}
		JsonObject v = res.value();
		String kind = str(v, "remember_kind", "none");
		recordMood(kind, now);
		noteSaver.save(u, kind, str(v, "remember", ""), now);
		String say = str(v, "say", "").strip();
		if (say.isEmpty()) {
			silent++; // модель решила, что это не ей
			return;
		}
		TextFilter.Result f = TextFilter.apply(say, cfg.content().maxMessageLength(), cfg.content().blocklist());
		if (!f.ok()) {
			log.info("[чат] ответ ИИ не прошёл фильтр: {}", f.reason());
			silent++;
			return;
		}
		String text = f.text();
		ServerPlayer p = pd.player;
		if (cfg.chat().actions() && !p.isRemoved() && p.isAlive()) {
			String aside = reactions.react(p, kind, now);
			if (aside != null) {
				text = text + " " + aside;
				log.info("[чат] реакция на {}: {}", p.getScoreboardName(), aside);
			}
		}
		speaker.say(server, PERSONA, text, flavor::canSee, Set.of(u));
		replies++;
		if (last) {
			conversations.remove(u);
		} else {
			conversations.put(u, new Conversation(now + cfg.chat().conversationSeconds() * 1000L, turn));
		}
	}

	private void recordMood(String kind, long now) {
		if ("insult".equals(kind)) {
			mood.addLast(new long[] {now, 1});
		} else if ("praise".equals(kind)) {
			mood.addLast(new long[] {now, 2});
		}
	}

	/** «Настроение: за час тебя обозвали 3 раза» или null. */
	private String mood(long now) {
		while (!mood.isEmpty() && mood.peekFirst()[0] < now - ChatLimits.HOUR) {
			mood.removeFirst();
		}
		long insults = mood.stream().filter(x -> x[1] == 1).count();
		long praise = mood.stream().filter(x -> x[1] == 2).count();
		if (insults == 0 && praise == 0) {
			return null;
		}
		List<String> parts = new ArrayList<>();
		if (insults > 0) {
			parts.add("обозвали " + times(insults));
		}
		if (praise > 0) {
			parts.add("похвалили " + times(praise));
		}
		return "Настроение: за час тебя " + String.join(", ", parts);
	}

	static String times(long n) {
		long d = n % 10;
		long h = n % 100;
		return n + (d >= 2 && d <= 4 && (h < 12 || h > 14) ? " раза" : " раз");
	}

	// ---------- для команд ----------

	/** Сводка для /rickadmin chat status. */
	public List<String> status() {
		RikoshetConfig cfg = config.get();
		long now = clock.millis();
		List<String> out = new ArrayList<>();
		out.add("Чат: " + (cfg.feature("chat") ? "включён" : "выключен") + "; с запуска ответов " + replies + ", промолчал " + silent
				+ ", заготовок " + busy + ", отброшено лимитами " + dropped);
		long active = conversations.values().stream().filter(c -> c.until() >= now).count();
		out.add("Разговоров сейчас: " + active + ", ждут ответа: " + pending.size());
		Map<UUID, Long> ignored = limits.ignoredNow(now);
		if (!ignored.isEmpty()) {
			Map<UUID, String> names = players.nameMap();
			List<String> list = new ArrayList<>();
			ignored.forEach((u, until) -> list.add(names.getOrDefault(u, u.toString()) + " ещё "
					+ Math.max(1, (until - now) / 60_000) + " мин"));
			out.add("В игноре: " + String.join(", ", list));
		}
		return out;
	}

	/** Реакция в мире без шанса и лимитов — для /rickdev chat react: burp, glow, gift. Ремарка или null. */
	public String devReact(ServerPlayer p, String what) {
		return reactions.force(p, what);
	}

	/** Как детектор понял фразу — для /rickdev chat test. */
	public String classify(String text) {
		return ChatDetector.classify(text, qualifiers()) + ", слов " + ChatDetector.wordCount(text);
	}

	// ---------- мелочи ----------

	private String online(MinecraftServer server, UUID except) {
		List<String> out = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.getUUID().equals(except) && flavor.canSee(p)) {
				out.add(Roster.describe(p.getScoreboardName(), roles.get(p.getUUID())));
			}
		}
		return out.isEmpty() ? "никого" : String.join(", ", out);
	}

	private Map<String, String> vars(ServerPlayer p) {
		Map<String, String> v = new HashMap<>();
		v.put("player", FlavorService.address(p.getScoreboardName(), roles.get(p.getUUID())));
		v.put("nick", p.getScoreboardName());
		return v;
	}

	private static ChatLimits.Settings settings(RikoshetConfig cfg) {
		RikoshetConfig.Chat c = cfg.chat();
		return new ChatLimits.Settings(c.replyCooldownSeconds() * 1000L, c.repliesPerHour(), c.serverPerMinute(), c.ignoreMinutes() * 60_000L);
	}

	private static String str(JsonObject o, String k, String def) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? def : e.getAsString();
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
