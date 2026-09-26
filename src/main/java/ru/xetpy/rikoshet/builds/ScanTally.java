package ru.xetpy.rikoshet.builds;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Счёт одного скана: рукотворные по видам, материалы, диапазон высот. */
public final class ScanTally {
	/** Секция считается «застроенной», если в ней столько рукотворных блоков. */
	static final int SECTION_MIN = 8;

	long artificial;
	final Map<BlockKinds.Kind, Long> kinds = new EnumMap<>(BlockKinds.Kind.class);
	final Map<String, Long> materials = new HashMap<>();
	private final Map<Integer, Long> perSection = new HashMap<>();

	public void add(BlockKinds.Kind k, String id, long n, int sectionY) {
		if (!k.artificial() || n <= 0) {
			return;
		}
		artificial += n;
		kinds.merge(k, n, Long::sum);
		materials.merge(id, n, Long::sum);
		perSection.merge(sectionY, n, Long::sum);
	}

	/**
	 * Высоты главной постройки (по секциям 16 блоков) или null: самая застроенная секция и
	 * соседние подряд, где рукотворного не меньше 3% — чтобы доски шахты глубоко внизу не
	 * превращали дом в «башню высотой 150 блоков».
	 */
	int[] heights() {
		Integer peak = null;
		for (var e : perSection.entrySet()) {
			if (peak == null || e.getValue() > perSection.get(peak)) {
				peak = e.getKey();
			}
		}
		if (peak == null || perSection.get(peak) < SECTION_MIN) {
			return null;
		}
		long min = Math.max(SECTION_MIN, artificial * 3 / 100);
		int lo = peak;
		int hi = peak;
		while (perSection.getOrDefault(lo - 1, 0L) >= min) {
			lo--;
		}
		while (perSection.getOrDefault(hi + 1, 0L) >= min) {
			hi++;
		}
		return new int[] {lo * 16, hi * 16 + 15};
	}

	/** Топ материалов: id → блоков. */
	List<Map.Entry<String, Long>> topMaterials(int n) {
		List<Map.Entry<String, Long>> l = new ArrayList<>(materials.entrySet());
		l.sort(Map.Entry.<String, Long>comparingByValue().reversed());
		return l.subList(0, Math.min(n, l.size()));
	}

	public JsonObject json(String biome, boolean structure) {
		JsonObject o = new JsonObject();
		o.addProperty("artificial", artificial);
		JsonObject k = new JsonObject();
		kinds.forEach((kind, v) -> k.addProperty(kind.id(), v));
		o.add("kinds", k);
		JsonArray m = new JsonArray();
		for (var e : topMaterials(8)) {
			JsonArray pair = new JsonArray();
			pair.add(e.getKey());
			pair.add(e.getValue());
			m.add(pair);
		}
		o.add("materials", m);
		int[] h = heights();
		if (h != null) {
			o.addProperty("y_min", h[0]);
			o.addProperty("y_max", h[1]);
		}
		o.addProperty("biome", biome);
		o.addProperty("structure", structure);
		return o;
	}
}
