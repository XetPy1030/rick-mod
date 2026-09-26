package ru.xetpy.rikoshet.chronicle;

import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.chronicle.analysis.Metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Карточка игрока для /rick me и /rickadmin chronicle player: сегодня и стиль за две недели. */
public final class PlayerCard {
	private PlayerCard() {
	}

	/** Строки карточки. who — как назвать игрока по UUID (для лучшего друга). */
	public static List<String> lines(Map<String, Long> today, JsonObject profile, Function<UUID, String> who) {
		List<String> out = new ArrayList<>();
		long online = today.getOrDefault(Keys.ONLINE, 0L);
		StringBuilder head = new StringBuilder("Сегодня: ").append(Metrics.duration(online));
		List<String> acts = new ArrayList<>();
		today.entrySet().stream()
				.filter(e -> e.getKey().startsWith(Keys.ACT) && e.getValue() >= 60)
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.limit(3)
				.forEach(e -> acts.add(Activity.byId(e.getKey().substring(Keys.ACT.length())).map(Activity::ru).orElse(e.getKey())
						+ " " + Metrics.duration(e.getValue())));
		if (!acts.isEmpty()) {
			head.append(" · ").append(String.join(", ", acts));
		}
		out.add(head.toString());
		List<String> nums = new ArrayList<>();
		for (Metrics.Metric m : Metrics.ALL) {
			long v = today.getOrDefault(m.key(), 0L);
			if (v > 0 && !m.key().equals(Keys.ONLINE) && nums.size() < 6) {
				nums.add(m.label() + " " + m.show(v));
			}
		}
		if (!nums.isEmpty()) {
			out.add(String.join(", ", nums));
		}
		if (profile != null && profile.has("style")) {
			StringBuilder p = new StringBuilder("За две недели: ").append(profile.get("style").getAsString());
			if (profile.has("bestFriend") && !profile.get("bestFriend").isJsonNull()) {
				p.append(" · чаще всего с ").append(who.apply(UUID.fromString(profile.get("bestFriend").getAsString())));
			}
			if (profile.has("nemesis") && !profile.get("nemesis").isJsonNull()) {
				p.append(" · главная угроза — ").append(profile.get("nemesis").getAsString());
			}
			if (profile.has("typicalHour") && profile.get("typicalHour").getAsInt() >= 0) {
				p.append(" · обычно около ").append(profile.get("typicalHour").getAsInt()).append(":00");
			}
			out.add(p.toString());
		}
		return out;
	}
}
