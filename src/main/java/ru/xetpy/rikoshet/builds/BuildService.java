package ru.xetpy.rikoshet.builds;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.chronicle.ChronicleEvent;
import ru.xetpy.rikoshet.chronicle.Keys;
import ru.xetpy.rikoshet.chronicle.Names;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Анализ построек (docs/design/builds.md): следы стройки от летописи, скан палитр секций по
 * одному чанку в секунду, рост и потери, имена от аналитика. Только главный поток.
 */
public final class BuildService {
	/** Столько поставил или добыл за окно в клетке — след стройки. */
	static final long TRACE_MIN = 64;
	/** Сканируем клетку не чаще. */
	static final long SCAN_EVERY_MS = TimeUnit.MINUTES.toMillis(30);
	/** Столько рукотворных — уже постройка, её пересканируем, когда рядом кто-то есть. */
	static final long KNOWN_MIN = 100;
	/** Упало на столько — потеря. */
	static final double LOSS = 0.3;
	static final int QUEUE = 8;

	private record Job(Site site, ServerLevel level, ScanTally tally, int[] next, String biome, boolean structure) {
	}

	private final Logger log;
	private final Clock clock;
	private final Supplier<LocalDate> today;
	private final BuildStore store;
	private final BlockKinds kinds = new BlockKinds();
	private final Map<String, Site> sites = new HashMap<>();
	private final Deque<Site> queue = new ArrayDeque<>();
	private final Consumer<ChronicleEvent> events;
	private final Function<UUID, String> who;
	private final Consumer<String> alert;
	private Job job;

	public BuildService(Logger log, Clock clock, Supplier<LocalDate> today, BuildStore store, Consumer<ChronicleEvent> events,
			Function<UUID, String> who, Consumer<String> alert) throws SQLException {
		this.log = log;
		this.clock = clock;
		this.today = today;
		this.store = store;
		this.events = events;
		this.who = who;
		this.alert = alert;
		for (Site s : store.load()) {
			sites.put(s.key(), s);
		}
	}

	// ---------- следы ----------

	/** Окно летописи: игрок провёл его в клетке и поставил или добыл там столько. */
	public void window(UUID uuid, String dim, int cx, int cz, long placed, long mined, long now) {
		if (placed < TRACE_MIN && mined < TRACE_MIN) {
			return;
		}
		Site s = sites.computeIfAbsent(Site.key(dim, cx, cz), k -> new Site(dim, cx, cz, now));
		if (placed > 0) {
			s.placed += placed;
			s.builders.merge(uuid, placed, Long::sum);
			s.buildTs = now;
			s.recomputeOwner();
		}
		store.save(s, uuid, placed, mined, now);
		if (placed >= TRACE_MIN || s.artificial >= KNOWN_MIN) {
			request(s, now);
		}
	}

	/** Замер: игрок в клетке. Известную постройку пора пересканировать — заодно заметим потери. */
	public void presence(String dim, int cx, int cz, long now) {
		Site s = sites.get(Site.key(dim, cx, cz));
		if (s != null && s.artificial >= KNOWN_MIN) {
			request(s, now);
		}
	}

	private void request(Site s, long now) {
		if (now - s.scanTs < SCAN_EVERY_MS || queue.contains(s) || job != null && job.site() == s || queue.size() >= QUEUE) {
			return;
		}
		queue.addLast(s);
	}

	// ---------- скан ----------

