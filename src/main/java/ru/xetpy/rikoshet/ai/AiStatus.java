package ru.xetpy.rikoshet.ai;

/** Итог запроса. Всё, кроме OK, значит «выводим заготовку или молчим». */
public enum AiStatus {
	OK,
	/** Нет ключа OpenRouter. */
	NO_KEY,
	/** ai.enabled = false. */
	DISABLED,
	/** /rickadmin ai pause или OpenRouter не принял ключ. */
	PAUSED,
	/** Дневной бюджет исчерпан. */
	BUDGET,
	/** Лимит запросов в минуту. */
	RATE_LIMIT,
	/** Все потоки заняты, очередь полна, или MSPT выше mspt_hard. */
	OVERLOAD,
	/** Не уложились в timeout_seconds маршрута. */
	TIMEOUT,
	/** Все модели ответили ошибкой или невалидным JSON. */
	FAILED,
	/** Модель отказалась отвечать, запасная тоже. */
	REFUSED;

	/** Запрос вообще не уходил в сеть. */
	public boolean local() {
		return this == NO_KEY || this == DISABLED || this == PAUSED || this == BUDGET || this == RATE_LIMIT || this == OVERLOAD;
	}
}
