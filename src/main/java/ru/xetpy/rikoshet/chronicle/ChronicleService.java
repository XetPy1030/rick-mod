package ru.xetpy.rikoshet.chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.chronicle.analysis.Analyzer;
import ru.xetpy.rikoshet.chronicle.analysis.DayData;
import ru.xetpy.rikoshet.chronicle.analysis.DayReport;
import ru.xetpy.rikoshet.chronicle.social.SocialTracker;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.flavor.DeathCauses;
import ru.xetpy.rikoshet.persona.Role;
import ru.xetpy.rikoshet.storage.DailyStats;
import ru.xetpy.rikoshet.storage.PlayerRecord;
import ru.xetpy.rikoshet.storage.PlayerStore;
import ru.xetpy.rikoshet.storage.RoleStore;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Летопись (docs/design/chronicle.md): что игроки делали. Главный поток снимает приросты
 * ванильной статистики раз в snapshot_minutes, замеряет положение раз в sample_seconds и
 * слушает редкие события; всё медленное — в потоке БД. Ночью — анализ дня.
 */
public final class ChronicleService {
	/** Клетка с этим временем за всё время — дом. */
	static final long HOME_MIN_SECONDS = 2 * 3600;
	/** Структуры проверяем на каждом N-м замере. */
	static final int STRUCTURE_EVERY = 4;
	/** Столько неподвижных замеров подряд — AFK. */
	static final int IDLE_SAMPLES = 4;
	static final double NEAR = 32;
	/** Сколько фактов сохраняют итоги дня: аналитик читает больше, чем влезает в газету. */
	public static final int ANALYST_FACTS = 80;
	static final Gson GSON = new Gson();

	private final Logger log;
	private final Clock clock;
	private final Supplier<RikoshetConfig> config;
	private final Supplier<LocalDate> today;
	private final ChronicleStore store;
	private final DailyStats stats;
	private final PlayerStore players;
	private final RoleStore roles;
	private final StatKeys keys = new StatKeys();
	private final SocialTracker social = new SocialTracker();

	private final Map<UUID, PlayerTracker> trackers = new HashMap<>();
	private final Map<UUID, ServerPlayer> devPlayers = new ConcurrentHashMap<>();
	private final Map<UUID, Set<String>> seen;
	private final Map<String, UUID> firsts;
	private final Map<UUID, LongOpenHashSet> cells = new HashMap<>();
	private final LongOpenHashSet allCells = new LongOpenHashSet();
	private final List<String> dims = new ArrayList<>();
	private final Map<String, Integer> dimIndex = new HashMap<>();
	private final Map<UUID, JsonObject> lastSessions = new ConcurrentHashMap<>();
	private final Map<UUID, JsonObject> profiles = new ConcurrentHashMap<>();
	private final List<Consumer<ChronicleEvent>> eventListeners = new CopyOnWriteArrayList<>();
	private final List<BiConsumer<LocalDate, DayReport>> reportListeners = new CopyOnWriteArrayList<>();

	private volatile BuildHooks buildHooks;
	private LocalDate currentDay;
	private long lastSample;
	private long lastCycle;
	private volatile boolean stopping;

	public ChronicleService(Logger log, Clock clock, Supplier<RikoshetConfig> config, Supplier<LocalDate> today,
			ChronicleStore store, DailyStats stats, PlayerStore players, RoleStore roles) throws SQLException {
		this.log = log;
		this.clock = clock;
		this.config = config;
		this.today = today;
		this.store = store;
		this.stats = stats;
		this.players = players;
		this.roles = roles;
		ChronicleStore.Loaded l = store.load();
		this.seen = new HashMap<>(l.seen());
		this.firsts = new HashMap<>(l.firsts());
		for (ChronicleStore.CellRow r : l.cells()) {
			long k = cellKey(r.dim(), r.cx(), r.cz());
			cells.computeIfAbsent(r.uuid(), u -> new LongOpenHashSet()).add(k);
			allCells.add(k);
		}
		setHomes(l.cells());
		lastSessions.putAll(l.lastSessions());
		profiles.putAll(l.profiles());
		this.totalsAtStart = l.totals();
		this.currentDay = today.get();
		long now = clock.millis();
		this.lastSample = now;
		this.lastCycle = now;
		log.info("[летопись] загружено: мест {}, находок {}, профилей {}", allCells.size(), firsts.size(), profiles.size());
	}

	/** Счётчики за всё время из БД — для игроков, которых ещё не видели в этой сессии сервера. */
	private final Map<UUID, Map<String, Long>> totalsAtStart;

	/** Куда сообщать о стройке ({@link ru.xetpy.rikoshet.builds.BuildService}). */
	public interface BuildHooks {
		/** Игрок провёл окно в клетке и поставил или добыл там столько. */
		void window(UUID uuid, String dim, int cx, int cz, long placed, long mined, long now);

		/** Игрок сейчас в клетке. */
		void presence(String dim, int cx, int cz, long now);
	}

	public void setBuildHooks(BuildHooks hooks) {
		this.buildHooks = hooks;
	}

	public boolean enabled() {
		return config.get().feature("chronicle");
	}

	public void onEvent(Consumer<ChronicleEvent> listener) {
		eventListeners.add(listener);
	}