	/** Раз в секунду: один чанк текущего скана. */
	public void step(MinecraftServer server) {
		if (job == null) {
			Site s = queue.pollFirst();
			if (s == null) {
				return;
			}
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
					Identifier.parse(s.dim.contains(":") ? s.dim : "minecraft:" + s.dim)));
			if (level == null || !loaded(level, s)) {
				return; // игроки ушли, чанки выгружены — просканируем в другой раз
			}
			BlockPos center = new BlockPos(s.cx * 64 + 32, 0, s.cz * 64 + 32);
			int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, center.getX(), center.getZ());
			BlockPos at = center.atY(y);
			job = new Job(s, level, new ScanTally(), new int[] {0}, Keys.shortId(level.getBiome(at).getRegisteredName()),
					insideStructure(level, at));
		}
		int i = job.next()[0];
		LevelChunk chunk = job.level().getChunkSource().getChunkNow(job.site().cx * 4 + i % 4, job.site().cz * 4 + i / 4);
		if (chunk == null) {
			job = null;
			return;
		}
		LevelChunkSection[] sections = chunk.getSections();
		for (int k = 0; k < sections.length; k++) {
			LevelChunkSection sec = sections[k];
			if (sec.hasOnlyAir()) {
				continue;
			}
			int sy = chunk.getSectionYFromSectionIndex(k);
			sec.getStates().count((state, n) -> {
				BlockKinds.Kind kind = kinds.of(state);
				if (kind.artificial()) {
					job.tally().add(kind, Keys.shortId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()), n, sy);
				}
			});
		}
		job.next()[0] = i + 1;
		if (i + 1 >= 16) {
			finish(job);
			job = null;
		}
	}

	/**
	 * Центр постройки у поверхности — внутри куска структуры (дом деревни, крепость)? Ссылки на
	 * структуры в чанке не годятся: шахты под землёй почти везде.
	 */
	private static boolean insideStructure(ServerLevel level, BlockPos at) {
		var sm = level.structureManager();
		for (var st : sm.getAllStructuresAt(at).keySet()) {
			if (sm.getStructureWithPieceAt(at, st).isValid()) {
				return true;
			}
		}
		return false;
	}

	private static boolean loaded(ServerLevel level, Site s) {
		for (int i = 0; i < 16; i++) {
			if (level.getChunkSource().getChunkNow(s.cx * 4 + i % 4, s.cz * 4 + i / 4) == null) {
				return false;
			}
		}
		return true;
	}

	private void finish(Job j) {
		Site s = j.site();
		long now = clock.millis();
		long prev = s.artificial;
		boolean first = s.scanTs == 0;
		s.artificial = j.tally().artificial;
		s.scanTs = now;
		if (s.baseline == null) {
			s.baseline = s.artificial;
		}
		s.scan = j.tally().json(j.biome(), j.structure());
		String day = today.get().toString();
		s.history.put(day, s.artificial);
		while (s.history.size() > 30) {
			s.history.pollFirstEntry();
		}
		store.save(s, null, 0, 0, now);
		if (!first && prev >= 300 && s.artificial <= prev * (1 - LOSS) && s.owner != null) {
			long pct = Math.round(100.0 * (prev - s.artificial) / prev);
			JsonObject d = new JsonObject();
			d.addProperty("before", prev);
			d.addProperty("after", s.artificial);
			d.addProperty("percent", pct);
			d.addProperty("where", where(s));
			if (s.name != null) {
				d.addProperty("name", s.name);
			}
			// Эпизод для памяти; факт для газеты — из итогов дня, с подозреваемыми
			events.accept(new ChronicleEvent(now, day, s.owner, "build_loss", s.key(), 7, d));
			String msg = "постройка " + (s.name != null ? "«" + s.name + "» " : "") + who.apply(s.owner) + " (" + s.dim + " "
					+ (s.cx * 64 + 32) + " " + (s.cz * 64 + 32) + ") потеряла " + pct + "% рукотворных блоков: " + prev + " → " + s.artificial;
			log.warn("[постройки] {}", msg);
			alert.accept(msg);
		}
	}

	/** «мангровое болото» или «Незер, crimson forest». */
	static String where(Site s) {
		String biome = s.scan != null && s.scan.has("biome") ? Names.pretty(s.scan.get("biome").getAsString()) : "?";
		return s.dim.equals("overworld") ? biome : Names.dimension(s.dim) + ", " + biome;
	}

	// ---------- имена ----------

	/** Постройки, которым пора дать или обновить имя: от 300 блоков, выросли на 30% с прошлого имени. */
	public List<Site> needNames(int max) {
		List<Site> out = new ArrayList<>();
		for (Site s : sites.values()) {
			if (s.artificial < 300 || s.owner == null || s.scan == null || s.scan.get("structure").getAsBoolean()) {
				continue;
			}
			if (s.namedAt == null || Math.abs(s.artificial - s.namedAt) >= Math.max(300, s.namedAt * 0.3)) {
				out.add(s);
			}
		}
		out.sort(Comparator.comparingLong((Site x) -> x.artificial).reversed());
		return out.subList(0, Math.min(max, out.size()));
	}

	/** Вход аналитика: постройки под номерами с материалами и видами. */
	public String namingInput(List<Site> list) {
		StringBuilder sb = new StringBuilder();
		int i = 1;
		for (Site s : list) {
			sb.append(i++).append(". Хозяин: ").append(who.apply(s.owner)).append("; где: ").append(where(s))
					.append("; рукотворных блоков: ").append(s.artificial);
			JsonObject sc = s.scan;
			if (sc.has("y_min")) {
				int span = sc.get("y_max").getAsInt() - sc.get("y_min").getAsInt() + 1;
				sb.append("; по высоте занимает до ").append(span).append(" блоков (точность — 16)");
			}
			List<String> kinds = new ArrayList<>();
			for (var e : sc.getAsJsonObject("kinds").entrySet()) {
				String ru = kindRu(e.getKey());
				kinds.add(ru + " " + e.getValue().getAsLong());
			}
			sb.append("; по видам: ").append(String.join(", ", kinds));
			List<String> mats = new ArrayList<>();
			for (JsonElement m : sc.getAsJsonArray("materials")) {
				mats.add(Names.pretty(m.getAsJsonArray().get(0).getAsString()) + " " + m.getAsJsonArray().get(1).getAsLong());
			}
			sb.append("; материалы: ").append(String.join(", ", mats));
			if (s.name != null) {
				sb.append("; прежнее имя: «").append(s.name).append('»');
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	private static String kindRu(String id) {
		for (BlockKinds.Kind k : BlockKinds.Kind.values()) {
			if (k.id().equals(id)) {
				return k.ru;
			}
		}
		return id;
	}

	/** Ответ аналитика: имя и описание по номерам. Возвращает, скольким дали имя. */
	public int applyNames(List<Site> list, JsonObject v, Consumer<Site> named) {
		int n = 0;
		if (v == null || !v.has("builds") || !v.get("builds").isJsonArray()) {
			return 0;
		}
		for (JsonElement el : v.getAsJsonArray("builds")) {
			JsonObject o = el.isJsonObject() ? el.getAsJsonObject() : null;
			if (o == null || !o.has("n") || !o.has("name")) {
				continue;
			}
			int i = o.get("n").getAsInt();
			if (i < 1 || i > list.size()) {
				continue;
			}
			Site s = list.get(i - 1);
			String name = clip(o.get("name").getAsString(), 40);
			if (name.isBlank()) {
				continue;
			}
			s.name = name;
			s.description = o.has("description") ? clip(o.get("description").getAsString(), 160) : null;
			s.namedAt = s.artificial;
			store.save(s, null, 0, 0, clock.millis());
			named.accept(s);
			n++;
		}
		return n;
	}

	private static String clip(String s, int max) {
		String t = s.replace('\n', ' ').strip();
		return t.length() <= max ? t : t.substring(0, max - 1) + "…";
	}

	// ---------- чтение ----------

	public List<Site> all() {
		List<Site> out = new ArrayList<>(sites.values());
		out.sort(Comparator.comparingLong((Site x) -> x.artificial).reversed());
		return out;
	}

	/** Главная постройка игрока — самая большая, где он хозяин. */
	public Site mainOf(UUID owner) {
		return sites.values().stream().filter(s -> owner.equals(s.owner) && s.artificial > 0)
				.max(Comparator.comparingLong(s -> s.artificial)).orElse(null);
	}

	/** /rickadmin builds scan: поставить клетку в очередь сейчас. */
	public boolean scanNow(String dim, int cx, int cz) {
		Site s = sites.computeIfAbsent(Site.key(dim, cx, cz), k -> new Site(dim, cx, cz, clock.millis()));
		s.scanTs = Math.min(s.scanTs, clock.millis() - SCAN_EVERY_MS);
		if (queue.contains(s)) {
			return true;
		}
		queue.addFirst(s);
		return true;
	}

	public int queued() {
		return queue.size() + (job == null ? 0 : 1);
	}

	static String dimOf(Level level) {
		return Keys.shortId(level.dimension().identifier().toString());
	}
}
