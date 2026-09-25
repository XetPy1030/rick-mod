package ru.xetpy.rikoshet.ai.action;

/**
 * Действие из ответа ИИ, уже проверенное {@link ActionValidator}. Полный список возможных
 * действий — docs/architecture/ai-actions.md; здесь только то, что уже реализовано.
 */
public sealed interface AiAction {
	/** Запомнить факт об игроке: до 300 символов. */
	record Remember(String note) implements AiAction {
	}
}
