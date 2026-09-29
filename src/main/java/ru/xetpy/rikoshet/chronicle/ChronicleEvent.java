package ru.xetpy.rikoshet.chronicle;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * Заметное событие для летописи (таблица chronicle_event). score — значимость для газеты:
 * чем выше, тем скорее попадёт в выпуск. data — подробности, их читает анализ дня.
 *
 * @param uuid    о ком событие; null — о сервере
 * @param subject что именно: id измерения, предмета, достижения, моба
 */
public record ChronicleEvent(long ts, String day, UUID uuid, String type, String subject, int score, JsonObject data) {
	public static final String DEATH = "death";
	/** Впервые: измерение, биом (только первый на сервере), структура, предмет-веха. */
	public static final String FIRST = "first";
	/** Счётчик за всё время перешёл порог. */
	public static final String MILESTONE = "milestone";
	public static final String ADVANCEMENT = "advancement";
	/** Убит босс — по ванильной статистике. */
	public static final String BOSS = "boss";
	public static final String PET_DEATH = "pet_death";
	/** Погиб моб с именем от бирки. */
	public static final String NAMED_DEATH = "named_death";
	/** Игрок задержался у чужого дома, хозяина рядом нет. */
	public static final String VISIT = "visit";
	/** Сработал тотем бессмертия. */
	public static final String TOTEM = "totem";
	/** Заметка Рика из чата: обозвал, похвалил, пообещал (docs/design/chat.md#память-что-запомнить). В газету не идёт. */
	public static final String CHAT_NOTE = "chat_note";
}
