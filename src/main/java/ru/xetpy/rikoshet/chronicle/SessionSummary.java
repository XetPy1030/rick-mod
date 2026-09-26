package ru.xetpy.rikoshet.chronicle;

import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.chronicle.analysis.Metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Итог сессии: JSON для player_session и фраза для Рика — «в прошлый раз ты…», прощание.
 * Только заметное: занятия от 5 минут и крупные счётчики.
 */
public final class SessionSummary {
	private SessionSummary() {
	}

	/** Что попадает в итог сессии, по порядку. */
	private static final List<String[]> NUMBERS = List.of(
			new String[] {Keys.PLACED, "поставил блоков"},
			new String[] {Keys.MINED, "добыл блоков"},
			new String[] {Keys.DIAMONDS, "алмазной руды"},
			new String[] {Keys.DEBRIS, "древних обломков"},
			new String[] {Keys.LOGS, "срубил брёвен"},
			new String[] {Keys.CROPS, "собрал урожая"},
			new String[] {Keys.HOSTILE, "убил монстров"},
			new String[] {Keys.FISH, "поймал рыб"},
			new String[] {Keys.TRADES, "сделок с жителями"},
			new String[] {Keys.ENCHANTS, "зачаровал"},
			new String[] {Keys.CRAFTED, "скрафтил"});

	public static JsonObject json(Map<String, Long> session, long seconds, int deaths) {
		JsonObject o = new JsonObject();
		o.addProperty("seconds", seconds);
		o.addProperty("deaths", deaths);
		JsonObject act = new JsonObject();
		session.entrySet().stream()
				.filter(e -> e.getKey().startsWith(Keys.ACT) && e.getValue() >= 300)
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.forEach(e -> act.addProperty(e.getKey().substring(Keys.ACT.length()), e.getValue()));
		o.add("act", act);
		JsonObject x = new JsonObject();
		for (String[] n : NUMBERS) {
			long v = session.getOrDefault(n[0], 0L);
			if (v > 0) {
				x.addProperty(n[0], v);
			}
		}
		long dist = session.getOrDefault(Keys.DISTANCE, 0L);
		if (dist > 0) {
			x.addProperty(Keys.DISTANCE, dist);
		}
		o.add("x", x);
		return o;
	}

	/** «2 ч 10 мин: стройка 1 ч 10 мин, шахта 30 мин; поставил блоков 800, алмазной руды 12; смертей 1». */
	public static String text(JsonObject o) {
		if (o == null || !o.has("seconds")) {
			return null;
		}
		StringBuilder sb = new StringBuilder(Metrics.duration(o.get("seconds").getAsLong()));
		List<String> acts = new ArrayList<>();
		if (o.has("act")) {
			for (var e : o.getAsJsonObject("act").entrySet()) {
				String name = Activity.byId(e.getKey()).map(Activity::ru).orElse(e.getKey());
				acts.add(name + " " + Metrics.duration(e.getValue().getAsLong()));
				if (acts.size() == 3) {
					break;
				}
			}
		}
		if (!acts.isEmpty()) {
			sb.append(": ").append(String.join(", ", acts));
		}
		List<String> nums = new ArrayList<>();
		if (o.has("x")) {
			JsonObject x = o.getAsJsonObject("x");
			for (String[] n : NUMBERS) {
				if (x.has(n[0]) && nums.size() < 4) {
					nums.add(n[1] + " " + Metrics.count(x.get(n[0]).getAsLong()));
				}
			}
			if (x.has(Keys.DISTANCE) && x.get(Keys.DISTANCE).getAsLong() >= 1000) {
				nums.add("прошёл " + Metrics.km(x.get(Keys.DISTANCE).getAsLong()));
			}
		}
		if (!nums.isEmpty()) {
			sb.append("; ").append(String.join(", ", nums));
		}
		long deaths = o.has("deaths") ? o.get("deaths").getAsLong() : 0;
		if (deaths > 0) {
			sb.append("; смертей ").append(deaths);
		}
		return sb.toString();
	}
}
