package ru.xetpy.rikoshet.chronicle.analysis;

import ru.xetpy.rikoshet.chronicle.Activity;
import ru.xetpy.rikoshet.chronicle.ChronicleEvent;
import ru.xetpy.rikoshet.chronicle.Keys;
import ru.xetpy.rikoshet.chronicle.Milestones;
import ru.xetpy.rikoshet.chronicle.Names;
import ru.xetpy.rikoshet.chronicle.social.PairKeys;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Анализ дня: из счётчиков, событий, сессий и пар — сводка, факты по значимости, прогнозы и
 * профили игроков. Детекторы — docs/design/chronicle.md#узнавание. Чистая функция, всё
 * детерминировано: ИИ получает уже отобранные факты.
 */
public final class Analyzer {
	/** Столько секунд онлайн — игрок «был» в этот день. */
	static final long ACTIVE_S = 60;
	static final int PER_PLAYER = 4;
	static final int PER_KIND = 4;
	/** События дешевле этого — только в сводку игрока, не в факты. */
	static final int EVENT_FACT_SCORE = 8;
	/** Дневник дня пишет модель по итогам — в факты того же дня он не идёт. */
	static final String DIARY = "diary";

	private Analyzer() {
	}

	public static DayReport analyze(DayData d, int maxFacts) {
		List<UUID> active = new ArrayList<>();
		for (var e : d.counters().entrySet()) {
			if (e.getValue().getOrDefault(Keys.ONLINE, 0L) >= ACTIVE_S) {
				active.add(e.getKey());
			}
		}
		active.sort(Comparator.comparing(u -> d.who(u).toLowerCase(Locale.ROOT)));

		List<DayReport.Fact> facts = new ArrayList<>();
		eventFacts(d, facts);
		deathSeries(d, facts);
		nemesis(d, facts);
		records(d, active, facts);
		unusual(d, active, facts);
		shifts(d, active, facts);
		streaks(d, active, facts);
		crimes(d, active, facts);
		explorers(d, active, facts);
		serverFacts(d, active, facts);

		Map<UUID, Forecaster.Online> online = Forecaster.online(d);
		Relations.Result rel = Relations.analyze(d, online);
		facts.addAll(rel.facts());
		for (Forecaster.Missing m : Forecaster.missing(d)) {
			if (d.visible(m.uuid())) {
				facts.add(fact("missing", m.uuid(), 12, d.who(m.uuid()) + " не заходит " + m.days() + " "
						+ Metrics.plural(m.days(), "день", "дня", "дней") + ", хотя обычно бывает раз в " + m.usualGap() + " "
						+ Metrics.plural(m.usualGap(), "день", "дня", "дней")));
			}
		}

		List<DayReport.Forecast> forecasts = forecasts(d, online, rel);
		List<DayReport.Profile> profiles = profiles(d);
		return new DayReport(
				d.day().toString(),
				d.day().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru")),
				server(d, active),
				players(d, active),
				rel.relations(),
				select(d, facts, maxFacts),
				forecasts,
				profiles);
	}

	// ---------- сводки ----------

	static DayReport.ServerDay server(DayData d, List<UUID> active) {
		long online = 0;
		long deaths = 0;
		long kills = 0;
		long mined = 0;
		long placed = 0;
		long distance = 0;
		for (Map<String, Long> c : d.counters().values()) {
			online += c.getOrDefault(Keys.ONLINE, 0L);
			deaths += c.getOrDefault(Keys.DEATHS, 0L);
			kills += c.getOrDefault(Keys.MOB_KILLS, 0L);
			mined += c.getOrDefault(Keys.MINED, 0L);
			placed += c.getOrDefault(Keys.PLACED, 0L);
			distance += c.getOrDefault(Keys.DISTANCE, 0L);
		}
		Map<String, Integer> killers = new HashMap<>();
		for (ChronicleEvent e : d.events()) {
			if (ChronicleEvent.DEATH.equals(e.type())) {
				killers.merge(Forecaster.killerLabel(e), 1, Integer::sum);
			}
		}
		var top = killers.entrySet().stream().max(Map.Entry.comparingByValue());
		int[] peak = peak(d);
		String peakAt = peak[0] > 0 ? String.format(Locale.ROOT, "%02d:%02d", peak[1] / 60, peak[1] % 60) : null;
		return new DayReport.ServerDay(active.size(), online, peak[0], peakAt, deaths, kills, mined, placed, distance,
				top.map(Map.Entry::getKey).orElse(null), top.map(Map.Entry::getValue).orElse(0));
	}

