package ru.xetpy.rikoshet.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.ai.AiRequest;
import ru.xetpy.rikoshet.ai.AiResult;
import ru.xetpy.rikoshet.ai.AiService;
import ru.xetpy.rikoshet.ai.PromptBuilder;
import ru.xetpy.rikoshet.ai.PromptLibrary;
import ru.xetpy.rikoshet.ai.TextFilter;
import ru.xetpy.rikoshet.ai.action.ActionValidator;
import ru.xetpy.rikoshet.ai.action.AiAction;
import ru.xetpy.rikoshet.chat.NoteSaver;
import ru.xetpy.rikoshet.chronicle.ChronicleEvent;
import ru.xetpy.rikoshet.chronicle.ChronicleService;
import ru.xetpy.rikoshet.citadel.Citadel;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.flavor.FallbackLines;
import ru.xetpy.rikoshet.flavor.FlavorService;
import ru.xetpy.rikoshet.flavor.LineStyle;
import ru.xetpy.rikoshet.integration.AuthTracker;
import ru.xetpy.rikoshet.integration.OriginsBridge;
import ru.xetpy.rikoshet.memory.MemoryService;
import ru.xetpy.rikoshet.persona.Persona;
import ru.xetpy.rikoshet.persona.Role;
import ru.xetpy.rikoshet.persona.Roster;
import ru.xetpy.rikoshet.persona.Speaker;
import ru.xetpy.rikoshet.quest.Level;
import ru.xetpy.rikoshet.quest.QuestDefs;
import ru.xetpy.rikoshet.quest.QuestService;
import ru.xetpy.rikoshet.quest.Reputation;
import ru.xetpy.rikoshet.quest.RickAdvancements;
import ru.xetpy.rikoshet.quest.Rewards;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Разговор с Риком в лаборатории (docs/design/characters/README.md#разговор): ПКМ по манекену —
 * разговор на talk_seconds, всё, что игрок пишет в чат, — Рику. Перед каждым ответом код сам
 * сдаёт выполненные задания; модель говорит и просит действия, код проверяет их по белому списку
 * (docs/architecture/ai-actions.md). Главный поток.
 */
public final class TalkService {
	private static final Persona PERSONA = Persona.RICK;
	static final Set<String> ACTIONS = Set.of("change_reputation", "give_quest", "complete_quest", "give_item");
	static final long DEBOUNCE_MS = 1200;
	static final int MAX_LINES = 3;
	static final int LINE_CHARS = 200;
	static final int HISTORY = 10;
	static final int RECALL_CHARS = 500;
	/** Репутации за один разговор — не больше этого в сумме по модулю. */
	static final int MAX_REPUTATION_PER_TALK = 10;
	static final long HOUR = 3_600_000L;
	static final Set<String> RECALL = Set.of(ChronicleEvent.CHAT_NOTE, "kind:insult", "kind:praise", "kind:promise", "kind:confession",
			"quest", "reputation", "rick_gift");
	private static final Pattern BYE = Pattern.compile(
			"^\\s*(пока|бывай|до\\s+свидания|досвидания|прощай|ухожу|я\\s+пошёл|я\\s+пошел|бб|bye)[\\s!.,)]*$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter DDMM = DateTimeFormatter.ofPattern("dd.MM");

	private static final class Talk {
		final UUID uuid;
		ServerPlayer player;
		long lastActivity;
		int turns;
		int reputation;
		boolean inFlight;
		/** Ответ в работе: ждём текст или звук. Пока ждём — «Рик думает…», новых запросов нет. */
		boolean waiting;
		long lastReply;
		final List<String> history = new ArrayList<>();
		final List<String> pending = new ArrayList<>();
		long pendingSince;
		/** Что случилось с заданиями перед этим ответом — для «Только что». */
		final List<String> happened = new ArrayList<>();

		Talk(UUID uuid, ServerPlayer player, long now) {
			this.uuid = uuid;
			this.player = player;
			this.lastActivity = now;
		}
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
	private final QuestService quests;
	private final Reputation reputation;
	private final NoteSaver noteSaver;
	private final Random rnd = new Random();
	private final Map<UUID, Talk> talks = new LinkedHashMap<>();
	private final Map<UUID, Deque<Long>> hourly = new HashMap<>();
	private int replies;
	private int fallbacks;
	/** Сказать живую реплику: озвучка сама решает, когда вывести текст (docs/design/voice.md). */
	private java.util.function.BiConsumer<String, Runnable> voice = (text, show) -> show.run();
	/** Запрос к ИИ начат (+1) или закончен (−1): над Риком «думает…». */
	private java.util.function.IntConsumer thinking = d -> { };

	public TalkService(Logger log, Clock clock, Supplier<RikoshetConfig> config, Supplier<LocalDate> today, AiService ai, PromptLibrary prompts,
			PlayerStore players, RoleStore roles, DailyStats stats, NoteStore notes, Speaker speaker, AuthTracker auth, MemoryService memory,
			ChronicleService chronicle, FlavorService flavor, QuestService quests, Reputation reputation) {
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
		this.quests = quests;
		this.reputation = reputation;
		this.noteSaver = new NoteSaver(config, today, stats, chronicle);
	}

	public void voice(java.util.function.BiConsumer<String, Runnable> voice, java.util.function.IntConsumer thinking) {
		this.voice = voice;
		this.thinking = thinking;
	}

	public boolean active(UUID u) {
		return talks.containsKey(u);
	}

	public int activeCount() {
		return talks.size();
	}

	// ---------- начало и конец ----------

	/** ПКМ по Рику: начать разговор или продлить идущий. */
	public void start(ServerPlayer p, Entity rick) {
		RikoshetConfig cfg = config.get();
		UUID u = p.getUUID();
		if (!cfg.feature("citadel") || !auth.isAuthenticated(p) || players.optedOut(u)) {
			return;
		}
		long now = clock.millis();
		Talk t = talks.get(u);
		if (t != null) {
			t.lastActivity = now;
			t.player = p;
			return;
		}
		t = new Talk(u, p, now);
		talks.put(u, t);
		p.sendOverlayMessage(Component.literal("Разговор с Риком: пиши в чат. «Пока» — конец.").withStyle(ChatFormatting.GREEN));
		RickAdvancements.award(p, RickAdvancements.TALK);
		request(p.level().getServer(), t, null);
	}

	/** Сообщение в чат. true — оно Рику в разговоре, общему чату его не отдаём. Главный поток. */
	public boolean onMessage(ServerPlayer p, String raw) {
		Talk t = talks.get(p.getUUID());
		if (t == null) {
			return false;
		}
		String text = raw.strip();
		if (text.isEmpty()) {
			return true;
		}
		long now = clock.millis();
		t.lastActivity = now;
		t.player = p;
		if (BYE.matcher(text).matches()) {
			end(p.level().getServer(), t, "bye");
			return true;
		}
		if (t.pending.isEmpty()) {
			t.pendingSince = now;
		}
		t.pending.add(text.length() <= LINE_CHARS ? text : text.substring(0, LINE_CHARS));
		while (t.pending.size() > MAX_LINES) {
			t.pending.removeFirst();
		}
		return true;
	}

	/** Каждый тик: отправить накопленное, когда игрок дописал и пауза прошла. */
	public void tick(MinecraftServer server) {
		if (talks.isEmpty()) {
			return;
		}
		long now = clock.millis();
		long cooldown = config.get().citadel().talkCooldownSeconds() * 1000L;
		for (Talk t : List.copyOf(talks.values())) {
			if (!t.waiting && !t.pending.isEmpty() && now - t.pendingSince >= DEBOUNCE_MS && now - t.lastReply >= cooldown) {
				List<String> lines = List.copyOf(t.pending);
				t.pending.clear();
				request(server, t, lines);
			}
		}
	}

	/** Раз в секунду: молчание, расстояние, ушёл из Цитадели — разговор окончен. */
	public void everySecond(MinecraftServer server, Entity rick) {
		if (talks.isEmpty()) {
			return;
		}
		RikoshetConfig cfg = config.get();
		long now = clock.millis();
		double max = cfg.citadel().talkDistance();
		for (Iterator<Talk> it = talks.values().iterator(); it.hasNext(); ) {
			Talk t = it.next();
			ServerPlayer p = t.player;
			boolean gone = p.isRemoved() || !Citadel.in(p) || rick == null || p.distanceToSqr(rick) > max * max;
			boolean silent = !t.waiting && now - t.lastActivity > cfg.citadel().talkSeconds() * 1000L;
			if (t.waiting && !gone) {
				p.sendOverlayMessage(Component.literal("Рик думает" + ".".repeat((int) (now / 1000 % 3) + 1)).withStyle(ChatFormatting.GRAY));
			}
			if (gone || silent || !cfg.feature("citadel")) {
				it.remove();
				if (!p.isRemoved()) {
					p.sendOverlayMessage(Component.literal("Рик отвернулся к пробиркам.").withStyle(ChatFormatting.GRAY));
				}
			}
		}
	}

	/** Реплика на «пока» — следующей задачей сервера: в чате она встанет после сообщения игрока. */
	private void end(MinecraftServer server, Talk t, String key) {
		talks.remove(t.uuid);
		String line = fallback(t.player, key);
		server.schedule(server.wrapRunnable(() -> say(server, t, line)));
	}

	public void stopping() {
		talks.clear();
	}

	// ---------- запрос ----------

	/** lines — что сказал игрок; null — он только подошёл. */
	private void request(MinecraftServer server, Talk t, List<String> lines) {
		RikoshetConfig cfg = config.get();
		ServerPlayer p = t.player;
		UUID u = t.uuid;
		long now = clock.millis();
		LocalDate day = today.get();
		t.happened.clear();
		if (cfg.feature("quests")) {
			handle(p, t, quests.check(p, day, now), day, now);
		}
		if (lines != null) {
			for (String l : lines) {
				t.history.add(Roster.describe(p.getScoreboardName(), roles.get(u)) + ": " + l);
				notes.line(u, PERSONA.id(), "player", l, now);
			}
		}
		if (!hourOk(u, now, cfg.citadel().talkPerHour())) {
			say(server, t, fallback(p, "busy_talk"));
			return;
		}
		t.turns++;
		t.inFlight = true;
		t.waiting = true;
		thinking.accept(1);
		String system = PromptBuilder.system(prompts, flavor.rosterBlock(), PERSONA.id(), "talk")
				+ LineStyle.tail(rnd, flavor.roleNotes(server, u), List.of());
		String user = PromptBuilder.user(memoryBlock(u), context(server, t, p, cfg, day, now, lines == null),
				tail(t.history, HISTORY), lines == null ? null : String.join("\n", lines));
		ActionValidator.Scope scope = scope(u, day, cfg);
		if (Boolean.getBoolean("rikoshet.dev")) {
			log.info("[разговор] запрос для {}:\n{}", p.getScoreboardName(), user);
		}
		ai.submit(new AiRequest("dialogue", "talk", system, user, "talk", u))
				.whenComplete((res, err) -> server.execute(() -> deliver(server, t, scope, res, err)));
	}

	private void deliver(MinecraftServer server, Talk t, ActionValidator.Scope scope, AiResult res, Throwable err) {
		t.inFlight = false;
		t.lastReply = clock.millis();
		thinking.accept(-1);
		RikoshetConfig cfg = config.get();
		ServerPlayer p = t.player;
		if (p.isRemoved() || players.optedOut(t.uuid)) {
			t.waiting = false;
			return;
		}
		if (err != null || res == null || !res.ok()) {
			log.debug("[разговор] без ответа: {}", err != null ? err.toString() : res == null ? "null" : res.status() + " " + res.error());
			fallbacks++;
			t.waiting = false;
			say(server, t, fallback(p, t.turns <= 1 ? "hello" : "busy_talk"));
			return;
		}
		JsonObject v = res.value();
		long now = clock.millis();
		LocalDate day = today.get();
		noteSaver.save(t.uuid, str(v, "remember_kind", "none"), str(v, "remember", ""), now);
		String say = str(v, "say", "").strip();
		TextFilter.Result f = say.isEmpty() ? null : TextFilter.apply(say, cfg.content().maxMessageLength(), cfg.content().blocklist());
		if (f != null && f.ok()) {
			String text = f.text();
			voice.accept(text, () -> {
				t.waiting = false;
				t.lastReply = clock.millis();
				say(server, t, text);
			});
			replies++;
		} else {
			t.waiting = false;
			if (f != null) {
				log.info("[разговор] реплика не прошла фильтр: {}", f.reason());
			}
		}
		ActionValidator.Outcome o = ActionValidator.validate(v, scope, cfg.content().blocklist());
		if (!o.rejected().isEmpty()) {
			log.info("[разговор] {}: отклонено {}", p.getScoreboardName(), o.rejected());
		}
		for (AiAction a : o.accepted()) {
			apply(p, t, a, cfg, day, now);
		}
	}

	// ---------- действия ----------

	private void apply(ServerPlayer p, Talk t, AiAction a, RikoshetConfig cfg, LocalDate day, long now) {
		switch (a) {
			case AiAction.ChangeReputation c -> {
				// Итог за разговор держится в пределах ±MAX_REPUTATION_PER_TALK
				int delta = c.delta() > 0 ? Math.min(c.delta(), MAX_REPUTATION_PER_TALK - t.reputation)
						: Math.max(c.delta(), -MAX_REPUTATION_PER_TALK - t.reputation);
				if (delta == 0) {
					log.info("[разговор] {}: репутация за разговор уже на пределе ±{}", p.getScoreboardName(), MAX_REPUTATION_PER_TALK);
					return;
				}
				t.reputation += delta;
				Reputation.Change ch = reputation.add(t.uuid, delta, "talk", c.reason(), now, day.toString());
				notify(p, Component.literal("Полезность для науки: " + signed(delta) + " → " + ch.after() + " (" + ch.to().title + ")")
						.withStyle(delta > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
				levelChanged(p, ch, now, day);
			}
			case AiAction.GiveQuest g -> {
				if (!cfg.feature("quests")) {
					return;
				}
				QuestDefs.Def d = QuestDefs.EXPERIMENT.equals(g.quest()) ? quests.defs().experiment(day) : quests.defs().get(g.quest());
				if (d == null || quests.available(t.uuid, day, cfg.quests().maxActive()).stream().noneMatch(x -> x.id().equals(d.id()))) {
					return;
				}
				QuestService.Active q = quests.accept(p, d, PERSONA.id(), day, now);
				notify(p, Component.literal("Новое задание: «" + d.title() + "» — " + d.goal() + ", до " + DDMM.format(q.deadline().minusDays(1)))
						.withStyle(ChatFormatting.YELLOW));
			}
			case AiAction.CompleteQuest c -> {
				if (!cfg.feature("quests")) {
					return;
				}
				List<QuestService.Outcome> out = quests.check(p, day, now);
				handle(p, t, out, day, now);
				if (out.isEmpty()) {
					for (QuestService.Active q : quests.active(t.uuid)) {
						if (q.def().id().equals(c.quest())) {
							notify(p, Component.literal("«" + q.def().title() + "» ещё не готово: " + quests.progress(p, q) + " из " + q.def().count())
									.withStyle(ChatFormatting.GRAY));
						}
					}
				}
			}
			case AiAction.GiveItem g -> {
				if (!cfg.feature("quests")) {
					return;
				}
				Rewards.Entry e = quests.rewards().get(g.reward());
				if (e == null || stats.increment(t.uuid, "rick.gift") > cfg.quests().giftsPerDay()) {
					return;
				}
				Component given = quests.rewards().give(p, e, g.count(), PERSONA.id(), "gift", now, day.toString());
				notify(p, Component.literal("Рик даёт: ").withStyle(ChatFormatting.GREEN).append(given));
				memory.remember(t.uuid, "rick_gift", "получил от Рика подарок: " + e.name(), 6);
			}
			case AiAction.Remember ignored -> {
			}
		}
	}

	/** Сданные и проваленные задания: сообщения игроку, летопись, достижения, «Только что» для модели. */
	private void handle(ServerPlayer p, Talk t, List<QuestService.Outcome> out, LocalDate day, long now) {
		for (QuestService.Outcome o : out) {
			QuestDefs.Def d = o.quest().def();
			JsonObject data = new JsonObject();
			data.addProperty("title", d.title());
			data.addProperty("result", o.done() ? "done" : "failed");
			if (o.done()) {
				MutableComponent msg = Component.literal("Принято: «" + d.title() + "». Полезность для науки " + signed(o.change().delta())
						+ " → " + o.change().after()).withStyle(ChatFormatting.GREEN);
				if (o.reward() != null) {
					msg.append(Component.literal(". Награда: ").withStyle(ChatFormatting.GREEN)).append(o.reward());
					data.addProperty("reward", o.rewardText());
				}
				notify(p, msg);
				t.happened.add("сдал задание «" + d.title() + "» (" + d.goal() + ")"
						+ (o.rewardText() == null ? "" : ", код выдал награду: " + o.rewardText())
						+ ", полезность " + signed(o.change().delta()));
				if (QuestDefs.EXPERIMENT.equals(d.id())) {
					RickAdvancements.experiments(p, quests.experimentsDone(t.uuid));
				}
			} else {
				notify(p, Component.literal("Провалено: «" + d.title() + "» — срок вышел. Полезность для науки " + signed(o.change().delta())
						+ " → " + o.change().after()).withStyle(ChatFormatting.RED));
				t.happened.add("провалил задание «" + d.title() + "»: срок вышел, полезность " + signed(o.change().delta()));
			}
			chronicle.record(new ChronicleEvent(now, day.toString(), t.uuid, "quest", d.id(), o.done() ? 6 : 5, data));
			levelChanged(p, o.change(), now, day);
		}
	}

	private void levelChanged(ServerPlayer p, Reputation.Change ch, long now, LocalDate day) {
		if (!ch.levelChanged()) {
			return;
		}
		RickAdvancements.level(p, ch.to());
		JsonObject data = new JsonObject();
		data.addProperty("from", ch.from().title);
		data.addProperty("to", ch.to().title);
		chronicle.record(new ChronicleEvent(now, day.toString(), p.getUUID(), "reputation", ch.to().id, 7, data));
		notify(p, Component.literal("Новый уровень у Рика: " + ch.to().title).withStyle(ChatFormatting.GOLD));
	}

	// ---------- контекст ----------

	private String context(MinecraftServer server, Talk t, ServerPlayer p, RikoshetConfig cfg, LocalDate day, long now, boolean greeting) {
		UUID u = t.uuid;
		Role role = roles.get(u);
		StringBuilder ctx = new StringBuilder("Событие: ").append(greeting ? "игрок подошёл к тебе в лаборатории и ткнул тебя"
				: "игрок говорит с тобой в лаборатории, обмен " + t.turns).append('\n');
		ctx.append("Игрок: ").append(Roster.describe(p.getScoreboardName(), role)).append('\n');
		if (t.turns <= 1) {
			String race = OriginsBridge.race(p);
			if (race != null) {
				ctx.append("Раса: ").append(race).append('\n');
			}
		}
		int value = reputation.get(u);
		Level level = Level.of(value);
		ctx.append("Полезность для науки: ").append(value).append(" — ").append(level.title).append(": ").append(level.tone).append('\n');
		int deaths = stats.get(u, DailyStats.DEATHS);
		if (deaths > 0) {
			ctx.append("Смертей сегодня: ").append(deaths).append('\n');
		}
		String session = chronicle.currentSession(u, now);
		if (session != null) {
			ctx.append("Чем занят: ").append(session).append('\n');
		}
		if (!t.happened.isEmpty()) {
			ctx.append("Только что: ").append(String.join("; ", t.happened)).append('\n');
		}
		if (cfg.feature("quests")) {
			quests(ctx, p, u, level, cfg, day);
		}
		ZonedDateTime time = ZonedDateTime.now(clock.withZone(cfg.timezone()));
		ctx.append("Время: ").append(HHMM.format(time)).append(" по часам сервера\n");
		return ctx.toString();
	}

	private void quests(StringBuilder ctx, ServerPlayer p, UUID u, Level level, RikoshetConfig cfg, LocalDate day) {
		int done = quests.experimentsDone(u);
		if (done > 0) {
			ctx.append("Экспериментов сдал всего: ").append(done).append('\n');
		}
		List<QuestService.Active> mine = quests.active(u);
		if (!mine.isEmpty()) {
			ctx.append("Его задания:\n");
			for (QuestService.Active a : mine) {
				ctx.append("- ").append(a.def().id()).append(" «").append(a.def().title()).append("»: ").append(a.def().goal())
						.append(" — сделано ").append(quests.progress(p, a)).append(", срок до ").append(DDMM.format(a.deadline().minusDays(1))).append('\n');
			}
		}
		List<QuestDefs.Def> can = quests.available(u, day, cfg.quests().maxActive());
		if (level == Level.BIOMASS) {
			ctx.append("Выдать задание: нельзя, он биомусор\n");
		} else if (can.isEmpty()) {
			ctx.append("Выдать задание: нечего — у него уже ").append(mine.size()).append(" или всё в работе\n");
		} else {
			ctx.append("Можешь выдать:\n");
			for (QuestDefs.Def d : can) {
				ctx.append("- ").append(d.id()).append(" «").append(d.title()).append("»: ").append(d.goal())
						.append(QuestDefs.EXPERIMENT.equals(d.id()) ? ", только сегодня" : ", дней: " + d.days());
				if (d.brief() != null) {
					ctx.append(". ").append(d.brief());
				}
				ctx.append('\n');
			}
		}
		int giftsLeft = cfg.quests().giftsPerDay() - stats.get(u, "rick.gift");
		List<Rewards.Entry> gifts = quests.rewards().available(level);
		if (giftsLeft <= 0 || gifts.isEmpty()) {
			ctx.append("Подарки: сегодня не даёшь\n");
		} else {
			ctx.append("Подарки (редко, только за дело, не по просьбе):\n");
			for (Rewards.Entry e : gifts) {
				ctx.append("- ").append(e.id()).append(": ").append(e.kind() == Rewards.Kind.ARTIFACT ? "«" + e.name() + "»" : e.name())
						.append(", ").append(e.min() == e.max() ? String.valueOf(e.min()) : e.min() + "–" + e.max()).append('\n');
			}
		}
	}

	private ActionValidator.Scope scope(UUID u, LocalDate day, RikoshetConfig cfg) {
		if (!cfg.feature("quests")) {
			return new ActionValidator.Scope(Set.of("change_reputation"), Set.of(), Set.of(), Map.of());
		}
		Set<String> can = new java.util.HashSet<>();
		quests.available(u, day, cfg.quests().maxActive()).forEach(d -> can.add(d.id()));
		Set<String> mine = new java.util.HashSet<>();
		quests.active(u).forEach(a -> mine.add(a.def().id()));
		Map<String, ActionValidator.Range> gifts = new HashMap<>();
		if (stats.get(u, "rick.gift") < cfg.quests().giftsPerDay()) {
			quests.rewards().available(reputation.level(u)).forEach(e -> gifts.put(e.id(), new ActionValidator.Range(e.min(), e.max())));
		}
		return new ActionValidator.Scope(ACTIONS, can, mine, gifts);
	}

	private String memoryBlock(UUID u) {
		List<String> blocks = new ArrayList<>();
		List<String> memo = notes.recent(u, PERSONA.id());
		if (!memo.isEmpty()) {
			blocks.add("- " + String.join("\n- ", memo));
		}
		String recalled = memory.recall(List.of(u), RECALL, RECALL_CHARS, 0);
		if (recalled != null) {
			blocks.add(recalled);
		}
		return blocks.isEmpty() ? null : String.join("\n", blocks);
	}

	// ---------- вывод ----------

	private void say(MinecraftServer server, Talk t, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		speaker.say(server, PERSONA, text, flavor::canSee, Set.of(t.uuid));
		t.history.add(PERSONA.displayName() + ": " + text);
		notes.line(t.uuid, PERSONA.id(), "persona", text, clock.millis());
	}

	private static void notify(ServerPlayer p, Component msg) {
		p.sendSystemMessage(Component.literal("[Рик] ").withStyle(ChatFormatting.DARK_GREEN).append(msg));
	}

	private String fallback(ServerPlayer p, String key) {
		Map<String, String> vars = new HashMap<>();
		vars.put("player", FlavorService.address(p.getScoreboardName(), roles.get(p.getUUID())));
		vars.put("nick", p.getScoreboardName());
		return flavor.fallback().pick(List.of(new FallbackLines.Choice("citadel", key, 1)), vars);
	}

	private boolean hourOk(UUID u, long now, int perHour) {
		Deque<Long> d = hourly.computeIfAbsent(u, k -> new ArrayDeque<>());
		while (!d.isEmpty() && d.peekFirst() < now - HOUR) {
			d.removeFirst();
		}
		if (d.size() >= perHour) {
			return false;
		}
		d.addLast(now);
		return true;
	}

	/** Сводка для /rickadmin citadel status. */
	public String status() {
		return "Разговоров сейчас: " + talks.size() + "; с запуска ответов " + replies + ", заготовок " + fallbacks;
	}

	private static List<String> tail(List<String> l, int n) {
		return l.size() <= n ? List.copyOf(l) : List.copyOf(l.subList(l.size() - n, l.size()));
	}

	private static String signed(int n) {
		return n > 0 ? "+" + n : String.valueOf(n);
	}

	private static String str(JsonObject o, String k, String def) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? def : e.getAsString();
	}
}
