package ru.xetpy.rikoshet.pools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.ai.AiRequest;
import ru.xetpy.rikoshet.ai.AiResult;
import ru.xetpy.rikoshet.ai.AiService;
import ru.xetpy.rikoshet.ai.BatchService;
import ru.xetpy.rikoshet.ai.PromptBuilder;
import ru.xetpy.rikoshet.ai.PromptLibrary;
import ru.xetpy.rikoshet.ai.TextFilter;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Пулы заготовок (docs/architecture/ai-integration.md#пулы-заготовок): реплики Рика на случай,
 * когда живой ответ не успел, и новости других вселенных для MOTD и таблиста. Ночью в
 * ai.batch_hour — досчитать запас пакетом (или живыми запросами). Реплика, показанная
 * RETIRE раз, уходит на пенсию, и её место занимает свежая.
 */
public final class PoolService implements BatchService.Handler {
	static final int PER_REQUEST = 8;
	static final int NEWS_PER_REQUEST = 12;
	static final int MAX_REQUESTS = 24;
	private static final Pattern VAR = Pattern.compile("\\{([a-z_]+)}");

	/** Что держать в пуле: ситуация для модели, лимит длины, запас, после скольких показов на пенсию. */
	record Spec(String pool, String key, String persona, String task, String situation, Set<String> vars, int max, int stock, int retire) {
		String id() {
			return pool + "|" + key;
		}
	}

	static final List<Spec> SPECS;

	static {
		List<Spec> s = new ArrayList<>();
		Map<String, String> death = new java.util.LinkedHashMap<>();
		death.put("any", "игрок умер, причина неизвестна");
		death.put("series", "игрок умер уже третий раз за день");
		death.put("creeper", "игрока взорвал крипер");
		death.put("fall", "игрок разбился, упав с высоты");
		death.put("lava", "игрок сгорел в лаве");
		death.put("fire", "игрок сгорел в огне");
		death.put("drown", "игрок утонул");
		death.put("explosion", "игрок погиб от взрыва");
		death.put("mob", "игрока убил моб");
		death.put("player", "игрока убил другой игрок");
		death.put("projectile", "игрока застрелили стрелой или снарядом");
		death.put("void", "игрок выпал в пустоту за краем мира");
		death.put("starve", "игрок умер от голода");
		death.put("crush", "игрока раздавило или он задохнулся в блоках");
		death.put("magic", "игрок погиб от зелья, магии или иссушения");
		death.forEach((k, v) -> s.add(flavor("death", k, "Смерть: " + v + ". Реплика Рика вслед.", Set.of())));
		s.add(flavor("death_archetype", "rick", "Смерть игрока, чья роль — один из Риков (Токсик Рик, Фермер Рик и т. п.). Реплика Рика вслед.", Set.of()));
		s.add(flavor("death_archetype", "morty", "Смерть игрока, чья роль — один из Морти. Реплика Рика вслед.", Set.of()));
		s.add(flavor("death_archetype", "jerry", "Смерть игрока, чья роль — Джерри. Реплика Рика вслед.", Set.of()));
		s.add(flavor("join", "new", "На сервер впервые зашёл новый игрок. Приветствие Рика.", Set.of()));
		s.add(flavor("join", "back", "Вернулся игрок, которого не было несколько дней; {days} — сколько дней. Приветствие Рика.", Set.of("days")));
		s.add(flavor("join", "any", "Игрок зашёл на сервер. Приветствие Рика.", Set.of()));
		s.add(flavor("leave", "any", "Игрок вышел с сервера. Реплика Рика вслед.", Set.of()));
		s.add(flavor("leave", "deathless", "Игрок вышел после долгой сессии без единой смерти. Реплика Рика вслед.", Set.of()));
		s.add(flavor("leave", "deaths", "Игрок вышел после сессии, где умер {deaths} раз. Реплика Рика вслед.", Set.of("deaths")));
		s.add(new Spec("motd", "news", "tv", "pool_news", "Новости для второй строки в списке серверов: каждая не длиннее 45 символов.",
				Set.of(), 45, 24, 40));
		s.add(new Spec("tab", "news", "tv", "pool_news", "Новости для шапки таблиста: каждая не длиннее 80 символов.",
				Set.of(), 80, 24, 40));
		SPECS = List.copyOf(s);
	}

	private static Spec flavor(String pool, String key, String situation, Set<String> extraVars) {
		Set<String> vars = new java.util.HashSet<>(Set.of("player", "role"));
		vars.addAll(extraVars);
		return new Spec(pool, key, "rick", "pool_lines", situation, Set.copyOf(vars), 150, 16, 3);
	}

	record Line(long id, String text, int shown) {
	}

	private final Logger log;
	private final Clock clock;
	private final Supplier<RikoshetConfig> config;
	private final AiService ai;
	private final BatchService batch;
	private final PromptLibrary prompts;
	private final Database db;
	private final Supplier<String> roster;
	private final Consumer<Map<String, Map<String, List<String>>>> publish;
	private final Map<String, List<Line>> lines = new ConcurrentHashMap<>();
	private final Random rnd = new Random();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "rikoshet-pools");
		t.setDaemon(true);
		return t;
	});
	private volatile MinecraftServer server;
	private volatile boolean running;

	public PoolService(Logger log, Clock clock, Supplier<RikoshetConfig> config, AiService ai, BatchService batch,
			PromptLibrary prompts, Database db, Supplier<String> roster,
			Consumer<Map<String, Map<String, List<String>>>> publish) throws SQLException {
		this.log = log;
		this.clock = clock;
		this.config = config;
		this.ai = ai;
		this.batch = batch;
		this.prompts = prompts;
		this.db = db;
		this.roster = roster;
		this.publish = publish;
		db.call(c -> {
			try (PreparedStatement st = c.prepareStatement("SELECT id, pool, key, text, shown_count FROM content_pool");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					lines.computeIfAbsent(rs.getString(2) + "|" + rs.getString(3), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
							.add(new Line(rs.getLong(1), rs.getString(4), rs.getInt(5)));
				}
			}
			return null;
		});
		publishFlavor();
	}

	public void start(MinecraftServer server) {
		this.server = server;
		scheduleNext();
	}

	public void stop() {
		timer.shutdownNow();
	}

	private void scheduleNext() {
		RikoshetConfig c = config.get();
		ZonedDateTime now = ZonedDateTime.now(clock.withZone(c.timezone()));
		ZonedDateTime next = now.toLocalDate().atTime(c.ai().batchHour(), 30).atZone(c.timezone());
		if (!next.isAfter(now)) {
			next = next.plusDays(1);
		}
		timer.schedule(() -> server.execute(() -> {
			scheduleNext();
			run(false);
		}), Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS);
	}

	// ---------- генерация ----------

	/** Сколько не хватает по каждому пулу: запас минус реплики, ещё не ушедшие на пенсию. */
	Map<Spec, Integer> deficits() {
		Map<Spec, Integer> out = new HashMap<>();
		for (Spec s : SPECS) {
			long alive = lines.getOrDefault(s.id(), List.of()).stream().filter(l -> l.shown() < s.retire()).count();
			if (alive < s.stock()) {
				out.put(s, (int) (s.stock() - alive));
			}
		}
		return out;
	}

	/**
	 * Досчитать пулы. live — живыми запросами (по одному в 5 с), иначе пакетом, а если пакет
	 * не принят — тоже живыми. Возвращает, сколько запросов ушло.
	 */
	public int run(boolean live) {
		if (running) {
			return 0;
		}
		Map<Spec, Integer> need = deficits();
		if (need.isEmpty()) {
			return 0;
		}
		List<BatchService.Req> reqs = new ArrayList<>();
		String rosterBlock = roster.get();
		List<Map.Entry<Spec, Integer>> sorted = new ArrayList<>(need.entrySet());
		sorted.sort(Map.Entry.<Spec, Integer>comparingByValue().reversed());
		int n = 0;
		for (var e : sorted) {
			Spec s = e.getKey();
			int per = s.pool().equals("motd") || s.pool().equals("tab") ? NEWS_PER_REQUEST : PER_REQUEST;
			for (int left = e.getValue(); left > 0 && reqs.size() < MAX_REQUESTS; left -= per) {
				String system = PromptBuilder.system(prompts, rosterBlock, s.persona(), s.task());
				String user = PromptBuilder.user(null, s.situation() + "\nНужно: " + per + ".\nЛимит длины: " + s.max() + " символов.", null, null);
				JsonObject meta = new JsonObject();
				meta.addProperty("pool", s.pool());
				meta.addProperty("key", s.key());
				reqs.add(new BatchService.Req("p" + (n++), system, user, meta));
			}
		}
		running = true;
		if (!live && config.get().ai().batch()) {
			batch.submit("pools", "pools", "lines", reqs).whenComplete((id, err) -> {
				if (err != null) {
					log.info("[пулы] пакет не принят ({}) — живыми запросами", err.getCause() != null ? err.getCause().getMessage() : err.getMessage());
					server.execute(() -> runLive(reqs));
				} else {
					running = false;
				}
			});
		} else {
			runLive(reqs);
		}
		return reqs.size();
	}

	private void runLive(List<BatchService.Req> reqs) {
		running = true;
		for (int i = 0; i < reqs.size(); i++) {
			BatchService.Req q = reqs.get(i);
			boolean last = i == reqs.size() - 1;
			timer.schedule(() -> ai.submit(new AiRequest("pools", "lines", q.system(), q.user(), "pools", null))
					.whenComplete((res, err) -> server.execute(() -> {
						if (res != null) {
							completed(q.meta(), res);
						}
						if (last) {
							running = false;
						}
					})), i * 5L, TimeUnit.SECONDS);
		}
		if (reqs.isEmpty()) {
			running = false;
		}
	}

	@Override
	public void completed(JsonObject meta, AiResult result) {
		running = false;
		if (!result.ok()) {
			return;
		}
		String pool = meta.get("pool").getAsString();
		String key = meta.get("key").getAsString();
		Spec spec = SPECS.stream().filter(s -> s.pool().equals(pool) && s.key().equals(key)).findFirst().orElse(null);
		if (spec == null || !result.value().has("lines")) {
			return;
		}
		List<String> fresh = new ArrayList<>();
		for (JsonElement el : result.value().getAsJsonArray("lines")) {
			String t = accept(el.getAsString(), spec, config.get().content().blocklist());
			if (t != null && !fresh.contains(t)) {
				fresh.add(t);
			}
		}
		if (fresh.isEmpty()) {
			return;
		}
		long now = clock.millis();
		String model = result.model();
		db.execute("pool", c -> {
			boolean auto = c.getAutoCommit();
			c.setAutoCommit(false);
			try (PreparedStatement st = c.prepareStatement(
					"INSERT INTO content_pool (pool, key, text, generated_at, model) VALUES (?, ?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
				for (String t : fresh) {
					st.setString(1, pool);
					st.setString(2, key);
					st.setString(3, t);
					st.setLong(4, now);
					st.setString(5, model);
					st.executeUpdate();
					try (ResultSet rs = st.getGeneratedKeys()) {
						long id = rs.next() ? rs.getLong(1) : -1;
						lines.computeIfAbsent(spec.id(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(new Line(id, t, 0));
					}
				}
				c.commit();
			} catch (SQLException e) {
				c.rollback();
				throw e;
			} finally {
				c.setAutoCommit(auto);
			}
			if (server != null) {
				server.execute(this::publishFlavor);
			}
		});
	}

	@Override
	public void failed(String reason) {
		running = false;
		log.warn("[пулы] пакет не выполнен: {}", reason);
	}

	/**
	 * Годится ли строка в пул: фильтр текста, длина, только разрешённые плейсхолдеры. null — нет.
	 */
	static String accept(String raw, Spec s, List<String> blocklist) {
		TextFilter.Result f = TextFilter.apply(raw, s.max(), blocklist);
		if (!f.ok() || f.text().isBlank() || f.text().length() < raw.strip().length() - 1 && s.max() <= 80) {
			return null; // новость для MOTD обрезанной не нужна
		}
		Matcher m = VAR.matcher(f.text());
		while (m.find()) {
			if (!s.vars().contains(m.group(1))) {
				return null;
			}
		}
		return f.text();
	}

	// ---------- выдача ----------

	/** Реплики пулов для заготовок флейвора: секция → ключ → строки, ещё не ушедшие на пенсию. */
	private void publishFlavor() {
		Map<String, Map<String, List<String>>> out = new HashMap<>();
		for (Spec s : SPECS) {
			if (s.pool().equals("motd") || s.pool().equals("tab")) {
				continue;
			}
			List<String> l = lines.getOrDefault(s.id(), List.of()).stream().filter(x -> x.shown() < s.retire()).map(Line::text).toList();
			if (!l.isEmpty()) {
				out.computeIfAbsent(s.pool(), k -> new HashMap<>()).put(s.key(), l);
			}
		}
		publish.accept(out);
	}

	/** Реплика пула показана игрокам: +1 к показам (реплика флейвора — по тексту). */
	public void shown(String text) {
		for (var e : lines.entrySet()) {
			List<Line> l = e.getValue();
			for (int i = 0; i < l.size(); i++) {
				Line x = l.get(i);
				if (x.text().equals(text)) {
					l.set(i, new Line(x.id(), x.text(), x.shown() + 1));
					long id = x.id();
					db.execute("pool shown", c -> {
						try (PreparedStatement st = c.prepareStatement("UPDATE content_pool SET shown_count = shown_count + 1 WHERE id = ?")) {
							st.setLong(1, id);
							st.executeUpdate();
						}
					});
					return;
				}
			}
		}
	}

	/** Новость для MOTD или таблиста: из наименее показанных, или null. */
	public String news(String pool) {
		List<Line> l = lines.getOrDefault(pool + "|news", List.of());
		if (l.isEmpty()) {
			return null;
		}
		int min = l.stream().mapToInt(Line::shown).min().orElse(0);
		List<Line> least = l.stream().filter(x -> x.shown() <= min + 2).toList();
		Line pick = least.get(rnd.nextInt(least.size()));
		shown(pick.text());
		return pick.text();
	}

	/** Сводка для админа: пул|ключ → живых / запас. */
	public Map<String, String> status() {
		Map<String, String> out = new java.util.TreeMap<>(Comparator.naturalOrder());
		for (Spec s : SPECS) {
			long alive = lines.getOrDefault(s.id(), List.of()).stream().filter(l -> l.shown() < s.retire()).count();
			out.put(s.id(), alive + "/" + s.stock());
		}
		return out;
	}

	public boolean running() {
		return running;
	}
}