	public void onReport(BiConsumer<LocalDate, DayReport> listener) {
		reportListeners.add(listener);
	}

	// ---------- жизненный цикл игрока ----------

	/** Игрок вошёл по паролю. */
	public void track(ServerPlayer player) {
		UUID uuid = player.getUUID();
		if (!enabled() || stopping || trackers.containsKey(uuid) || players.optedOut(uuid)) {
			return;
		}
		long now = clock.millis();
		PlayerTracker t = new PlayerTracker(uuid, player.getScoreboardName(), now);
		t.baseline.putAll(player.getStats().stats);
		t.lifetime = lifetime(player);
		for (AdvancementHolder h : displayed(player.level().getServer())) {
			if (player.getAdvancements().getOrStartProgress(h).isDone()) {
				t.advancements.add(h.id());
			}
		}
		trackers.put(uuid, t);
	}

	/**
	 * Игрок вышел: последний снимок, итог сессии. Возвращает итог сессии текстом (для прощания)
	 * или null, если игрок не отслеживался.
	 */
	public String leave(ServerPlayer player) {
		PlayerTracker t = trackers.remove(player.getUUID());
		devPlayers.remove(player.getUUID());
		if (t == null) {
			return null;
		}
		long now = clock.millis();
		snapshot(player, t, now, currentDay, null, null);
		social.forget(t.uuid);
		store.pairs(currentDay, social.drain());
		JsonObject summary = SessionSummary.json(t.session, Math.max(0, (now - t.sessionStart) / 1000), t.sessionDeaths);
		store.session(t.uuid, t.sessionStart, now, t.session.getOrDefault(Keys.AFK, 0L), t.sessionDeaths, summary);
		lastSessions.put(t.uuid, summary);
		return SessionSummary.text(summary);
	}

	/** Игрок сказал /rick off: перестаём следить, незаписанное окно выбрасываем. */
	public void forget(UUID uuid) {
		trackers.remove(uuid);
		social.forget(uuid);
	}

	/** /rickadmin reload: летопись выключили — закрыть сессии; включили — начать следить за онлайном. */
	public void reconfigure(MinecraftServer server, java.util.function.Predicate<ServerPlayer> authenticated) {
		if (!enabled()) {
			for (UUID u : List.copyOf(trackers.keySet())) {
				ServerPlayer p = resolve(server, u);
				if (p != null) {
					leave(p);
				} else {
					trackers.remove(u);
				}
			}
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (authenticated.test(p)) {
				track(p);
			}
		}
	}

	public void stopping(MinecraftServer server) {
		for (UUID u : List.copyOf(trackers.keySet())) {
			ServerPlayer p = resolve(server, u);
			if (p != null) {
				leave(p);
			}
		}
		stopping = true;
	}

	// ---------- тик ----------

	/** Раз в секунду из главного потока. */
	public void everySecond(MinecraftServer server) {
		if (!enabled() || stopping) {
			return;
		}
		long now = clock.millis();
		applyPendingHomes();
		LocalDate day = today.get();
		if (!day.equals(currentDay)) {
			rollover(server, now, day);
		}
		RikoshetConfig cfg = config.get();
		if (now - lastSample >= cfg.chronicle().sampleSeconds() * 1000L) {
			sampleAll(server, now);
		}
		if (now - lastCycle >= cfg.chronicle().snapshotMinutes() * 60_000L) {
			cycleAll(server, now, currentDay);
		}
	}

	/** Полночь по timezone: закрыть день снимком и отдать его на анализ. */
	private void rollover(MinecraftServer server, long now, LocalDate newDay) {
		LocalDate old = currentDay;
		cycleAll(server, now, old);
		social.newDay();
		currentDay = newDay;
		log.info("[летопись] день {} закрыт, анализ", old);
		analyze(old).exceptionally(e -> {
			log.warn("[летопись] анализ {} не удался: {}", old, e.toString());
			return null;
		});
	}

	private void sampleAll(MinecraftServer server, long now) {
		long dt = Math.max(1, Math.min((now - lastSample) / 1000, 2L * config.get().chronicle().sampleSeconds()));
		lastSample = now;
		List<SocialTracker.Pos> positions = new ArrayList<>();
		for (PlayerTracker t : List.copyOf(trackers.values())) {
			if (players.optedOut(t.uuid)) {
				forget(t.uuid);
				continue;
			}
			ServerPlayer p = resolve(server, t.uuid);
			if (p == null) {
				continue;
			}
			sample(p, t, dt, now);
			positions.add(new SocialTracker.Pos(t.uuid, dimId(p.level()), p.getX(), p.getY(), p.getZ()));
		}
		for (SocialTracker.Event e : social.sample(positions, dt, now)) {
			emitSocial(e, now);
		}
	}

