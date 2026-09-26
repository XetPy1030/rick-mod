package ru.xetpy.rikoshet.chronicle.social;

import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.chronicle.Activity;
import ru.xetpy.rikoshet.chronicle.Keys;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Взаимодействия игроков в реальном времени. Никаких ссылок на мир: главный поток передаёт
 * сюда координаты, приросты статистики и события, а класс копит счётчики пар до сброса в
 * pair_daily и возвращает заметные события. Часть связей прямая (урон, «спас», упоминание
 * в чате), часть — вывод по совпадениям (подарок, стройка у чужого дома, мародёрство).
 * Алгоритмы — docs/design/chronicle.md#взаимодействия-игроков. Только главный поток.
 */
public final class SocialTracker {
	static final double TOGETHER = 32;
	static final double CLOSE = 8;
	/** Хозяин ближе — значит, дома. */
	static final double HOST_PRESENT = 48;
	/** Место смерти: подошёл ближе — «был у вещей». */
	static final double SITE = 12;
	/** Дом — клетка 64×64 и соседние: 192×192 блока. */
	static final int HOME_RADIUS_CELLS = 1;
	static final int CELL = 64;
	static final long DIALOGUE_MS = 30_000;
	static final long REVENGE_MS = 10 * 60_000;
	static final long SITE_MS = 5 * 60_000;
	/** Столько секунд у чужого дома без хозяина — событие (раз в сутки на пару). */
	static final long VISIT_EVENT_S = 60;
	/** Подарок от этой ценности (5 алмазов) — событие. */
	static final int GIFT_EVENT = 50;

	/** Положение игрока в замере. */
	public record Pos(UUID uuid, String dim, double x, double y, double z) {
		int cx() {
			return Math.floorDiv((int) Math.floor(x), CELL);
		}

		int cz() {
			return Math.floorDiv((int) Math.floor(z), CELL);
		}

		double dist2(Pos o) {
			double dx = x - o.x;
			double dy = y - o.y;
			double dz = z - o.z;
			return dx * dx + dy * dy + dz * dz;
		}
	}

	/** Дом игрока: клетка, где он провёл больше всего времени. */
	public record Home(String dim, int cx, int cz) {
		boolean contains(Pos p) {
			return dim.equals(p.dim()) && Math.abs(p.cx() - cx) <= HOME_RADIUS_CELLS && Math.abs(p.cz() - cz) <= HOME_RADIUS_CELLS;
		}
	}

	/** Заметное событие для летописи: actor сделал что-то target. */
	public record Event(String type, UUID actor, UUID target, int score, String subject, JsonObject data) {
	}

	private record DeathSite(UUID victim, String dim, double x, double y, double z, long ts, Set<String> items) {
	}

	private record Revenge(UUID victim, long ts) {
	}

	private final Map<String, Long> pending = new HashMap<>();
	private final Map<String, Long> cycleTogether = new HashMap<>();
	private final Map<String, Long> cycleClose = new HashMap<>();
	/** гость → хозяин → {секунд без хозяина, секунд при хозяине} */
	private final Map<UUID, Map<UUID, long[]>> cycleAtHome = new HashMap<>();
	private final Map<UUID, Long> cycleSampled = new HashMap<>();
	private final Map<UUID, Set<DeathSite>> cycleAtSite = new HashMap<>();
	private final Deque<DeathSite> sites = new ArrayDeque<>();
	private final Map<UUID, Revenge> revenge = new HashMap<>();
	private final Map<String, Long> visitToday = new HashMap<>();
	private final Set<String> visitReported = new HashSet<>();
	private Map<UUID, Home> homes = Map.of();
	private UUID lastChatter;
	private long lastChatTs;

	public void setHomes(Map<UUID, Home> homes) {
		this.homes = Map.copyOf(homes);
	}

	public Map<UUID, Home> homes() {
		return homes;
	}

	/** Новый день: сброс «раз в сутки». Счётчики к этому моменту уже сброшены. */
	public void newDay() {
		visitToday.clear();
		visitReported.clear();
	}

	// ---------- замеры ----------

