package ru.xetpy.rikoshet.ai;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Одна попытка запроса для таблицы ai_log.
 *
 * @param status ok, invalid (не JSON или не по схеме), refused, error, timeout, late (ответ пришёл после дедлайна)
 * @param output сырой ответ модели, обрезанный; null, если ответа нет
 */
public record AiLogEntry(Instant ts, LocalDate day, String route, String tag, String model, String status,
		int inputTokens, int cachedTokens, int outputTokens, int reasoningTokens,
		long latencyMs, double costUsd, UUID player, String output, String error) {
}