	/** Пик одновременного онлайна за день: {игроков, минута суток}. Развёртка по началам и концам сессий. */
	static int[] peak(DayData d) {
		long from = d.day().atStartOfDay(d.zone()).toInstant().toEpochMilli();
		long to = d.day().plusDays(1).atStartOfDay(d.zone()).toInstant().toEpochMilli();
		List<long[]> points = new ArrayList<>();
		for (DayData.Session s : d.sessions()) {
			long a = Math.max(from, s.start());
			long b = Math.min(to, s.end());
			if (a < b) {
				points.add(new long[] {a, 1});
				points.add(new long[] {b, -1});
			}
		}
		points.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
		int cur = 0;
		int best = 0;
		long at = 0;
		for (long[] p : points) {
			cur += (int) p[1];
			if (cur > best) {
				best = cur;
				at = p[0];
			}
		}
		ZonedDateTime z = Instant.ofEpochMilli(at).atZone(d.zone());
		return new int[] {best, z.getHour() * 60 + z.getMinute()};
	}

	static List<DayReport.PlayerDay> players(DayData d, List<UUID> active) {
		List<DayReport.PlayerDay> out = new ArrayList<>();
		long from = d.day().atStartOfDay(d.zone()).toInstant().toEpochMilli();
		long to = d.day().plusDays(1).atStartOfDay(d.zone()).toInstant().toEpochMilli();
		for (UUID u : active) {
			if (!d.visible(u)) {
				continue;
			}
			Map<String, Long> c = d.countersOf(u);
			Map<String, Long> acts = new LinkedHashMap<>();
			c.entrySet().stream()
					.filter(e -> e.getKey().startsWith(Keys.ACT) && e.getValue() >= 60)
					.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
					.forEach(e -> acts.put(Activity.byId(e.getKey().substring(Keys.ACT.length())).map(Activity::ru).orElse(e.getKey()), e.getValue()));
			Map<String, String> numbers = new LinkedHashMap<>();
			for (Metrics.Metric m : Metrics.ALL) {
				long v = c.getOrDefault(m.key(), 0L);
				if (v > 0 && !m.key().equals(Keys.ONLINE) && !m.key().equals(Keys.JUMPS) && !m.key().equals(Keys.DAMAGE_TAKEN)) {
					numbers.put(m.label(), m.show(v));
				}
			}
			for (String boss : Milestones.BOSSES.keySet()) {
				long v = c.getOrDefault("killed:" + boss, 0L);
				if (v > 0) {
					numbers.put("убит " + Names.mob(boss), Long.toString(v));
				}
			}
			List<String> deaths = new ArrayList<>();
			for (ChronicleEvent e : d.events()) {
				if (ChronicleEvent.DEATH.equals(e.type()) && u.equals(e.uuid())) {
					deaths.add(Forecaster.killerLabel(e));
				}
			}
			int sessions = 0;
			long first = Long.MAX_VALUE;
			long last = 0;
			for (DayData.Session s : d.sessions()) {
				if (s.uuid().equals(u) && s.end() > from && s.start() < to) {
					sessions++;
					first = Math.min(first, Math.max(from, s.start()));
					last = Math.max(last, Math.min(to, s.end()));
				}
			}
			List<String> partners = new ArrayList<>();
			pairTotals(d, d.day(), d.day(), PairKeys.TOGETHER).entrySet().stream()
					.filter(e -> e.getValue() >= 600 && (e.getKey()[0].equals(u) || e.getKey()[1].equals(u)))
					.sorted(Map.Entry.<UUID[], Long>comparingByValue().reversed())
					.limit(3)
					.forEach(e -> {
						UUID other = e.getKey()[0].equals(u) ? e.getKey()[1] : e.getKey()[0];
						if (d.visible(other)) {
							partners.add(d.who(other) + " — " + Metrics.duration(e.getValue()));
						}
					});
			out.add(new DayReport.PlayerDay(u.toString(), d.who(u), c.getOrDefault(Keys.ONLINE, 0L), c.getOrDefault(Keys.AFK, 0L),
					sessions, first == Long.MAX_VALUE ? null : hhmm(d, first), last == 0 ? null : hhmm(d, last),
					acts, numbers, deaths, partners, c.getOrDefault(Keys.NEW_BIOMES, 0L).intValue(), c.getOrDefault(Keys.CHAT, 0L)));
		}
		return out;
	}

