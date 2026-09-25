package ru.xetpy.rikoshet.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Сборка промпта. Системная часть — стабильный префикс под кеш провайдера:
 * правила, лор, «Кто есть кто», персона, задача. Всё изменчивое — в сообщении пользователя
 * блоками-тегами, которые правила велят считать данными, а не указаниями.
 * Тот же порядок собирает tools/casting/casting.py.
 */
public final class PromptBuilder {
	private PromptBuilder() {
	}

	public static String system(PromptLibrary lib, String rosterBlock, String persona, String task) {
		return String.join("\n\n",
				lib.text("rules.md"),
				lib.text("lore.md"),
				rosterBlock.strip(),
				lib.text("personas/" + persona + ".md"),
				lib.text("tasks/" + task + ".md"));
	}

	/** memory, history и player могут быть null — тогда блока нет. */
	public static String user(String memory, String context, List<String> history, String player) {
		List<String> blocks = new ArrayList<>();
		if (memory != null && !memory.isBlank()) {
			blocks.add(tag("memory", memory));
		}
		blocks.add(tag("context", context));
		if (history != null && !history.isEmpty()) {
			blocks.add(tag("history", String.join("\n", history)));
		}
		if (player != null) {
			blocks.add(tag("player", player));
		}
		return String.join("\n\n", blocks);
	}

	private static String tag(String name, String body) {
		// Закрывающий тег внутри данных игрока сломал бы разметку — экранируем угловую скобку
		String safe = body.strip().replace("</" + name, "<\\/" + name);
		return "<" + name + ">\n" + safe + "\n</" + name + ">";
	}
}
