package ru.xetpy.rikoshet.ai;

import java.util.List;

/**
 * Маршрут ИИ-запросов: модели по порядку (первая — основная), размышления, лимит выхода,
 * таймаут одного запроса. Запасные модели перебирает мод, а не OpenRouter: у моделей разные
 * допустимые уровни размышлений, и невалидный JSON тоже повод перейти к следующей.
 * hedgeMillis > 0 — гонка: основная молчит столько — параллельно стартует вторая модель,
 * берётся первый годный ответ.
 */
public record AiRoute(String name, List<ModelSpec> models, String reasoning, int maxTokens, int timeoutSeconds, int hedgeMillis) {
	public AiRoute(String name, List<ModelSpec> models, String reasoning, int maxTokens, int timeoutSeconds) {
		this(name, models, reasoning, maxTokens, timeoutSeconds, 0);
	}
}