	/** Сумма симметричного ключа пар за дни [from, to]: {a, b} → значение. */
	static Map<UUID[], Long> pairTotals(DayData d, LocalDate from, LocalDate to, String key) {
		Map<String, Long> sum = new HashMap<>();
		Map<String, UUID[]> ids = new HashMap<>();
		for (var day : d.pairs().entrySet()) {
			if (day.getKey().isBefore(from) || day.getKey().isAfter(to)) {
				continue;
			}
			for (DayData.PairRow r : day.getValue()) {
				if (r.key().equals(key)) {
					String k = PairKeys.pair(r.a(), r.b());
					sum.merge(k, r.value(), Long::sum);
					ids.putIfAbsent(k, new UUID[] {r.a(), r.b()});
				}
			}
		}
		Map<UUID[], Long> out = new HashMap<>();
		sum.forEach((k, v) -> out.put(ids.get(k), v));
		return out;
	}

	// ---------- детекторы ----------

	/** События летописи с достаточной значимостью — сразу факты. Смерти — только заметные. */
	static void eventFacts(DayData d, List<DayReport.Fact> out) {
		for (ChronicleEvent e : d.events()) {
			if (e.score() < EVENT_FACT_SCORE || DIARY.equals(e.type())) {
				continue;
			}
			out.add(new DayReport.Fact(e.type(), e.uuid() == null ? null : e.uuid().toString(), e.score(),
					EventText.render(e, d::who)));
		}
	}

	/** Серия: 3+ смерти одного игрока за 10 минут. */
	static void deathSeries(DayData d, List<DayReport.Fact> out) {
		Map<UUID, List<Long>> times = new HashMap<>();
		for (ChronicleEvent e : d.events()) {
			if (ChronicleEvent.DEATH.equals(e.type()) && e.uuid() != null) {
				times.computeIfAbsent(e.uuid(), k -> new ArrayList<>()).add(e.ts());
			}
		}
		for (var e : times.entrySet()) {
			List<Long> t = e.getValue();
			t.sort(Long::compare);
			int best = 0;
			for (int i = 0, j = 0; j < t.size(); j++) {
				while (t.get(j) - t.get(i) > 10 * 60_000) {
					i++;
				}
				best = Math.max(best, j - i + 1);
			}
			if (best >= 3) {
				out.add(fact("series", e.getKey(), 20, d.who(e.getKey()) + " умер " + best + " "
						+ Metrics.plural(best, "раз", "раза", "раз") + " за 10 минут"));
			}
		}
	}

	/** Немезида: один убийца 3+ раза за 7 дней, и хотя бы раз — сегодня. */
	static void nemesis(DayData d, List<DayReport.Fact> out) {
		LocalDate from = d.day().minusDays(6);
		Map<UUID, Map<String, int[]>> by = new HashMap<>();
		for (ChronicleEvent e : d.deaths()) {
			if (e.uuid() == null || LocalDate.parse(e.day()).isBefore(from)) {
				continue;
			}
			int[] v = by.computeIfAbsent(e.uuid(), k -> new HashMap<>()).computeIfAbsent(Forecaster.killerLabel(e), k -> new int[2]);
			v[0]++;
			if (e.day().equals(d.day().toString())) {
				v[1]++;
			}
		}
		for (var p : by.entrySet()) {
			for (var k : p.getValue().entrySet()) {
				if (k.getValue()[0] >= 3 && k.getValue()[1] > 0 && !k.getKey().equals("неизвестно")) {
					out.add(fact("nemesis", p.getKey(), 20, "у " + d.who(p.getKey()) + " есть немезида — " + k.getKey()
							+ ": " + k.getValue()[0] + " " + Metrics.plural(k.getValue()[0], "смерть", "смерти", "смертей") + " за неделю"));
				}
			}
		}
	}

