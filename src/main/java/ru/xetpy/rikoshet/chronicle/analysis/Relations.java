package ru.xetpy.rikoshet.chronicle.analysis;

import ru.xetpy.rikoshet.chronicle.Activity;
import ru.xetpy.rikoshet.chronicle.social.PairKeys;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Связи игроков по pair_daily: признаки пары за 14 дней, тип связи, перемены за неделю,
 * компании и прогноз «завтра вместе». Правила и веса — docs/design/chronicle.md#анализ-связей.
 * Чистая функция: на входе снимок, на выходе связи, факты и прогнозы.
 */
public final class Relations {
	private Relations() {
	}

	/** Признаки пары за период. Направленные — [a→b, b→a], где a &lt; b. */
	static final class Features {
		final UUID a;
		final UUID b;
		long together;
		long close;
		long overlap;
		long joint;
		final Map<String, Long> jointBy = new HashMap<>();
		long dialogue;
		final long[] mention = new long[2];
		final long[] gift = new long[2];
		final long[] rescue = new long[2];
		final long[] revenge = new long[2];
		final long[] pvpDamage = new long[2];
		final long[] pvpKill = new long[2];
		final long[] petKill = new long[2];
		final long[] visit = new long[2];
		final long[] host = new long[2];
		final long[] buildAt = new long[2];
		final long[] mineAbsent = new long[2];
		final long[] loot = new long[2];
		int daysBoth;
		int daysTogether;

		Features(UUID a, UUID b) {
			this.a = a;
			this.b = b;
		}

		double affinity() {
			return ln(together / 600.0) + 1.5 * ln(joint / 600.0) + 0.5 * ln(dialogue) + 0.3 * ln(mention[0] + mention[1])
					+ ln((gift[0] + gift[1]) / 50.0) + 0.7 * (rescue[0] + rescue[1] + revenge[0] + revenge[1])
					+ 0.5 * ln((host[0] + host[1]) / 600.0) + 0.5 * ln((buildAt[0] + buildAt[1]) / 100.0);
		}

		/** Взаимный урон — спарринг, а не вражда. */
		boolean sparring() {
			return pvpDamage[0] >= 40 && pvpDamage[1] >= 40;
		}

		double tension() {
			double kills = sparring() ? 0 : pvpKill[0] + pvpKill[1];
			return kills + 2.0 * (petKill[0] + petKill[1]) + ln((mineAbsent[0] + mineAbsent[1]) / 100.0)
					+ 0.5 * ln((loot[0] + loot[1]) / 50.0) + 0.3 * ln((visit[0] + visit[1]) / 600.0);
		}

		private static double ln(double x) {
			return x <= 0 ? 0 : Math.log1p(x);
		}
	}

	public record Result(List<DayReport.Relation> relations, List<DayReport.Fact> facts, List<DayReport.Forecast> forecasts) {
	}

