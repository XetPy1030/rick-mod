package ru.xetpy.rikoshet.ai;

import com.google.gson.JsonObject;

/**
 * Результат запроса к маршруту.
 *
 * @param value    ответ, прошедший схему; null, если статус не OK
 * @param model    модель, которая ответила (или последняя попробованная)
 * @param attempts сколько попыток ушло в сеть
 */
public record AiResult(AiStatus status, JsonObject value, String model, double costUsd, long latencyMs, int attempts, String error) {
	public static AiResult local(AiStatus status, String error) {
		return new AiResult(status, null, null, 0, 0, 0, error);
	}

	public boolean ok() {
		return status == AiStatus.OK;
	}
}