	private void sample(ServerPlayer p, PlayerTracker t, long dt, long now) {
		ServerLevel level = p.level();
		BlockPos pos = p.blockPosition();
		String dim = dimId(level);
		t.samples++;
		boolean still = t.hasLast && Math.abs(p.getX() - t.lx) < 0.3 && Math.abs(p.getY() - t.ly) < 0.3 && Math.abs(p.getZ() - t.lz) < 0.3
				&& Math.abs(p.getYRot() - t.lyaw) < 0.5f && Math.abs(p.getXRot() - t.lpitch) < 0.5f;
		if (still) {
			t.idle++;
			t.idleStreak++;
			if (t.idleStreak < IDLE_SAMPLES) {
				t.idleUncounted += dt;
			} else {
				add(t, Keys.AFK, t.idleUncounted + dt);
				t.idleUncounted = 0;
			}
		} else {
			t.idleStreak = 0;
			t.idleUncounted = 0;
		}
		t.hasLast = true;
		t.lx = p.getX();
		t.ly = p.getY();
		t.lz = p.getZ();
		t.lyaw = p.getYRot();
		t.lpitch = p.getXRot();

		add(t, Keys.DIM + dim, dt);
		String biome = Keys.shortId(level.getBiome(pos).getRegisteredName());
		add(t, Keys.BIOME + biome, dt);
		if (level.dimension() == Level.OVERWORLD && pos.getY() < level.getSeaLevel() && !level.canSeeSky(pos)) {
			t.underground++;
			add(t, Keys.UNDERGROUND, dt);
		}

		// В Верхнем мире начинают все — не новость
		if (!dim.equals("overworld") && firstFor(t.uuid, "dimension", dim, now)) {
			boolean server = serverFirst("dimension", dim, t.uuid);
			emitFirst(t.uuid, "dimension", dim, Names.dimension(dim), Milestones.dimensionScore(dim) + (server ? Milestones.SERVER_FIRST_BONUS * 2 : 0), server, now);
		}
		if (firstFor(t.uuid, "biome", biome, now)) {
			add(t, Keys.NEW_BIOMES, 1);
			if (serverFirst("biome", biome, t.uuid)) {
				emitFirst(t.uuid, "biome", biome, Names.pretty(biome), Milestones.SERVER_FIRST_BIOME, true, now);
			}
		}

		int cx = Math.floorDiv(pos.getX(), 64);
		int cz = Math.floorDiv(pos.getZ(), 64);
		long key = cellKey(dim, cx, cz);
		t.cellSeconds.addTo(key, dt);
		t.windowCells.addTo(key, 1);
		BuildHooks hooks = buildHooks;
		if (hooks != null) {
			hooks.presence(dim, cx, cz, now);
		}
		if (cells.computeIfAbsent(t.uuid, u -> new LongOpenHashSet()).add(key)) {
			t.newCells++;
			add(t, Keys.NEW_CELLS, 1);
			if (allCells.add(key)) {
				add(t, Keys.FIRST_CELLS, 1);
			}
		}
		if (++t.sampleNo % STRUCTURE_EVERY == 0) {
			structures(level, pos, t, now);
		}
	}

	private void structures(ServerLevel level, BlockPos pos, PlayerTracker t, long now) {
		StructureManager sm = level.structureManager();
		Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> all = sm.getAllStructuresAt(pos);
		if (all.isEmpty()) {
			return;
		}
		var reg = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		for (Structure s : all.keySet()) {
			if (!sm.getStructureWithPieceAt(pos, s).isValid()) {
				continue;
			}
			Identifier id = reg.getKey(s);
			if (id == null) {
				continue;
			}
			String sid = Keys.shortId(id.toString());
			if (firstFor(t.uuid, "structure", sid, now)) {
				boolean server = serverFirst("structure", sid, t.uuid);
				emitFirst(t.uuid, "structure", sid, Names.structure(sid), Milestones.structureScore(sid) + (server ? Milestones.SERVER_FIRST_BONUS : 0), server, now);
			}
		}
	}

	private void cycleAll(MinecraftServer server, long now, LocalDate day) {
		lastCycle = now;
		Map<UUID, Map<String, Long>> deltas = new HashMap<>();
		Map<UUID, Activity> acts = new HashMap<>();
		Map<UUID, Long> windows = new HashMap<>();
		for (PlayerTracker t : List.copyOf(trackers.values())) {
			ServerPlayer p = resolve(server, t.uuid);
			if (p == null) {
				continue;
			}
			snapshot(p, t, now, day, deltas, acts);
			windows.put(t.uuid, lastWindow);
		}
		for (SocialTracker.Event e : social.cycle(deltas, acts, windows)) {
			emitSocial(e, now);
		}
		store.pairs(day, social.drain());
	}

	private long lastWindow;

