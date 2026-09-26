package ru.xetpy.rikoshet.chronicle.analysis;

import ru.xetpy.rikoshet.chronicle.Keys;

import java.util.List;
import java.util.Locale;
import java.util.function.LongFunction;

/**
 * Метрики дня для рекордов, «необычного» и сводок. floor — ниже этого рекорд не рекорд
 * (иначе первый же день даст десяток «рекордов» по мелочам).
 */
public final class Metrics {
	private Metrics() {
	}

	public record Metric(String key, String label, long floor, LongFunction<String> format) {
		public String show(long v) {
			return format.apply(v);
		}
	}

	public static final List<Metric> ALL = List.of(
			new Metric(Keys.DEATHS, "смертей", 5, Metrics::count),
			new Metric(Keys.MOB_KILLS, "убито мобов", 100, Metrics::count),
			new Metric(Keys.MINED, "добыто блоков", 2000, Metrics::count),
			new Metric(Keys.ORES, "добыто руды", 64, Metrics::count),
			new Metric(Keys.DIAMONDS, "добыто алмазной руды", 16, Metrics::count),
			new Metric(Keys.DEBRIS, "добыто древних обломков", 8, Metrics::count),
			new Metric(Keys.PLACED, "поставлено блоков", 1000, Metrics::count),
			new Metric(Keys.LOGS, "срублено брёвен", 300, Metrics::count),
			new Metric(Keys.CROPS, "собрано урожая", 500, Metrics::count),
			new Metric(Keys.DISTANCE, "пройдено", 20_000, Metrics::km),
			new Metric(Keys.AVIATE, "пролетено на элитрах", 20_000, Metrics::km),
			new Metric(Keys.FISH, "поймано рыбы", 30, Metrics::count),
			new Metric(Keys.TRADES, "сделок с жителями", 30, Metrics::count),
			new Metric(Keys.BRED, "выведено животных", 30, Metrics::count),
			new Metric(Keys.ENCHANTS, "зачарований", 10, Metrics::count),
			new Metric(Keys.CRAFTED, "скрафчено предметов", 1000, Metrics::count),
			new Metric(Keys.JUMPS, "прыжков", 2000, Metrics::count),
			new Metric(Keys.ONLINE, "в игре", 4 * 3600, Metrics::duration),
			new Metric(Keys.NEW_CELLS, "новых мест на карте", 60, Metrics::count),
			new Metric(Keys.DAMAGE_TAKEN, "получено урона", 1000, v -> count(v / 20) + " сердец"));

	public static Metric byKey(String key) {
		for (Metric m : ALL) {
			if (m.key().equals(key)) {
				return m;
			}
		}
		return null;
	}

	/** 12345 → «12 345». */
	public static String count(long v) {
		return String.format(Locale.ROOT, "%,d", v).replace(',', ' ');
	}

	/** Блоки → «12,4 км» или «850 м». */
	public static String km(long blocks) {
		if (blocks < 1000) {
			return blocks + " м";
		}
		return String.format(Locale.ROOT, "%.1f км", blocks / 1000.0).replace('.', ',');
	}

	/** Секунды → «2 ч 10 мин», «15 мин». */
	public static String duration(long seconds) {
		long h = seconds / 3600;
		long m = seconds % 3600 / 60;
		if (h == 0) {
			return m + " мин";
		}
		return m == 0 ? h + " ч" : h + " ч " + m + " мин";
	}

	/** Склонение: plural(3, "раз", "раза", "раз"). */
	public static String plural(long n, String one, String few, String many) {
		long a = Math.abs(n) % 100;
		long b = a % 10;
		if (a > 10 && a < 20) {
			return many;
		}
		if (b == 1) {
			return one;
		}
		if (b >= 2 && b <= 4) {
			return few;
		}
		return many;
	}
}