	/** Рекорды: сервера — лучше всех прошлых дней; личные — лучше своих, при 7+ днях истории. */
	static void records(DayData d, List<UUID> active, List<DayReport.Fact> out) {
		for (Metrics.Metric m : Metrics.ALL) {
			UUID leader = null;
			long top = 0;
			for (UUID u : active) {
				long v = d.countersOf(u).getOrDefault(m.key(), 0L);
				if (v > top) {
					top = v;
					leader = u;
				}
			}
			DayData.Best best = d.serverBest().get(m.key());
			boolean serverRecord = leader != null && top >= m.floor() && best != null && top > best.value();
			if (serverRecord) {
				out.add(fact("record", leader, 60, d.who(leader) + " — рекорд сервера за день: " + m.label() + " " + m.show(top)
						+ " (прежний — " + m.show(best.value()) + ", " + d.who(best.uuid()) + ", " + best.day() + ")"));
			}
			for (UUID u : active) {
				if (serverRecord && u.equals(leader)) {
					continue;
				}
				long v = d.countersOf(u).getOrDefault(m.key(), 0L);
				Long pb = d.personalBest().getOrDefault(u, Map.of()).get(m.key());
				int days = d.activeDays().getOrDefault(u, List.of()).size();
				if (v >= m.floor() && pb != null && v > pb && days >= 7) {
					out.add(fact("personal_best", u, 20, d.who(u) + " побил личный рекорд: " + m.label() + " " + m.show(v)
							+ " (было " + m.show(pb) + ")"));
				}
			}
		}
	}

	/** Необычно много для себя: в 3 раза выше медианы своих активных дней. */
	static void unusual(DayData d, List<UUID> active, List<DayReport.Fact> out) {
		for (UUID u : active) {
			Map<LocalDate, Map<String, Long>> hist = d.history().getOrDefault(u, Map.of());
			List<Map<String, Long>> days = hist.values().stream().filter(x -> x.getOrDefault(Keys.ONLINE, 0L) >= ACTIVE_S).toList();
			if (days.size() < 3) {
				continue;
			}
			DayReport.Fact best = null;
			double bestRatio = 0;
			for (Metrics.Metric m : Metrics.ALL) {
				if (m.key().equals(Keys.ONLINE)) {
					continue;
				}
				long v = d.countersOf(u).getOrDefault(m.key(), 0L);
				List<Long> vals = days.stream().map(x -> x.getOrDefault(m.key(), 0L)).toList();
				long median = Forecaster.median(vals.stream().map(x -> Math.max(0, x)).toList());
				if (v < m.floor() / 2 || v < 3 * median) {
					continue;
				}
				double ratio = (double) v / Math.max(1, median);
				if (ratio > bestRatio) {
					bestRatio = ratio;
					best = fact("unusual", u, 15, d.who(u) + " — необычно для себя: " + m.label() + " " + m.show(v)
							+ " (обычно около " + m.show(median) + ")");
				}
			}
			if (best != null) {
				out.add(best);
			}
		}
	}

	/** Смена амплуа: главное занятие дня (час и больше) не то, что обычно за 14 дней. */
	static void shifts(DayData d, List<UUID> active, List<DayReport.Fact> out) {
		for (UUID u : active) {
			Activity today = topActivity(d.countersOf(u), 3600);
			if (today == null) {
				continue;
			}
			Map<String, Long> sum = new HashMap<>();
			int days = 0;
			for (var e : d.history().getOrDefault(u, Map.of()).entrySet()) {
				if (e.getKey().isBefore(d.day().minusDays(14))) {
					continue;
				}
				if (e.getValue().getOrDefault(Keys.ONLINE, 0L) >= ACTIVE_S) {
					days++;
				}
				e.getValue().forEach((k, v) -> {
					if (k.startsWith(Keys.ACT)) {
						sum.merge(k, v, Long::sum);
					}
				});
			}
			Activity usual = topActivity(sum, 0);
			if (days >= 3 && usual != null && usual != today) {
				out.add(fact("shift", u, 15, d.who(u) + " сменил амплуа: обычно " + usual.ru() + ", а вчера — "
						+ today.ru() + " (" + Metrics.duration(d.countersOf(u).getOrDefault(today.key(), 0L)) + ")"));
			}
		}
	}

