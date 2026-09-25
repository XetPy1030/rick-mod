package ru.xetpy.rikoshet.persona;

import java.util.Set;

/**
 * Персонаж, от имени которого говорит ИИ. Промпт — rikoshet/prompts/personas/<id>.md,
 * разрешённые действия — по docs/architecture/ai-actions.md и карточке персонажа.
 */
public record Persona(String id, String displayName, int color, Set<String> allowedActions) {
	/** Рик Санчез. Цвет — «портальный» зелёный. */
	public static final Persona RICK = new Persona("rick", "Рик", 0x97CE4C, Set.of("remember"));
}
