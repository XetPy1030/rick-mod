package ru.xetpy.rikoshet.quest;

import java.util.List;

/**
 * Уровни «Полезности для науки» (docs/design/characters/rick.md#репутация-полезность-для-науки).
 * Порядок важен: награды и квесты открываются «с уровня и выше».
 */
public enum Level {
	BIOMASS("biomass", -100, "Биомусор", "по делу с ним не разговариваешь, только оскорбления; заданий и наград не даёшь"),
	SUBJECT("subject", -50, "Подопытный", "используешь для опасных экспериментов, доверия ноль"),
	LAB("lab", 0, "Лаборант", "обычные задания, обычное презрение"),
	ASSISTANT("assistant", 30, "Ассистент", "доверяешь задания посложнее, иногда делишься наблюдениями"),
	ALMOST_MORTY("almost_morty", 60, "Почти-Морти", "редкие задания, изредка личные истории"),
	GENIUS("genius", 90, "Гений второго сорта", "признаёшь сквозь зубы; лучшие награды");

	public static final int MIN = -100;
	public static final int MAX = 100;
	private static final List<Level> ALL = List.of(values());

	public final String id;
	public final int from;
	public final String title;
	/** Как Рик к нему относится — строка для промпта. */
	public final String tone;

	Level(String id, int from, String title, String tone) {
		this.id = id;
		this.from = from;
		this.title = title;
		this.tone = tone;
	}

	public static Level of(int value) {
		Level out = BIOMASS;
		for (Level l : ALL) {
			if (value >= l.from) {
				out = l;
			}
		}
		return out;
	}

	/** Уровень по id из конфига; неизвестный — null. */
	public static Level byId(String id) {
		for (Level l : ALL) {
			if (l.id.equals(id)) {
				return l;
			}
		}
		return null;
	}

	public boolean atLeast(Level other) {
		return ordinal() >= other.ordinal();
	}
}