	static Activity topActivity(Map<String, Long> c, long min) {
		Activity best = null;
		long bestV = min - 1;
		for (Activity a : Activity.values()) {
			if (!a.meaningful()) {
				continue;
			}
			long v = c.getOrDefault(a.key(), 0L);
			if (v > bestV && v > 0) {
				bestV = v;
				best = a;
			}
		}
		return best;
	}

	/** Серии дней подряд, дни без смертей, возвращение после недели. */
	static void streaks(DayData d, List<UUID> active, List<DayReport.Fact> out) {
		for (UUID u : active) {
			List<LocalDate> days = d.activeDays().getOrDefault(u, List.of());
			int streak = 0;
			LocalDate expect = d.day();
			for (int i = days.size() - 1; i >= 0 && days.get(i).equals(expect); i--) {
				streak++;
				expect = expect.minusDays(1);
			}
			if (streak == 7 || streak == 14 || streak == 30 || streak > 30 && streak % 30 == 0) {
				out.add(fact("streak", u, 15, d.who(u) + " заходит " + streak + " " + Metrics.plural(streak, "день", "дня", "дней") + " подряд"));
			}
			int idx = days.indexOf(d.day());
			DayData.Person person = d.people().get(u);
			long dayStart = d.day().atStartOfDay(d.zone()).toInstant().toEpochMilli();
			if (person != null && person.firstSeen() >= dayStart) {
				out.add(fact("newcomer", u, 25, d.who(u) + " впервые зашёл на сервер"));
			} else if (idx > 0) {
				long gap = ChronoUnit.DAYS.between(days.get(idx - 1), d.day());
				if (gap >= 7) {
					out.add(fact("return", u, 20, d.who(u) + " вернулся после " + gap + " " + Metrics.plural(gap, "дня", "дней", "дней") + " отсутствия"));
				}
			}
			// Без смертей: подряд активные дни (от 30 мин) с нулём смертей
			int clean = 0;
			for (LocalDate day = d.day(); ; day = day.minusDays(1)) {
				Map<String, Long> c = day.equals(d.day()) ? d.countersOf(u) : d.history().getOrDefault(u, Map.of()).get(day);
				if (c == null || c.getOrDefault(Keys.ONLINE, 0L) < 1800 || c.getOrDefault(Keys.DEATHS, 0L) > 0) {
					break;
				}
				clean++;
			}
			if (clean == 3 || clean == 5 || clean == 7 || clean == 14) {
				out.add(fact("deathless", u, 10, d.who(u) + " не умирал " + clean + " " + Metrics.plural(clean, "день", "дня", "дней") + " подряд"));
			}
		}
	}

	/** Убийства жителей, торговцев и големов. */
	static void crimes(DayData d, List<UUID> active, List<DayReport.Fact> out) {
		for (UUID u : active) {
			List<String> parts = new ArrayList<>();
			long total = 0;
			for (String mob : Milestones.CRIMES) {
				long v = d.countersOf(u).getOrDefault("killed:" + mob, 0L);
				if (v > 0) {
					parts.add(Names.mob(mob) + " ×" + v);
					total += v;
				}
			}
			if (total > 0) {
				out.add(fact("crime", u, (int) Math.min(40, 12 + 4 * total), d.who(u) + " убил мирных: " + String.join(", ", parts)));
			}
		}
	}

	/** Первопроходцы: новые биомы и места, где никто не бывал. */
	static void explorers(DayData d, List<UUID> active, List<DayReport.Fact> out) {
		for (UUID u : active) {
			long biomes = d.countersOf(u).getOrDefault(Keys.NEW_BIOMES, 0L);
			long first = d.countersOf(u).getOrDefault(Keys.FIRST_CELLS, 0L);
			if (biomes >= 5) {
				List<String> names = d.seen().stream()
						.filter(s -> s.uuid().equals(u) && s.kind().equals("biome"))
						.map(s -> Names.pretty(s.id())).limit(4).toList();
				out.add(fact("explorer", u, 8, d.who(u) + " открыл для себя " + biomes + " "
						+ Metrics.plural(biomes, "биом", "биома", "биомов") + (names.isEmpty() ? "" : ": " + String.join(", ", names))));
			}
			if (first >= 40) {
				out.add(fact("pioneer", u, 10, d.who(u) + " побывал там, где до него не ступал никто: " + first + " "
						+ Metrics.plural(first, "участок", "участка", "участков") + " карты"));
			}
		}
	}