	public static Result analyze(DayData d, Map<UUID, Forecaster.Online> online) {
		Map<String, Features> now = features(d, d.day().minusDays(13), d.day());
		Map<String, Features> week = features(d, d.day().minusDays(6), d.day());
		Map<String, Features> prevWeek = features(d, d.day().minusDays(13), d.day().minusDays(7));
		Map<String, Features> today = features(d, d.day(), d.day());
		List<DayReport.Relation> relations = new ArrayList<>();
		List<DayReport.Fact> facts = new ArrayList<>();
		List<DayReport.Forecast> forecasts = new ArrayList<>();

		for (Features f : now.values()) {
			if (!d.visible(f.a) || !d.visible(f.b) || f.overlap == 0 && f.together == 0) {
				continue;
			}
			String who = d.who(f.a) + " и " + d.who(f.b);
			Type t = type(f);
			String trend = null;
			Features w = week.get(key(f.a, f.b));
			Features p = prevWeek.get(key(f.a, f.b));
			long wt = w == null ? 0 : w.together;
			long pt = p == null ? 0 : p.together;
			long wo = w == null ? 0 : w.overlap;
			if (pt < 600 && wt >= 7200) {
				trend = "новая дружба";
				facts.add(fact("new_friends", f.a, 20, who + " за неделю провели вместе " + Metrics.duration(wt)
						+ ", а неделей раньше почти не пересекались"));
			} else if (pt >= 3 * 3600 && wt <= pt / 5 && wo >= 3 * 3600) {
				trend = "охлаждение";
				facts.add(fact("cooling", f.a, 20, who + " были неразлучны (" + Metrics.duration(pt) + " вместе), а на этой неделе — "
						+ Metrics.duration(wt) + ", хотя оба играли"));
			}
			Features td = today.get(key(f.a, f.b));
			if (td != null && td.tension() >= 1 && f.affinity() - td.affinity() >= 3) {
				facts.add(fact("quarrel", f.a, 30, who + " давно дружат, но вчера " + tensionText(td, d)));
			}
			relations.add(new DayReport.Relation(f.a.toString(), f.b.toString(), d.who(f.a), d.who(f.b), t.name, t.text(f, d),
					round(f.affinity()), round(f.tension()), trend));
		}
		relations.sort(Comparator.comparingDouble(DayReport.Relation::affinity).reversed());

		// Сегодняшняя пара дня и спарринги
		today.values().stream()
				.filter(f -> d.visible(f.a) && d.visible(f.b) && f.together >= 3600)
				.max(Comparator.comparingLong(f -> f.together))
				.ifPresent(f -> {
					String act = f.jointBy.entrySet().stream().max(Map.Entry.comparingByValue())
							.filter(x -> x.getValue() >= 1800)
							.flatMap(x -> Activity.byId(x.getKey()))
							.map(a -> ", больше всего — " + a.ru())
							.orElse("");
					facts.add(fact("pair_of_day", f.a, 10, d.who(f.a) + " и " + d.who(f.b) + " провели вместе "
							+ Metrics.duration(f.together) + act));
				});
		for (Features f : today.values()) {
			if (d.visible(f.a) && d.visible(f.b) && f.sparring()) {
				facts.add(fact("sparring", f.a, 12, d.who(f.a) + " и " + d.who(f.b) + " дрались друг с другом: "
						+ (f.pvpDamage[0] + f.pvpDamage[1]) / 20 + " сердец урона на двоих"));
			}
		}

		facts.addAll(graphFacts(d, now, today));

		// Прогноз «завтра вместе»: P(оба онлайн) · P(вместе | оба онлайн), сглаживание Лапласа
		Map<String, Features> month = features(d, d.day().minusDays(27), d.day());
		List<DayReport.Forecast> together = new ArrayList<>();
		for (Features f : month.values()) {
			Forecaster.Online oa = online.get(f.a);
			Forecaster.Online ob = online.get(f.b);
			if (oa == null || ob == null || !d.visible(f.a) || !d.visible(f.b) || f.daysTogether == 0) {
				continue;
			}
			double q = (f.daysTogether + 1.0) / (f.daysBoth + 2.0);
			double pr = oa.p() * ob.p() * q;
			if (pr >= 0.3) {
				together.add(new DayReport.Forecast("together", f.a.toString(), round(pr),
						d.who(f.a) + " и " + d.who(f.b) + " завтра снова будут вместе — вероятность " + Math.round(pr * 100) + "%"));
			}
		}
		together.sort(Comparator.comparingDouble(DayReport.Forecast::p).reversed());
		forecasts.addAll(together.subList(0, Math.min(2, together.size())));
		return new Result(relations, facts, forecasts);
	}

	// ---------- тип связи ----------

	record Type(String name, java.util.function.BiFunction<Features, DayData, String> describe) {
		String text(Features f, DayData d) {
			return describe.apply(f, d);
		}
	}

	static Type type(Features f) {
		double a = f.affinity();
		double t = f.tension();
		if (t >= 2 && t > a / 2) {
			return new Type("conflict", (x, d) -> "конфликт: " + tensionText(x, d));
		}
		if (f.sparring()) {
			return new Type("rivals", (x, d) -> "соперники: регулярно дерутся друг с другом");
		}
		if (f.together >= 5 * 3600 && f.overlap > 0 && f.together * 2 >= f.overlap) {
			return new Type("inseparable", (x, d) -> "неразлучники: вместе " + Metrics.duration(x.together)
					+ " из " + Metrics.duration(x.overlap) + " общего онлайна");
		}
		if (f.joint >= 2 * 3600) {
			return new Type("partners", (x, d) -> "напарники: " + topJoint(x) + " вместе " + Metrics.duration(x.joint));
		}
		int giver = patron(f);
		if (giver >= 0) {
			return new Type("patron", (x, d) -> {
				UUID from = giver == 0 ? x.a : x.b;
				UUID to = giver == 0 ? x.b : x.a;
				return d.who(from) + " опекает " + d.who(to) + ": подарки и помощь в бою";
			});
		}
		if (f.together >= 3600) {
			return new Type("friends", (x, d) -> "приятели: вместе " + Metrics.duration(x.together));
		}
		if (f.overlap >= 3 * 3600 && f.together < 600) {
			return new Type("parallel", (x, d) -> "параллельные миры: онлайн вместе " + Metrics.duration(x.overlap)
					+ ", рядом почти не бывают");
		}
		return new Type("acquainted", (x, d) -> "знакомые");
	}