	/**
	 * Замер всех отслеживаемых игроков сразу, раз в sample_seconds. dt — сколько секунд
	 * представляет замер. Возвращает события визитов.
	 */
	public List<Event> sample(List<Pos> online, long dt, long now) {
		List<Event> out = new ArrayList<>();
		expire(now);
		Map<UUID, Pos> byId = new HashMap<>();
		for (Pos p : online) {
			byId.put(p.uuid(), p);
		}
		for (int i = 0; i < online.size(); i++) {
			Pos a = online.get(i);
			for (int j = i + 1; j < online.size(); j++) {
				Pos b = online.get(j);
				String pair = PairKeys.pair(a.uuid(), b.uuid());
				add(pair, PairKeys.OVERLAP, dt);
				if (!a.dim().equals(b.dim())) {
					continue;
				}
				double d2 = a.dist2(b);
				if (d2 <= TOGETHER * TOGETHER) {
					add(pair, PairKeys.TOGETHER, dt);
					cycleTogether.merge(pair, dt, Long::sum);
					if (d2 <= CLOSE * CLOSE) {
						add(pair, PairKeys.CLOSE, dt);
						cycleClose.merge(pair, dt, Long::sum);
					}
				}
			}
		}
		for (Pos p : online) {
			cycleSampled.merge(p.uuid(), dt, Long::sum);
			for (var h : homes.entrySet()) {
				UUID owner = h.getKey();
				if (owner.equals(p.uuid()) || !h.getValue().contains(p)) {
					continue;
				}
				Pos o = byId.get(owner);
				boolean present = o != null && o.dim().equals(p.dim()) && o.dist2(p) <= HOST_PRESENT * HOST_PRESENT;
				long[] slot = cycleAtHome.computeIfAbsent(p.uuid(), k -> new HashMap<>()).computeIfAbsent(owner, k -> new long[2]);
				String dir = PairKeys.directed(p.uuid(), owner);
				if (present) {
					add(dir, PairKeys.HOST, dt);
					slot[1] += dt;
				} else {
					add(dir, PairKeys.VISIT, dt);
					slot[0] += dt;
					long total = visitToday.merge(dir, dt, Long::sum);
					if (total >= VISIT_EVENT_S && visitReported.add(dir)) {
						JsonObject data = new JsonObject();
						data.addProperty("owner_online", o != null);
						out.add(new Event("visit", p.uuid(), owner, o == null ? 25 : 15, owner.toString(), data));
					}
				}
			}
			for (DeathSite s : sites) {
				if (!s.victim().equals(p.uuid()) && s.dim().equals(p.dim())
						&& sq(s.x() - p.x()) + sq(s.y() - p.y()) + sq(s.z() - p.z()) <= SITE * SITE) {
					cycleAtSite.computeIfAbsent(p.uuid(), k -> new HashSet<>()).add(s);
				}
			}
		}
		return out;
	}

	// ---------- окно между снимками ----------