	static void serverFacts(DayData d, List<UUID> active, List<DayReport.Fact> out) {
		long deaths = 0;
		for (UUID u : active) {
			deaths += d.countersOf(u).getOrDefault(Keys.DEATHS, 0L);
		}
		if (active.size() >= 3 && deaths == 0) {
			out.add(new DayReport.Fact("quiet", null, 10, "за весь день никто не умер, хотя играли " + active.size() + " человек"));
		}
	}

	// ---------- прогнозы и профили ----------

	static List<DayReport.Forecast> forecasts(DayData d, Map<UUID, Forecaster.Online> online, Relations.Result rel) {
		List<DayReport.Forecast> out = new ArrayList<>();
		online.entrySet().stream()
				.filter(e -> d.visible(e.getKey()) && e.getValue().p() >= 0.5)
				.sorted(Map.Entry.<UUID, Forecaster.Online>comparingByValue(Comparator.comparingDouble(Forecaster.Online::p)).reversed())
				.limit(4)
				.forEach(e -> out.add(new DayReport.Forecast("online", e.getKey().toString(), e.getValue().p(),
						d.who(e.getKey()) + " завтра зайдёт с вероятностью " + Math.round(e.getValue().p() * 100) + "%"
								+ (e.getValue().hour() >= 0 ? ", скорее всего около " + e.getValue().hour() + ":00" : ""))));
		Map<UUID, Forecaster.Risk> risks = Forecaster.risks(d);
		for (var e : risks.entrySet()) {
			Forecaster.Online o = online.get(e.getKey());
			if (d.visible(e.getKey()) && o != null && o.p() >= 0.4) {
				Forecaster.Risk r = e.getValue();
				out.add(new DayReport.Forecast("risk", e.getKey().toString(), (double) r.count() / r.total(),
						d.who(e.getKey()) + ": главная угроза — " + r.killer() + " (" + r.count() + " из " + r.total() + " смертей за месяц)"));
			}
		}
		for (UUID u : d.counters().keySet()) {
			if (!d.visible(u)) {
				continue;
			}
			for (Forecaster.Upcoming up : Forecaster.upcoming(d, u)) {
				Metrics.Metric m = Metrics.byKey(up.key());
				String what = m != null ? m.label() + " — " + m.show(up.threshold()) : up.key() + " — " + up.threshold();
				out.add(new DayReport.Forecast("milestone", u.toString(), 0, d.who(u) + " на подходе к вехе: " + what
						+ ", осталось " + Metrics.count(up.left()) + ", при нынешнем темпе — через " + up.days() + " "
						+ Metrics.plural(up.days(), "день", "дня", "дней")));
			}
		}
		out.addAll(rel.forecasts());
		return out;
	}

