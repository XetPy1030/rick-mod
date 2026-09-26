package ru.xetpy.rikoshet.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Вспоминание: что из памяти относится к моменту. Детерминированно, без ИИ и эмбеддингов
 * (docs/adr/0007-memory-without-embeddings.md):
 * оценка = важность/100 · 0,5^(возраст/период) + 0,5 · доля совпавших тегов + 0,4, если про этого игрока.
 */
public final class Recall {
	static final double TAG_WEIGHT = 0.5;
	static final double ABOUT_WEIGHT = 0.4;
	/** Ниже этого — не вспоминаем вовсе. */
	static final double MIN_SCORE = 0.15;

	private Recall() {
	}

	/**
	 * Запрос: о ком (главный — первый), теги ситуации, бюджет символов.
	 */
	public record Query(List<UUID> subjects, Set<String> tags, int budgetChars, long now) {
	}

	public record Scored(Memory memory, double score) {
	}

	public static double score(Memory m, Query q) {
		double ageDays = Math.max(0, (q.now() - m.ts()) / 86_400_000.0);
		double fresh = m.importance() / 100.0 * Math.pow(0.5, ageDays / m.halfLifeDays());
		double tags = 0;
		if (!q.tags().isEmpty()) {
			int hit = 0;
			for (String t : q.tags()) {
				if (m.tags().contains(t)) {
					hit++;
				}
			}
			tags = (double) hit / q.tags().size();
		}
		double about = 0;
		if (!q.subjects().isEmpty() && m.about().contains(q.subjects().getFirst())) {
			about = ABOUT_WEIGHT;
		} else {
			for (UUID u : q.subjects()) {
				if (m.about().contains(u)) {
					about = ABOUT_WEIGHT / 2;
					break;
				}
			}
		}
		if (about == 0 && tags == 0) {
			return 0; // не про них и не про это — не вспоминаем
		}
		return fresh + TAG_WEIGHT * tags + about;
	}

	/** Лучшие по оценке, по одному на ключ, пока влезают в бюджет. */
	public static List<Scored> recall(List<Memory> candidates, Query q) {
		List<Scored> scored = new ArrayList<>();
		for (Memory m : candidates) {
			double s = score(m, q);
			if (s >= MIN_SCORE) {
				scored.add(new Scored(m, s));
			}
		}
		scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(x -> -x.memory().ts()));
		List<Scored> out = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		int used = 0;
		for (Scored s : scored) {
			int len = s.memory().text().length() + 3;
			if (!keys.add(s.memory().key()) || used + len > q.budgetChars()) {
				continue;
			}
			used += len;
			out.add(s);
		}
		return out;
	}
}