	/**
	 * Итог окна: у всех отслеживаемых игроков сняты приросты статистики. deltas — прирост за окно,
	 * acts — занятие окна, windows — длина окна в секундах. Возвращает заметные события.
	 */
	public List<Event> cycle(Map<UUID, Map<String, Long>> deltas, Map<UUID, Activity> acts, Map<UUID, Long> windows) {
		List<Event> out = new ArrayList<>();
		// Общее занятие: рядом больше половины окна и заняты одним делом
		for (var e : cycleTogether.entrySet()) {
			UUID[] ab = split(e.getKey());
			Activity a = acts.get(ab[0]);
			Activity b = acts.get(ab[1]);
			long wa = windows.getOrDefault(ab[0], 0L);
			long wb = windows.getOrDefault(ab[1], 0L);
			if (a != null && a == b && a.meaningful() && e.getValue() * 2 >= Math.min(wa, wb)) {
				add(e.getKey(), PairKeys.JOINT + a.id(), e.getValue());
			}
		}
		// Подарки: стояли вплотную, у одного «выбросил X», у другого «подобрал X»
		for (var e : cycleClose.entrySet()) {
			UUID[] ab = split(e.getKey());
			for (int k = 0; k < 2; k++) {
				UUID from = ab[k];
				UUID to = ab[1 - k];
				Gift g = gift(deltas.get(from), deltas.get(to));
				if (g.value() > 0) {
					add(PairKeys.directed(from, to), PairKeys.GIFT, g.value());
					if (g.value() >= GIFT_EVENT) {
						JsonObject data = new JsonObject();
						data.addProperty("item", g.item());
						data.addProperty("count", g.count());
						data.addProperty("value", g.value());
						out.add(new Event("gift", from, to, Math.min(40, 10 + g.value() / 20), g.item(), data));
					}
				}
			}
		}
		// Стройка и копание у чужого дома: больше половины окна там
		for (var v : cycleAtHome.entrySet()) {
			UUID visitor = v.getKey();
			long sampled = cycleSampled.getOrDefault(visitor, 0L);
			Map<String, Long> d = deltas.get(visitor);
			if (d == null || sampled == 0) {
				continue;
			}
			for (var o : v.getValue().entrySet()) {
				long absent = o.getValue()[0];
				long present = o.getValue()[1];
				if ((absent + present) * 2 < sampled) {
					continue;
				}
				String dir = PairKeys.directed(visitor, o.getKey());
				long placed = d.getOrDefault(Keys.PLACED, 0L);
				long mined = d.getOrDefault(Keys.MINED, 0L);
				if (present >= absent) {
					add(dir, PairKeys.BUILD_AT, placed);
				} else {
					add(dir, PairKeys.BUILD_ABSENT, placed);
					add(dir, PairKeys.MINE_ABSENT, mined);
					if (mined >= 50) {
						JsonObject data = new JsonObject();
						data.addProperty("mined", mined);
						out.add(new Event("dig_at_home", visitor, o.getKey(), 20, o.getKey().toString(), data));
					}
				}
			}
		}
		// Вещи погибшего: был у места смерти и подобрал то, что выпало
		for (var s : cycleAtSite.entrySet()) {
			Map<String, Long> d = deltas.get(s.getKey());
			if (d == null) {
				continue;
			}
			for (DeathSite site : s.getValue()) {
				int value = 0;
				String top = null;
				int topValue = 0;
				for (String item : site.items()) {
					long n = d.getOrDefault("picked_up:" + item, 0L);
					if (n > 0) {
						int v = (int) Math.round(n * Math.max(0.1, GiftValues.of(item)) * 10);
						value += v;
						if (v > topValue) {
							topValue = v;
							top = item;
						}
					}
				}
				if (value > 0) {
					add(PairKeys.directed(s.getKey(), site.victim()), PairKeys.LOOT, value);
					JsonObject data = new JsonObject();
					data.addProperty("item", top);
					data.addProperty("value", value);
					out.add(new Event("loot", s.getKey(), site.victim(), Math.min(35, 10 + value / 20), top, data));
				}
			}
		}
		cycleTogether.clear();
		cycleClose.clear();
		cycleAtHome.clear();
		cycleSampled.clear();
		cycleAtSite.clear();
		return out;
	}

	/** Оценка подарка: сколько и чего; ценность — по {@link GiftValues}, 10 очков = алмаз. */
	public record Gift(int value, String item, long count) {
	}

	/**
	 * Подарок from → to по приростам статистики за окно. Кто подобрал намного больше, чем
	 * другой выбросил, скорее собирал своё — не считаем. Дешёвые вещи весят мало, поэтому
	 * общий булыжник под ногами не превращается в «подарок».
	 */
	public static Gift gift(Map<String, Long> from, Map<String, Long> to) {
		if (from == null || to == null) {
			return new Gift(0, null, 0);
		}
		double value = 0;
		String top = null;
		long topCount = 0;
		double topValue = 0;
		for (var e : from.entrySet()) {
			if (!e.getKey().startsWith("dropped:")) {
				continue;
			}
			String item = e.getKey().substring("dropped:".length());
			long dropped = e.getValue();
			long picked = to.getOrDefault("picked_up:" + item, 0L);
			if (dropped <= 0 || picked <= 0 || picked > dropped * 3 + 8) {
				continue;
			}
			long n = Math.min(dropped, picked);
			double v = n * GiftValues.of(item) * 10;
			value += v;
			if (v > topValue) {
				topValue = v;
				top = item;
				topCount = n;
			}
		}
		return new Gift((int) Math.round(value), top, topCount);
	}

	// ---------- события ----------

	/** Урон от игрока игроку. */
	public void pvpDamage(UUID attacker, UUID victim, float amount) {
		if (!attacker.equals(victim) && amount > 0) {
			add(PairKeys.directed(attacker, victim), PairKeys.PVP_DAMAGE, Math.round(amount * 10));
		}
	}