	static List<DayReport.Profile> profiles(DayData d) {
		List<DayReport.Profile> out = new ArrayList<>();
		LocalDate from = d.day().minusDays(13);
		Map<UUID[], Long> together = pairTotals(d, from, d.day(), PairKeys.TOGETHER);
		Map<UUID, long[]> hours = Forecaster.hourHistogram(d);
		Map<UUID, Forecaster.Risk> risks = Forecaster.risks(d);
		for (UUID u : d.people().keySet()) {
			Map<String, Long> acts = new HashMap<>();
			Map<String, Long> biomes = new HashMap<>();
			int days = 0;
			List<Map<String, Long>> window = new ArrayList<>();
			window.add(d.countersOf(u));
			d.history().getOrDefault(u, Map.of()).forEach((day, c) -> {
				if (!day.isBefore(from)) {
					window.add(c);
				}
			});
			for (Map<String, Long> c : window) {
				if (c.getOrDefault(Keys.ONLINE, 0L) >= ACTIVE_S) {
					days++;
				}
				c.forEach((k, v) -> {
					if (k.startsWith(Keys.ACT)) {
						acts.merge(k.substring(Keys.ACT.length()), v, Long::sum);
					} else if (k.startsWith(Keys.BIOME)) {
						biomes.merge(k.substring(Keys.BIOME.length()), v, Long::sum);
					}
				});
			}
			if (days == 0) {
				continue;
			}
			long meaningful = acts.entrySet().stream().filter(e -> Activity.byId(e.getKey()).map(Activity::meaningful).orElse(false))
					.mapToLong(Map.Entry::getValue).sum();
			Map<String, Double> shares = new LinkedHashMap<>();
			acts.entrySet().stream()
					.filter(e -> Activity.byId(e.getKey()).map(Activity::meaningful).orElse(false))
					.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
					.forEach(e -> shares.put(e.getKey(), meaningful == 0 ? 0 : Math.round(e.getValue() * 100.0 / meaningful) / 100.0));
			List<String> style = shares.entrySet().stream().filter(e -> e.getValue() >= 0.25).limit(2)
					.map(e -> STYLE.getOrDefault(e.getKey(), e.getKey())).toList();
			long sessions = d.sessions().stream().filter(s -> s.uuid().equals(u)).count();
			Forecaster.Risk risk = risks.get(u);
			UUID friend = null;
			long friendS = 0;
			for (var e : together.entrySet()) {
				UUID other = e.getKey()[0].equals(u) ? e.getKey()[1] : e.getKey()[1].equals(u) ? e.getKey()[0] : null;
				if (other != null && e.getValue() > friendS && d.visible(other)) {
					friend = other;
					friendS = e.getValue();
				}
			}
			String biome = biomes.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> Names.pretty(e.getKey())).orElse(null);
			int deaths = (int) d.deaths().stream().filter(e -> u.equals(e.uuid())).count();
			out.add(new DayReport.Profile(u.toString(), style.isEmpty() ? "без выраженного стиля" : String.join(" и ", style), shares,
					Forecaster.peakHour(hours.get(u)), sessions == 0 ? 0 : d.sessions().stream().filter(s -> s.uuid().equals(u))
							.mapToLong(s -> (s.end() - s.start()) / 1000).sum() / sessions,
					days, risk == null ? null : risk.killer(), risk == null ? 0 : risk.count(), deaths,
					friendS >= 1800 && friend != null ? friend.toString() : null, friendS, biome));
		}
		return out;
	}

	static final Map<String, String> STYLE = Map.of(
			"mining", "шахтёр",
			"lumber", "лесоруб",
			"building", "строитель",
			"farming", "фермер",
			"combat", "воин",
			"exploring", "путешественник",
			"fishing", "рыбак",
			"trading", "торговец и чародей",
			"crafting", "мастер");

	// ---------- отбор ----------

	/**
	 * Отбор фактов: по значимости, не больше PER_PLAYER на игрока и PER_KIND на детектор,
	 * игроки с /rick off — не героями фактов. Остаток — в порядке убывания.
	 */
	static List<DayReport.Fact> select(DayData d, List<DayReport.Fact> all, int max) {
		List<DayReport.Fact> sorted = new ArrayList<>(all);
		sorted.sort(Comparator.comparingInt(DayReport.Fact::score).reversed().thenComparing(DayReport.Fact::text));
		Map<String, Integer> perPlayer = new HashMap<>();
		Map<String, Integer> perKind = new HashMap<>();
		List<DayReport.Fact> out = new ArrayList<>();
		for (DayReport.Fact f : sorted) {
			if (out.size() >= max) {
				break;
			}
			if (f.uuid() != null && !d.visible(UUID.fromString(f.uuid()))) {
				continue;
			}
			String p = f.uuid() == null ? "server" : f.uuid();
			if (perPlayer.getOrDefault(p, 0) >= PER_PLAYER || perKind.getOrDefault(f.kind(), 0) >= PER_KIND) {
				continue;
			}
			perPlayer.merge(p, 1, Integer::sum);
			perKind.merge(f.kind(), 1, Integer::sum);
			out.add(f);
		}
		return out;
	}

	static DayReport.Fact fact(String kind, UUID uuid, int score, String text) {
		return new DayReport.Fact(kind, uuid == null ? null : uuid.toString(), score, text);
	}

	private static String hhmm(DayData d, long ts) {
		ZonedDateTime z = Instant.ofEpochMilli(ts).atZone(d.zone());
		return String.format(Locale.ROOT, "%02d:%02d", z.getHour(), z.getMinute());
	}
}
