package ru.xetpy.rikoshet.ai.action;

/**
 * Действие из ответа ИИ, уже проверенное {@link ActionValidator}. Полный список возможных
 * действий — docs/architecture/ai-actions.md; здесь только то, что уже реализовано.
 */
public sealed interface AiAction {
	/** Запомнить факт об игроке: до 300 символов. */
	record Remember(String note) implements AiAction {
	}

	/** «Полезность для науки»: delta от −5 до +5, не ноль. Лимит ±10 за разговор держит код разговора. */
	record ChangeReputation(int delta, String reason) implements AiAction {
	}

	/** Выдать квест из списка доступных игроку. */
	record GiveQuest(String quest) implements AiAction {
	}

	/** Попросить код проверить активный квест. Выполнен ли он, решает код, а не модель. */
	record CompleteQuest(String quest) implements AiAction {
	}

	/** Подарок из таблицы наград: id записи и количество в её пределах. */
	record GiveItem(String reward, int count) implements AiAction {
	}
}
