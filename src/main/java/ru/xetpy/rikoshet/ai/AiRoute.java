package ru.xetpy.rikoshet.ai;

import java.util.List;

/**
 * Маршрут ИИ-запросов: модели по порядку (первая — основная), размышления, лимит выхода,
 * таймаут одного запроса. Запасные модели перебирает мод, а не OpenRouter: у моделей разные
 * допустимые уровни размышлений, и невалидный JSON тоже повод перейти к следующей.
 */
public record AiRoute(String name, List<ModelSpec> models, String reasoning, int maxTokens, int timeoutSeconds) {
}