	/**
	 * Смерть игрока. killerPlayer — если убил игрок, killerEntity — моб (для мести), witnesses —
	 * кто был рядом, lostItems — что выпало (пусто при keepInventory).
	 */
	public void playerDeath(UUID victim, UUID killerPlayer, UUID killerEntity, List<UUID> witnesses,
			Pos where, Set<String> lostItems, long now) {
		if (killerPlayer != null && !killerPlayer.equals(victim)) {
			add(PairKeys.directed(killerPlayer, victim), PairKeys.PVP_KILL, 1);
		}
		if (killerEntity != null) {
			revenge.put(killerEntity, new Revenge(victim, now));
		}
		for (UUID w : witnesses) {
			if (!w.equals(victim)) {
				add(PairKeys.directed(w, victim), PairKeys.WITNESS, 1);
			}
		}
		if (where != null && !lostItems.isEmpty()) {
			sites.addLast(new DeathSite(victim, where.dim(), where.x(), where.y(), where.z(), now, Set.copyOf(lostItems)));
		}
	}

	/**
	 * Моб убит игроком. target — в кого моб целился, targetNear и targetHealth — рядом ли цель и
	 * какая доля здоровья у неё осталась. Возвращает событие «спас» или «отомстил», если было.
	 */
	public List<Event> mobKilled(UUID mob, UUID killer, UUID target, boolean targetNear, float targetHealth, long now) {
		List<Event> out = new ArrayList<>();
		if (target != null && !target.equals(killer) && targetNear) {
			add(PairKeys.directed(killer, target), PairKeys.RESCUE, 1);
			if (targetHealth <= 0.3f) {
				JsonObject data = new JsonObject();
				data.addProperty("health", Math.round(targetHealth * 100));
				out.add(new Event("rescue", killer, target, 20, null, data));
			}
		}
		Revenge r = revenge.remove(mob);
		if (r != null && now - r.ts() <= REVENGE_MS && !r.victim().equals(killer)) {
			add(PairKeys.directed(killer, r.victim()), PairKeys.REVENGE, 1);
			out.add(new Event("revenge", killer, r.victim(), 15, null, new JsonObject()));
		}
		return out;
	}

	/** Игрок убил чужого питомца. */
	public void petKilled(UUID killer, UUID owner) {
		if (!killer.equals(owner)) {
			add(PairKeys.directed(killer, owner), PairKeys.PET_KILL, 1);
		}
	}

	/**
	 * Сообщение в чате. names — как можно назвать каждого игрока: ник и роль в нижнем регистре.
	 * Текст нигде не хранится, только счётчики.
	 */
	public void chat(UUID from, String lowerText, Map<UUID, List<String>> names, long now) {
		for (var e : names.entrySet()) {
			if (e.getKey().equals(from)) {
				continue;
			}
			for (String n : e.getValue()) {
				if (n.length() >= 3 && lowerText.contains(n)) {
					add(PairKeys.directed(from, e.getKey()), PairKeys.MENTION, 1);
					break;
				}
			}
		}
		if (lastChatter != null && !lastChatter.equals(from) && now - lastChatTs <= DIALOGUE_MS && names.containsKey(lastChatter)) {
			add(PairKeys.pair(from, lastChatter), PairKeys.DIALOGUE, 1);
		}
		lastChatter = from;
		lastChatTs = now;
	}

	/** Игрок больше не отслеживается (вышел, /rick off): убрать из незакрытых окон. */
	public void forget(UUID uuid) {
		String s = uuid.toString();
		cycleTogether.keySet().removeIf(k -> k.contains(s));
		cycleClose.keySet().removeIf(k -> k.contains(s));
		cycleAtHome.remove(uuid);
		cycleAtHome.values().forEach(m -> m.remove(uuid));
		cycleSampled.remove(uuid);
		cycleAtSite.remove(uuid);
		if (uuid.equals(lastChatter)) {
			lastChatter = null;
		}
	}

	/** Накопленные счётчики пар «a|b|key» → значение; после вызова пусто. */
	public Map<String, Long> drain() {
		Map<String, Long> out = new HashMap<>(pending);
		pending.clear();
		return out;
	}

	private void add(String pair, String key, long v) {
		if (v != 0) {
			pending.merge(pair + "|" + key, v, Long::sum);
		}
	}

	private void expire(long now) {
		for (Iterator<DeathSite> it = sites.iterator(); it.hasNext(); ) {
			if (now - it.next().ts() > SITE_MS) {
				it.remove();
			}
		}
		revenge.values().removeIf(r -> now - r.ts() > REVENGE_MS);
	}

	private static UUID[] split(String pair) {
		int i = pair.indexOf('|');
		return new UUID[] {UUID.fromString(pair.substring(0, i)), UUID.fromString(pair.substring(i + 1))};
	}

	private static double sq(double v) {
		return v * v;
	}
}
