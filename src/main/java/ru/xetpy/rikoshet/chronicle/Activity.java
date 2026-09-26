package ru.xetpy.rikoshet.chronicle;

import java.util.Locale;
import java.util.Optional;

/** Чем игрок был занят в окне между снимками статистики (docs/design/chronicle.md#занятия). */
public enum Activity {
	MINING("шахта"),
	LUMBER("лесоповал"),
	BUILDING("стройка"),
	FARMING("ферма"),
	COMBAT("бой"),
	EXPLORING("путешествия"),
	FISHING("рыбалка"),
	TRADING("торговля и чары"),
	CRAFTING("крафт"),
	AFK("AFK"),
	OTHER("прочее");

	private final String ru;

	Activity(String ru) {
		this.ru = ru;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public String ru() {
		return ru;
	}

	/** Ключ счётчика секунд: «act:mining». */
	public String key() {
		return Keys.ACT + id();
	}

	/** Занятие, которое что-то говорит о стиле игры: не AFK и не «прочее». */
	public boolean meaningful() {
		return this != AFK && this != OTHER;
	}

	public static Optional<Activity> byId(String id) {
		for (Activity a : values()) {
			if (a.id().equals(id)) {
				return Optional.of(a);
			}
		}
		return Optional.empty();
	}
}