	/**
	 * Снимок статистики игрока: приросты с прошлого снимка, производные счётчики, занятие окна,
	 * вехи, находки, достижения. Пишет всё в БД одной пачкой.
	 */
	private void snapshot(ServerPlayer p, PlayerTracker t, long now, LocalDate day,
			Map<UUID, Map<String, Long>> deltasOut, Map<UUID, Activity> actsOut) {
		Object2IntMap<Stat<?>> cur = p.getStats().stats;
		Map<String, Long> d = new HashMap<>();
		Map<String, Long> life = new HashMap<>();
		for (Object2IntMap.Entry<Stat<?>> e : cur.object2IntEntrySet()) {
			Stat<?> stat = e.getKey();
			int v = e.getIntValue();
			StatKeys.Info info = keys.info(stat);
			lifetimeAdd(info, v, life);
			int old = t.baseline.getInt(stat);
			if (v > old && !info.has(StatKeys.SKIP)) {
				long dv = (long) v - old;
				d.merge(info.key(), dv, Long::sum);
				StatKeys.derive(info, dv, d);
			}
			if (v != old) {
				t.baseline.put(stat, v);
			}
		}
		StatKeys.finish(d);
		StatKeys.finish(life);
		life.keySet().retainAll(Milestones.LIFETIME.keySet());

		long window = Math.max(0, (now - t.windowStart) / 1000);
		lastWindow = window;
		Activity act = ActivityClassifier.classify(d, new ActivityClassifier.Window(window, t.samples, t.idle, t.underground, t.newCells));
		if (window > 0) {
			d.merge(Keys.ONLINE, window, Long::sum);
			d.merge(act.key(), window, Long::sum);
		}
		t.pending.forEach((k, v) -> d.merge(k, v, Long::sum));
		t.pending.clear();

		milestones(t, life, now, day);
		items(t, d, now, day);
		bosses(t, d, now, day);
		long totems = d.getOrDefault(Keys.TOTEMS, 0L);
		if (totems > 0) {
			JsonObject data = new JsonObject();
			data.addProperty("count", totems);
			emit(new ChronicleEvent(now, day.toString(), t.uuid, ChronicleEvent.TOTEM, "totem_of_undying", 20, data));
		}
		advancements(p, t, now, day);

		stats.add(t.uuid, day, d);
		if (!life.equals(t.lifetime)) {
			store.totals(t.uuid, life, now);
		}
		t.lifetime = life;
		flushCells(t, now);
		d.forEach((k, v) -> t.session.merge(k, v, Long::sum));
		traceBuild(t, d, now);
		t.resetWindow(now);
		if (deltasOut != null) {
			deltasOut.put(t.uuid, d);
			actsOut.put(t.uuid, act);
		}
	}

	/** След стройки: окно — в клетке, где было больше всего замеров. */
	private void traceBuild(PlayerTracker t, Map<String, Long> d, long now) {
		BuildHooks hooks = buildHooks;
		if (hooks == null || t.windowCells.isEmpty()) {
			return;
		}
		long placed = d.getOrDefault(Keys.PLACED, 0L);
		long mined = d.getOrDefault(Keys.MINED, 0L) - d.getOrDefault(Keys.LOGS, 0L) - d.getOrDefault(Keys.CROPS, 0L);
		if (placed <= 0 && mined <= 0) {
			return;
		}
		long best = 0;
		int bestN = -1;
		for (var e : t.windowCells.long2IntEntrySet()) {
			if (e.getIntValue() > bestN) {
				bestN = e.getIntValue();
				best = e.getLongKey();
			}
		}
		hooks.window(t.uuid, dims.get((int) (best >>> 48)), (int) ((best >>> 24) & 0xFFFFFF) << 8 >> 8,
				(int) (best & 0xFFFFFF) << 8 >> 8, placed, Math.max(0, mined), now);
	}

	/** Счётчики за всё время для вех: из тех же ванильных значений. */
	private static void lifetimeAdd(StatKeys.Info info, long v, Map<String, Long> life) {
		if (Milestones.LIFETIME.containsKey(info.key())) {
			life.merge(info.key(), v, Long::sum);
		}
		if (info.key().equals("custom:play_time")) {
			life.put(Keys.PLAY_HOURS, v / 72_000);
		}
		StatKeys.derive(info, v, life);
	}

	private Map<String, Long> lifetime(ServerPlayer p) {
		Map<String, Long> life = new HashMap<>();
		for (Object2IntMap.Entry<Stat<?>> e : p.getStats().stats.object2IntEntrySet()) {
			lifetimeAdd(keys.info(e.getKey()), e.getIntValue(), life);
		}
		StatKeys.finish(life);
		life.keySet().retainAll(Milestones.LIFETIME.keySet());
		return life;
	}

	private void milestones(PlayerTracker t, Map<String, Long> life, long now, LocalDate day) {
		if (t.lifetime == null) {
			return;
		}
		for (var m : Milestones.LIFETIME.entrySet()) {
			long old = t.lifetime.getOrDefault(m.getKey(), 0L);
			long cur = life.getOrDefault(m.getKey(), 0L);
			int i = Milestones.crossed(m.getValue(), old, cur);
			if (i >= 0) {
				JsonObject data = new JsonObject();
				data.addProperty("key", m.getKey());
				data.addProperty("threshold", m.getValue()[i]);
				data.addProperty("total", cur);
				emit(new ChronicleEvent(now, day.toString(), t.uuid, ChronicleEvent.MILESTONE, m.getKey(), Milestones.lifetimeScore(i), data));
			}
		}
	}

	private void items(PlayerTracker t, Map<String, Long> d, long now, LocalDate day) {
		for (String k : List.copyOf(d.keySet())) {
			int colon = k.indexOf(':');
			if (colon < 0) {
				continue;
			}
			String type = k.substring(0, colon);
			if (!type.equals("picked_up") && !type.equals("crafted")) {
				continue;
			}
			String item = k.substring(colon + 1);
			String base = item.endsWith("shulker_box") ? "shulker_box" : item;
			Integer score = Milestones.ITEMS.get(base);
			if (score != null && firstFor(t.uuid, "item", base, now)) {
				boolean server = serverFirst("item", base, t.uuid);
				emitFirst(t.uuid, "item", base, Names.item(base), score + (server ? Milestones.SERVER_FIRST_BONUS : 0), server, now);
			}
		}
	}