	/** Кто кого опекает: подарки и спасения в одну сторону, втрое больше обратного. -1 — никто. */
	static int patron(Features f) {
		long ab = f.gift[0] + 200 * f.rescue[0];
		long ba = f.gift[1] + 200 * f.rescue[1];
		if (ab >= 200 && ab >= 3 * ba) {
			return 0;
		}
		if (ba >= 200 && ba >= 3 * ab) {
			return 1;
		}
		return -1;
	}

	static String topJoint(Features f) {
		return f.jointBy.entrySet().stream().max(Map.Entry.comparingByValue())
				.flatMap(x -> Activity.byId(x.getKey())).map(Activity::ru).orElse("общие дела");
	}

	static String tensionText(Features f, DayData d) {
		List<String> parts = new ArrayList<>();
		for (int k = 0; k < 2; k++) {
			UUID from = k == 0 ? f.a : f.b;
			UUID to = k == 0 ? f.b : f.a;
			if (f.pvpKill[k] > 0 && !f.sparring()) {
				parts.add(d.who(from) + " убивал " + d.who(to) + " (" + f.pvpKill[k] + ")");
			}
			if (f.petKill[k] > 0) {
				parts.add(d.who(from) + " убил питомца " + d.who(to));
			}
			if (f.mineAbsent[k] >= 50) {
				parts.add(d.who(from) + " копал у дома " + d.who(to) + " без хозяина");
			}
			if (f.loot[k] >= 50) {
				parts.add(d.who(from) + " подбирал вещи погибшего " + d.who(to));
			}
		}
		return parts.isEmpty() ? "мелкие стычки" : String.join("; ", parts);
	}

	// ---------- граф ----------

	/**
	 * Компании — компоненты связности по рёбрам «вместе ≥ 1 ч за 14 дней» (Union-Find), душа
	 * компании — больше всех разных партнёров, одиночка дня, «разрывается между двумя».
	 */
	static List<DayReport.Fact> graphFacts(DayData d, Map<String, Features> now, Map<String, Features> today) {
		List<DayReport.Fact> out = new ArrayList<>();
		Map<UUID, UUID> parent = new HashMap<>();
		Map<UUID, Set<UUID>> partners = new HashMap<>();
		for (Features f : now.values()) {
			if (!d.visible(f.a) || !d.visible(f.b) || f.together < 3600) {
				continue;
			}
			union(parent, f.a, f.b);
			partners.computeIfAbsent(f.a, k -> new HashSet<>()).add(f.b);
			partners.computeIfAbsent(f.b, k -> new HashSet<>()).add(f.a);
		}
		Map<UUID, List<UUID>> groups = new HashMap<>();
		for (UUID u : parent.keySet()) {
			groups.computeIfAbsent(find(parent, u), k -> new ArrayList<>()).add(u);
		}
		for (List<UUID> g : groups.values()) {
			if (g.size() >= 3) {
				g.sort(Comparator.comparing(d::who));
				out.add(fact("company", g.getFirst(), 6, "компания за две недели: "
						+ String.join(", ", g.stream().map(d::who).toList())));
			}
		}
		partners.entrySet().stream()
				.filter(e -> e.getValue().size() >= 3)
				.max(Comparator.comparingInt(e -> e.getValue().size()))
				.ifPresent(e -> out.add(fact("social_hub", e.getKey(), 6, d.who(e.getKey()) + " — душа компании: за две недели провёл "
						+ "больше часа с " + e.getValue().size() + " разными игроками")));

		// «Разрывается»: два друга, которые друг с другом не пересекаются
		for (var e : partners.entrySet()) {
			List<UUID> ps = new ArrayList<>(e.getValue());
			for (int i = 0; i < ps.size(); i++) {
				for (int j = i + 1; j < ps.size(); j++) {
					Features bc = now.get(key(ps.get(i), ps.get(j)));
					if (bc != null && bc.overlap >= 3600 && bc.together < 300) {
						out.add(fact("between", e.getKey(), 12, d.who(e.getKey()) + " делит время между " + d.who(ps.get(i))
								+ " и " + d.who(ps.get(j)) + ", а те друг с другом почти не видятся"));
						i = ps.size();
						break;
					}
				}
			}
		}

		// Одиночка дня: 2+ часа, другие были онлайн, рядом ни с кем
		Map<UUID, long[]> day = new HashMap<>();
		for (Features f : today.values()) {
			for (UUID u : new UUID[] {f.a, f.b}) {
				long[] v = day.computeIfAbsent(u, k -> new long[2]);
				v[0] += f.overlap;
				v[1] += f.together;
			}
		}
		for (var e : day.entrySet()) {
			long online = d.countersOf(e.getKey()).getOrDefault(ru.xetpy.rikoshet.chronicle.Keys.ONLINE, 0L);
			if (d.visible(e.getKey()) && online >= 7200 && e.getValue()[0] >= 3600 && e.getValue()[1] < 300) {
				out.add(fact("loner", e.getKey(), 8, d.who(e.getKey()) + " играл " + Metrics.duration(online)
						+ " и ни разу не подошёл к остальным, хотя они были онлайн"));
			}
		}
		return out;
	}

