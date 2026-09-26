package ru.xetpy.rikoshet.chronicle.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Итоги дня → текст для редакции газеты (блок &lt;context&gt;) и для /rickadmin chronicle day.
 * Порядок: сводка, игроки, факты, связи, истории, прогнозы, прошлые заголовки. Около 1–3 тыс.
 * токенов даже в насыщенный день: всё уже отобрано анализом.
 */
public final class DigestWriter {
	static final int MAX_RELATIONS = 6;

	private DigestWriter() {
	}

	/**
	 * @param stories   продолжающиеся истории из памяти («третий день…»)
	 * @param headlines заголовки прошлых выпусков, свежие первыми
	 */
	public static String write(DayReport r, List<String> stories, List<String> headlines) {
		return write(r, stories, headlines, Integer.MAX_VALUE);
	}

	/** То же, но фактов — не больше maxFacts (0 — без фактов). */
	public static String write(DayReport r, List<String> stories, List<String> headlines, int maxFacts) {
		StringBuilder sb = new StringBuilder();
		sb.append("Выпуск за ").append(r.day()).append(", ").append(r.weekday()).append(".\n");
		DayReport.ServerDay s = r.server();
		if (s.players() == 0) {
			sb.append("Вчера на сервер никто не заходил.\n");
		} else {
			sb.append("Сервер за день: играли ").append(s.players()).append(' ')
					.append(Metrics.plural(s.players(), "человек", "человека", "человек"))
					.append(", всего ").append(Metrics.duration(s.onlineSeconds()));
			if (s.peakAt() != null && s.peak() > 1) {
				sb.append(", пик — ").append(s.peak()).append(" одновременно в ").append(s.peakAt());
			}
			sb.append(". Смертей ").append(s.deaths()).append(", убито мобов ").append(Metrics.count(s.mobKills()))
					.append(", добыто блоков ").append(Metrics.count(s.mined())).append(", поставлено ")
					.append(Metrics.count(s.placed())).append(", пройдено ").append(Metrics.km(s.distance())).append('.');
			if (s.topKiller() != null && s.topKillerCount() >= 2) {
				sb.append(" Опаснее всех — ").append(s.topKiller()).append(" (").append(s.topKillerCount()).append(' ')
						.append(Metrics.plural(s.topKillerCount(), "смерть", "смерти", "смертей")).append(").");
			}
			sb.append('\n');
		}

		if (!r.players().isEmpty()) {
			sb.append("\nИгроки:\n");
			for (DayReport.PlayerDay p : r.players()) {
				sb.append("- ").append(p.who()).append(": ").append(Metrics.duration(p.online()));
				if (p.afk() >= 600) {
					sb.append(" (AFK ").append(Metrics.duration(p.afk())).append(')');
				}
				if (p.first() != null) {
					sb.append(", ").append(p.first()).append('–').append(p.last());
				}
				sb.append('.');
				if (!p.activities().isEmpty()) {
					List<String> a = new ArrayList<>();
					p.activities().entrySet().stream().limit(4).forEach(e -> a.add(e.getKey() + " " + Metrics.duration(e.getValue())));
					sb.append(" Занятия: ").append(String.join(", ", a)).append('.');
				}
				if (!p.numbers().isEmpty()) {
					List<String> n = new ArrayList<>();
					p.numbers().entrySet().stream().limit(6).forEach(e -> n.add(e.getKey() + " " + e.getValue()));
					sb.append(' ').append(capitalize(String.join(", ", n))).append('.');
				}
				if (!p.deaths().isEmpty()) {
					sb.append(" Смерти: ").append(String.join(", ", p.deaths())).append('.');
				}
				if (!p.partners().isEmpty()) {
					sb.append(" Рядом: ").append(String.join(", ", p.partners())).append('.');
				}
				sb.append('\n');
			}
		}

		if (!r.facts().isEmpty() && maxFacts > 0) {
			sb.append("\nФакты дня (важные первыми):\n");
			int i = 1;
			for (DayReport.Fact f : r.facts()) {
				if (i > maxFacts) {
					break;
				}
				sb.append(i++).append(". ").append(f.text()).append('\n');
			}
		}

		List<DayReport.Relation> rel = new ArrayList<>();
		for (DayReport.Relation x : r.relations()) {
			boolean notable = x.trend() != null || "conflict".equals(x.type()) || "rivals".equals(x.type());
			if (notable || rel.size() < MAX_RELATIONS && !"acquainted".equals(x.type())) {
				rel.add(x);
			}
		}
		if (!rel.isEmpty()) {
			sb.append("\nСвязи за две недели:\n");
			for (DayReport.Relation x : rel) {
				sb.append("- ").append(x.aWho()).append(" и ").append(x.bWho())
						.append(": ").append(x.text());
				if (x.trend() != null) {
					sb.append(" (").append(x.trend()).append(')');
				}
				sb.append('\n');
			}
		}

		if (!stories.isEmpty()) {
			sb.append("\nПродолжающиеся истории:\n");
			stories.forEach(t -> sb.append("- ").append(t).append('\n'));
		}

		if (!r.forecasts().isEmpty()) {
			sb.append("\nПрогнозы (расчёт по статистике, а не факт):\n");
			r.forecasts().forEach(f -> sb.append("- ").append(f.text()).append('\n'));
		}

		if (!headlines.isEmpty()) {
			sb.append("\nЗаголовки прошлых выпусков — не повторяй:\n");
			headlines.forEach(h -> sb.append("- ").append(h).append('\n'));
		}
		return sb.toString().strip();
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