	private void bosses(PlayerTracker t, Map<String, Long> d, long now, LocalDate day) {
		for (var b : Milestones.BOSSES.entrySet()) {
			long n = d.getOrDefault("killed:" + b.getKey(), 0L);
			if (n <= 0) {
				continue;
			}
			firstFor(t.uuid, "boss", b.getKey(), now);
			boolean server = serverFirst("boss", b.getKey(), t.uuid);
			JsonObject data = new JsonObject();
			data.addProperty("entity", b.getKey());
			data.addProperty("count", n);
			data.addProperty("server_first", server);
			emit(new ChronicleEvent(now, day.toString(), t.uuid, ChronicleEvent.BOSS, b.getKey(),
					b.getValue() + (server ? Milestones.SERVER_FIRST_BONUS : 0), data));
		}
	}

	private void advancements(ServerPlayer p, PlayerTracker t, long now, LocalDate day) {
		for (AdvancementHolder h : displayed(p.level().getServer())) {
			if (t.advancements.contains(h.id()) || !p.getAdvancements().getOrStartProgress(h).isDone()) {
				continue;
			}
			t.advancements.add(h.id());
			String id = h.id().toString();
			firstFor(t.uuid, "advancement", id, now);
			boolean server = serverFirst("advancement", id, t.uuid);
			DisplayInfo disp = h.value().display().orElseThrow();
			String frame = disp.getType().name().toLowerCase(Locale.ROOT);
			int score = switch (frame) {
				case "challenge" -> 30;
				case "goal" -> 12;
				default -> 4;
			} + (server ? Milestones.SERVER_FIRST_BONUS : 0);
			JsonObject data = new JsonObject();
			data.addProperty("id", id);
			data.addProperty("title", disp.getTitle().getString());
			data.addProperty("frame", frame);
			data.addProperty("server_first", server);
			emit(new ChronicleEvent(now, day.toString(), t.uuid, ChronicleEvent.ADVANCEMENT, id, score, data));
		}
	}

	/** Достижения с отображением: рецепты и служебные — мимо. */
	private static List<AdvancementHolder> displayed(MinecraftServer server) {
		List<AdvancementHolder> out = new ArrayList<>();
		for (AdvancementHolder h : server.getAdvancements().getAllAdvancements()) {
			if (h.value().display().isPresent()) {
				out.add(h);
			}
		}
		return out;
	}

	// ---------- события мира ----------