	private static UUID find(Map<UUID, UUID> parent, UUID u) {
		UUID p = parent.getOrDefault(u, u);
		if (p.equals(u)) {
			parent.putIfAbsent(u, u);
			return u;
		}
		UUID root = find(parent, p);
		parent.put(u, root);
		return root;
	}

	private static void union(Map<UUID, UUID> parent, UUID a, UUID b) {
		UUID ra = find(parent, a);
		UUID rb = find(parent, b);
		if (!ra.equals(rb)) {
			parent.put(ra, rb);
		}
	}

	// ---------- признаки ----------

	static Map<String, Features> features(DayData d, LocalDate from, LocalDate to) {
		Map<String, Features> out = new HashMap<>();
		for (var day : d.pairs().entrySet()) {
			if (day.getKey().isBefore(from) || day.getKey().isAfter(to)) {
				continue;
			}
			Map<String, long[]> perDay = new HashMap<>();
			for (DayData.PairRow r : day.getValue()) {
				boolean ab = r.a().toString().compareTo(r.b().toString()) < 0;
				UUID lo = ab ? r.a() : r.b();
				UUID hi = ab ? r.b() : r.a();
				Features f = out.computeIfAbsent(key(lo, hi), k -> new Features(lo, hi));
				int dir = ab ? 0 : 1;
				long v = r.value();
				String k = r.key();
				if (k.startsWith(PairKeys.JOINT)) {
					f.joint += v;
					f.jointBy.merge(k.substring(PairKeys.JOINT.length()), v, Long::sum);
					continue;
				}
				switch (k) {
					case PairKeys.TOGETHER -> {
						f.together += v;
						perDay.computeIfAbsent(key(lo, hi), x -> new long[2])[1] += v;
					}
					case PairKeys.CLOSE -> f.close += v;
					case PairKeys.OVERLAP -> {
						f.overlap += v;
						perDay.computeIfAbsent(key(lo, hi), x -> new long[2])[0] += v;
					}
					case PairKeys.DIALOGUE -> f.dialogue += v;
					case PairKeys.MENTION -> f.mention[dir] += v;
					case PairKeys.GIFT -> f.gift[dir] += v;
					case PairKeys.RESCUE -> f.rescue[dir] += v;
					case PairKeys.REVENGE -> f.revenge[dir] += v;
					case PairKeys.PVP_DAMAGE -> f.pvpDamage[dir] += v;
					case PairKeys.PVP_KILL -> f.pvpKill[dir] += v;
					case PairKeys.PET_KILL -> f.petKill[dir] += v;
					case PairKeys.VISIT -> f.visit[dir] += v;
					case PairKeys.HOST -> f.host[dir] += v;
					case PairKeys.BUILD_AT -> f.buildAt[dir] += v;
					case PairKeys.MINE_ABSENT -> f.mineAbsent[dir] += v;
					case PairKeys.LOOT -> f.loot[dir] += v;
					default -> {
					}
				}
			}
			for (var e : perDay.entrySet()) {
				Features f = out.get(e.getKey());
				if (e.getValue()[0] > 0) {
					f.daysBoth++;
				}
				if (e.getValue()[1] >= 300) {
					f.daysTogether++;
				}
			}
		}
		return out;
	}

	static String key(UUID x, UUID y) {
		return PairKeys.pair(x, y);
	}

	private static DayReport.Fact fact(String kind, UUID uuid, int score, String text) {
		return new DayReport.Fact(kind, uuid.toString(), score, text);
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}
}
