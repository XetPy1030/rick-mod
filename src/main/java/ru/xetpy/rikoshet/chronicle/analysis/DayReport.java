package ru.xetpy.rikoshet.chronicle.analysis;

import java.util.List;
import java.util.Map;

/**
 * Итоги дня: сводка сервера, игроки, связи, факты по значимости, прогнозы, профили. Хранится
 * JSON-ом в chronicle_day, читается газетой, памятью и командами. UUID — строками.
 */
public record DayReport(
		String day,
		String weekday,
		ServerDay server,
		List<PlayerDay> players,
		List<Relation> relations,
		List<Fact> facts,
		List<Forecast> forecasts,
		List<Profile> profiles
) {
	public record ServerDay(int players, long onlineSeconds, int peak, String peakAt, long deaths, long mobKills,
			long mined, long placed, long distance, String topKiller, int topKillerCount) {
	}

	/**
	 * @param activities занятие → секунды, по убыванию
	 * @param numbers    заметные счётчики дня: подпись → значение текстом
	 */
	public record PlayerDay(String uuid, String who, long online, long afk, int sessions, String first, String last,
			Map<String, Long> activities, Map<String, String> numbers, List<String> deaths, List<String> partners,
			int newBiomes, long chat) {
	}

	/** Связь пары за 14 дней: тип, близость, напряжение, перемена за неделю. */
	public record Relation(String a, String b, String aWho, String bWho, String type, String text, double affinity,
			double tension, String trend) {
	}

	/** Факт для газеты и памяти. kind — какой детектор, score — значимость. */
	public record Fact(String kind, String uuid, int score, String text) {
	}

	/** Прогноз: расчёт, а не факт. p — вероятность, если есть. */
	public record Forecast(String kind, String uuid, double p, String text) {
	}

	/**
	 * Профиль игрока за 14 дней.
	 *
	 * @param style        «шахтёр и строитель»
	 * @param shares       доли занятий
	 * @param typicalHour  час, когда чаще онлайн, или -1
	 * @param nemesis      кто чаще убивает, текстом, или null
	 * @param bestFriend   UUID, с кем больше всего времени, или null
	 */
	public record Profile(String uuid, String style, Map<String, Double> shares, int typicalHour, long avgSession,
			int activeDays, String nemesis, int nemesisCount, int deaths, String bestFriend, long bestFriendSeconds,
			String favoriteBiome) {
	}
}
