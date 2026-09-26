package ru.xetpy.rikoshet.chronicle;

import java.util.EnumMap;
import java.util.Map;

/**
 * Занятие окна по приросту статистики и замерам положения. Очки примерно равны «минутам
 * заметной работы» за пятиминутное окно: шахтёр копает 100–300 блоков, строитель ставит
 * 100–400, путник проходит 1000–2000 блоков. Порог для короткого окна уменьшается
 * пропорционально. Веса подобраны на глаз; сырые счётчики остаются в БД, так что
 * классификацию можно пересчитать.
 */
public final class ActivityClassifier {
	/** Очков за 5 минут, меньше — «прочее». */
	static final double THRESHOLD = 12;
	/** Доля неподвижных замеров, с которой окно — AFK. */
	static final double AFK_SHARE = 0.6;

	private ActivityClassifier() {
	}

	/** Сводка замеров окна. */
	public record Window(long seconds, int samples, int idle, int underground, int newCells) {
	}

	public static Activity classify(Map<String, Long> d, Window w) {
		return classifyWithScores(d, w, null);
	}

	/** scores — куда сложить очки по занятиям (для отладки и тестов), может быть null. */
	public static Activity classifyWithScores(Map<String, Long> d, Window w, Map<Activity, Double> scores) {
		if (w.samples() > 0 && w.idle() >= w.samples() * AFK_SHARE) {
			return Activity.AFK;
		}
		Map<Activity, Double> s = scores != null ? scores : new EnumMap<>(Activity.class);
		double logs = v(d, Keys.LOGS);
		double crops = v(d, Keys.CROPS);
		double stone = Math.max(0, v(d, Keys.MINED) - logs - crops);
		double mining = stone + 5 * v(d, Keys.ORES);
		if (stone >= 20 && w.samples() > 0 && w.underground() * 2 >= w.samples()) {
			mining += 10;
		}
		s.put(Activity.MINING, mining);
		s.put(Activity.LUMBER, 2 * logs);
		s.put(Activity.BUILDING, v(d, Keys.PLACED));
		s.put(Activity.FARMING, 0.5 * crops + 0.5 * v(d, Keys.PLANTED) + 4 * v(d, Keys.BRED) + v(d, Keys.PASSIVE));
		s.put(Activity.COMBAT, 4 * v(d, Keys.HOSTILE) + v(d, Keys.DAMAGE_DEALT) / 100 + 10 * v(d, Keys.PLAYER_KILLS));
		s.put(Activity.EXPLORING, v(d, Keys.DISTANCE) / 40 + 5 * w.newCells());
		s.put(Activity.FISHING, 8 * v(d, Keys.FISH));
		s.put(Activity.TRADING, 6 * v(d, Keys.TRADES) + 8 * v(d, Keys.ENCHANTS) + 3 * v(d, Keys.BREWING));
		s.put(Activity.CRAFTING, 0.3 * v(d, Keys.CRAFTED) + 2 * v(d, Keys.STATIONS));

		Activity best = Activity.OTHER;
		double bestScore = 0;
		for (var e : s.entrySet()) {
			if (e.getValue() > bestScore) {
				best = e.getKey();
				bestScore = e.getValue();
			}
		}
		double threshold = Math.max(4, THRESHOLD * Math.min(1.0, w.seconds() / 300.0));
		return bestScore >= threshold ? best : Activity.OTHER;
	}

	private static double v(Map<String, Long> d, String key) {
		Long x = d.get(key);
		return x == null ? 0 : x;
	}
}