	/** Смертельный удар по игроку, до тотема и выпадения вещей. */
	public void beforeDeath(ServerPlayer p) {
		PlayerTracker t = trackers.get(p.getUUID());
		if (t == null) {
			return;
		}
		t.secondsSinceDeath = p.getStats().getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_DEATH)) / 20L;
		boolean keep = p.level().getGameRules().get(GameRules.KEEP_INVENTORY);
		t.pendingLoss = keep ? null : loss(p);
		t.pendingLossTick = p.level().getServer().getTickCount();
	}

	private static PlayerTracker.DeathLoss loss(ServerPlayer p) {
		int diamonds = 0;
		int netherite = 0;
		int enchanted = 0;
		int elytra = 0;
		int totems = 0;
		int shulkers = 0;
		Set<String> items = new HashSet<>();
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			String id = Keys.shortId(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
			boolean valuable = true;
			if (id.equals("diamond")) {
				diamonds += s.getCount();
			} else if (id.equals("diamond_block")) {
				diamonds += 9 * s.getCount();
			} else if (id.contains("netherite")) {
				netherite += s.getCount();
			} else if (id.equals("elytra")) {
				elytra++;
			} else if (id.equals("totem_of_undying")) {
				totems += s.getCount();
			} else if (id.endsWith("shulker_box")) {
				shulkers++;
			} else if (!s.isEnchanted()) {
				valuable = false;
			}
			if (s.isEnchanted()) {
				enchanted++;
			}
			if (valuable) {
				items.add(id);
			}
		}
		int value = diamonds + 8 * netherite + 2 * enchanted + 10 * elytra + 5 * totems + 15 * shulkers;
		return new PlayerTracker.DeathLoss(diamonds, netherite, enchanted, elytra, totems, shulkers, value, items);
	}

	/** Игрок умер (AFTER_DEATH). */
	public void death(ServerPlayer p, DamageSource source) {
		PlayerTracker t = trackers.get(p.getUUID());
		if (t == null) {
			return;
		}
		long now = clock.millis();
		t.sessionDeaths++;
		ServerLevel level = p.level();
		BlockPos pos = p.blockPosition();
		String typeId = source.typeHolder().getRegisteredName();
		DeathCauses.Cause cause = DeathCauses.of(typeId);
		JsonObject data = new JsonObject();
		data.addProperty("cause", cause.text());
		data.addProperty("group", cause.group());
		data.addProperty("damage_type", typeId);
		Entity killer = source.getEntity();
		UUID killerPlayer = null;
		UUID killerMob = null;
		int score = 3;
		if (killer instanceof ServerPlayer kp && kp != p) {
			killerPlayer = kp.getUUID();
			data.addProperty("killer_uuid", killerPlayer.toString());
			score += 25;
		} else if (killer != null && killer != p) {
			String type = EntityType.getKey(killer.getType()).toString();
			String name = DeathCauses.mob(type);
			data.addProperty("killer_type", Keys.shortId(type));
			if (killer.hasCustomName()) {
				name = name + " по имени «" + killer.getCustomName().getString() + "»";
				score += 10;
			}
			data.addProperty("killer", name);
			killerMob = killer.getUUID();
		}
		data.addProperty("dim", dimId(level));
		data.addProperty("biome", Keys.shortId(level.getBiome(pos).getRegisteredName()));
		data.addProperty("y", pos.getY());
		PlayerTracker.DeathLoss loss = t.pendingLossTick == level.getServer().getTickCount() ? t.pendingLoss : null;
		boolean keep = level.getGameRules().get(GameRules.KEEP_INVENTORY);
		data.addProperty("keep_inventory", keep);
		if (loss != null && loss.value() > 0) {
			data.addProperty("diamonds", loss.diamonds());
			data.addProperty("netherite", loss.netherite());
			data.addProperty("enchanted", loss.enchanted());
			data.addProperty("elytra", loss.elytra());
			data.addProperty("totems", loss.totems());
			data.addProperty("shulkers", loss.shulkers());
			data.addProperty("lost_value", loss.value());
			if (loss.big()) {
				score += Math.min(30, loss.value() / 2);
			}
		}
		if (t.secondsSinceDeath > 0) {
			data.addProperty("since_respawn", t.secondsSinceDeath);
			if (t.secondsSinceDeath < 60) {
				score += 10;
			}
		}
		List<UUID> witnesses = new ArrayList<>();
		for (ServerPlayer o : level.players()) {
			if (o != p && trackers.containsKey(o.getUUID()) && o.distanceToSqr(p) <= NEAR * NEAR) {
				witnesses.add(o.getUUID());
			}
		}
		emit(new ChronicleEvent(now, currentDay.toString(), t.uuid, ChronicleEvent.DEATH, cause.group(), score, data));
		social.playerDeath(t.uuid, killerPlayer != null && trackers.containsKey(killerPlayer) ? killerPlayer : null, killerMob,
				witnesses, new SocialTracker.Pos(t.uuid, dimId(level), p.getX(), p.getY(), p.getZ()),
				loss == null ? Set.of() : loss.items(), now);
		t.pendingLoss = null;
		t.pendingLossTick = -1;
	}

	/** Умер не игрок (AFTER_DEATH): питомцы, именные мобы, «спас» и «отомстил». Дёшево для обычных мобов. */
	public void entityDeath(LivingEntity entity, DamageSource source) {
		if (trackers.isEmpty() || entity instanceof ServerPlayer) {
			return;
		}
		Entity k = source.getEntity();
		ServerPlayer killer = k instanceof ServerPlayer sp && trackers.containsKey(sp.getUUID()) ? sp : null;
		long now = clock.millis();
		if (entity instanceof OwnableEntity own && own.getOwnerReference() != null) {
			UUID owner = own.getOwnerReference().getUUID();
			PlayerRecord rec = players.get(owner);
			if (rec != null && !rec.aiOptOut()) {
				JsonObject data = new JsonObject();
				data.addProperty("owner", owner.toString());
				data.addProperty("pet", DeathCauses.mob(EntityType.getKey(entity.getType()).toString()));
				if (entity.hasCustomName()) {
					data.addProperty("name", entity.getCustomName().getString());
				}
				killerData(data, k);
				boolean byPlayer = killer != null && !killer.getUUID().equals(owner);
				emit(new ChronicleEvent(now, currentDay.toString(), owner, ChronicleEvent.PET_DEATH,
						Keys.shortId(EntityType.getKey(entity.getType()).toString()), byPlayer ? 35 : 15, data));
				if (byPlayer) {
					social.petKilled(killer.getUUID(), owner);
				}
			}
		} else if (entity.hasCustomName()) {
			JsonObject data = new JsonObject();
			data.addProperty("mob", DeathCauses.mob(EntityType.getKey(entity.getType()).toString()));
			data.addProperty("name", entity.getCustomName().getString());
			killerData(data, k);
			emit(new ChronicleEvent(now, currentDay.toString(), killer == null ? null : killer.getUUID(), ChronicleEvent.NAMED_DEATH,
					Keys.shortId(EntityType.getKey(entity.getType()).toString()), 12, data));
		}
		if (killer != null) {
			UUID target = null;
			boolean near = false;
			float health = 1;
			if (entity instanceof Mob m && m.getTarget() instanceof ServerPlayer tp && trackers.containsKey(tp.getUUID())) {
				target = tp.getUUID();
				near = tp.distanceToSqr(m) <= 16 * 16;
				health = tp.getHealth() / Math.max(1, tp.getMaxHealth());
			}
			for (SocialTracker.Event e : social.mobKilled(entity.getUUID(), killer.getUUID(), target, near, health, now)) {
				emitSocial(e, now);
			}
		}
	}

	private void killerData(JsonObject data, Entity k) {
		if (k instanceof ServerPlayer sp) {
			data.addProperty("killer_uuid", sp.getUUID().toString());
		} else if (k != null) {
			data.addProperty("killer", DeathCauses.mob(EntityType.getKey(k.getType()).toString()));
		}
	}

	/** Урон игрока игроку. */
	public void damage(LivingEntity victim, DamageSource source, float amount) {
		if (victim instanceof ServerPlayer v && source.getEntity() instanceof ServerPlayer a
				&& trackers.containsKey(v.getUUID()) && trackers.containsKey(a.getUUID())) {
			social.pvpDamage(a.getUUID(), v.getUUID(), amount);
		}
	}

	/** Сообщение в чате: только счётчики, текст нигде не хранится. */
	public void chat(ServerPlayer p, String text) {
		PlayerTracker t = trackers.get(p.getUUID());
		if (t == null) {
			return;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		add(t, Keys.CHAT, 1);
		if (lower.contains("рик") || lower.contains("rick")) {
			add(t, Keys.CHAT_RICK, 1);
		}
		Map<UUID, List<String>> names = new HashMap<>();
		for (PlayerTracker o : trackers.values()) {
			List<String> n = new ArrayList<>();
			n.add(o.name.toLowerCase(Locale.ROOT));
			Role r = roles.get(o.uuid);
			if (r != null && r.title().contains(" ")) {
				n.add(r.title().toLowerCase(Locale.ROOT));
			}
			names.put(o.uuid, n);
		}
		social.chat(t.uuid, lower, names, clock.millis());
	}

	// ---------- анализ ----------

	/** Проанализировать день: снимок из БД, итог — в chronicle_day и профили, слушателям — в главном потоке. */
	public CompletableFuture<DayReport> analyze(LocalDate day) {
		Map<UUID, DayData.Person> people = people();
		// Для аналитика газеты — с запасом; редакция без брифа берёт первые max_facts
		int maxFacts = Math.max(ANALYST_FACTS, config.get().newspaper().maxFacts());
		return store.dayData(day, config.get().timezone(), people)
				.thenApply(d -> Analyzer.analyze(d, maxFacts))
				.thenApply(r -> {
					long now = clock.millis();
					store.report(day, GSON.toJson(r), now);
					Map<UUID, JsonObject> prof = new HashMap<>();
					for (DayReport.Profile p : r.profiles()) {
						prof.put(UUID.fromString(p.uuid()), GSON.toJsonTree(p).getAsJsonObject());
					}
					store.profiles(day, prof);
					profiles.putAll(prof);
					reportListeners.forEach(l -> {
						try {
							l.accept(day, r);
						} catch (RuntimeException e) {
							log.warn("[летопись] обработчик итогов дня: {}", e.toString());
						}
					});
					return r;
				})
				.whenComplete((r, e) -> store.cells().thenAccept(this::setHomesAsync));
	}

	private volatile List<ChronicleStore.CellRow> pendingHomes;

	private void setHomesAsync(List<ChronicleStore.CellRow> rows) {
		pendingHomes = rows;
	}

	/** Итоги дня из БД (или null). */
	public CompletableFuture<DayReport> report(LocalDate day) {
		return store.report(day).thenApply(s -> s == null ? null : GSON.fromJson(s, DayReport.class));
	}

	public CompletableFuture<Boolean> hasData(LocalDate day) {
		return store.hasData(day);
	}

	/** Игроки для анализа: ник, роль, /rick off, первый вход. Главный поток. */
	public Map<UUID, DayData.Person> people() {
		Map<UUID, DayData.Person> out = new HashMap<>();
		for (var e : players.nameMap().entrySet()) {
			PlayerRecord rec = players.get(e.getKey());
			Role r = roles.get(e.getKey());
			out.put(e.getKey(), new DayData.Person(e.getValue(), r == null ? null : r.title(), rec != null && rec.aiOptOut(),
					rec == null ? 0 : rec.firstSeen()));
		}
		return out;
	}

	// ---------- чтение для других модулей ----------

	/** Итог прошлой сессии текстом или null. */
	public String lastSession(UUID uuid) {
		return SessionSummary.text(lastSessions.get(uuid));
	}

	/** Итог текущей сессии текстом или null. */
	public String currentSession(UUID uuid, long now) {
		PlayerTracker t = trackers.get(uuid);
		if (t == null) {
			return null;
		}
		return SessionSummary.text(SessionSummary.json(t.session, Math.max(0, (now - t.sessionStart) / 1000), t.sessionDeaths));
	}

	public JsonObject profile(UUID uuid) {
		return profiles.get(uuid);
	}

	public Map<UUID, SocialTracker.Home> homes() {
		return social.homes();
	}

	public boolean tracked(UUID uuid) {
		return trackers.containsKey(uuid);
	}

	/** Счётчики за всё время: живые — у отслеживаемых, иначе из БД на момент старта. */
	public Map<String, Long> totals(UUID uuid) {
		PlayerTracker t = trackers.get(uuid);
		if (t != null && t.lifetime != null) {
			return t.lifetime;
		}
		return totalsAtStart.getOrDefault(uuid, Map.of());
	}

	// ---------- отладка ----------

	/** /rickdev: следить за фейковым игроком, которого нет в списке игроков. */
	public void devTrack(ServerPlayer fake) {
		devPlayers.put(fake.getUUID(), fake);
		track(fake);
	}

	/** /rickdev chronicle snapshot: снимок всех сейчас. */
	public void devCycle(MinecraftServer server) {
		long now = clock.millis();
		sampleAll(server, now);
		cycleAll(server, now, currentDay);
	}

	// ---------- внутреннее ----------

	private ServerPlayer resolve(MinecraftServer server, UUID uuid) {
		ServerPlayer p = server.getPlayerList().getPlayer(uuid);
		return p != null ? p : devPlayers.get(uuid);
	}

	private void add(PlayerTracker t, String key, long v) {
		t.pending.merge(key, v, Long::sum);
	}

	private boolean firstFor(UUID uuid, String kind, String id, long now) {
		if (!seen.computeIfAbsent(uuid, u -> new HashSet<>()).add(ChronicleStore.seenKey(kind, id))) {
			return false;
		}
		store.seen(uuid, kind, id, now);
		return true;
	}

	private boolean serverFirst(String kind, String id, UUID uuid) {
		return firsts.putIfAbsent(ChronicleStore.seenKey(kind, id), uuid) == null;
	}

	private void emitFirst(UUID uuid, String kind, String id, String name, int score, boolean server, long now) {
		JsonObject data = new JsonObject();
		data.addProperty("kind", kind);
		data.addProperty("id", id);
		data.addProperty("name", name);
		data.addProperty("server_first", server);
		emit(new ChronicleEvent(now, currentDay.toString(), uuid, ChronicleEvent.FIRST, kind + ":" + id, score, data));
	}

	private void emitSocial(SocialTracker.Event e, long now) {
		JsonObject data = e.data() == null ? new JsonObject() : e.data().deepCopy();
		if (e.target() != null) {
			data.addProperty("target", e.target().toString());
		}
		emit(new ChronicleEvent(now, currentDay.toString(), e.actor(), e.type(), e.subject(), e.score(), data));
	}

	private void emit(ChronicleEvent e) {
		store.event(e);
		for (Consumer<ChronicleEvent> l : eventListeners) {
			try {
				l.accept(e);
			} catch (RuntimeException ex) {
				log.warn("[летопись] обработчик события: {}", ex.toString());
			}
		}
	}

	private void flushCells(PlayerTracker t, long now) {
		if (t.cellSeconds.isEmpty()) {
			return;
		}
		List<ChronicleStore.CellDelta> out = new ArrayList<>();
		for (var e : t.cellSeconds.long2LongEntrySet()) {
			long k = e.getLongKey();
			out.add(new ChronicleStore.CellDelta(dims.get((int) (k >>> 48)), (int) ((k >>> 24) & 0xFFFFFF) << 8 >> 8,
					(int) (k & 0xFFFFFF) << 8 >> 8, e.getLongValue()));
		}
		t.cellSeconds.clear();
		store.cells(t.uuid, out, now);
	}

	private long cellKey(String dim, int cx, int cz) {
		int i = dimIndex.computeIfAbsent(dim, k -> {
			dims.add(k);
			return dims.size() - 1;
		});
		return ((long) i << 48) | ((long) (cx & 0xFFFFFF) << 24) | (cz & 0xFFFFFF);
	}

	private void setHomes(List<ChronicleStore.CellRow> rows) {
		Map<UUID, SocialTracker.Home> homes = new HashMap<>();
		ChronicleStore.homes(rows, HOME_MIN_SECONDS).forEach((u, r) -> homes.put(u, new SocialTracker.Home(r.dim(), r.cx(), r.cz())));
		social.setHomes(homes);
	}

	/** Дома пересчитываются после ночного анализа; применяем в главном потоке. */
	public void applyPendingHomes() {
		List<ChronicleStore.CellRow> rows = pendingHomes;
		if (rows != null) {
			pendingHomes = null;
			setHomes(rows);
		}
	}

	static String dimId(Level level) {
		return Keys.shortId(level.dimension().identifier().toString());
	}

	/** Для тестов и команд: последняя дата, которую ведёт летопись. */
	public LocalDate currentDay() {
		return currentDay;
	}

	/** Как назвать игрока в тексте для других: «Роль (ник)»; с /rick off — обезличенно. */
	public String whoPublic(UUID uuid) {
		PlayerRecord rec = players.get(uuid);
		if (rec == null) {
			return "неизвестный игрок";
		}
		if (rec.aiOptOut()) {
			return "кто-то из игроков";
		}
		Role r = roles.get(uuid);
		return r == null ? rec.name() : r.title() + " (" + rec.name() + ")";
	}

	/** Событие не от сбора, а от других модулей (дневник дня): в БД и слушателям. Главный поток. */
	public void record(ChronicleEvent e) {
		emit(e);
	}

	/** Кого летопись сейчас ведёт: ники. */
	public List<String> trackedNames() {
		return trackers.values().stream().map(t -> t.name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
	}
}
