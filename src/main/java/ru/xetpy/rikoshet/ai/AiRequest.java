package ru.xetpy.rikoshet.ai;

import java.util.UUID;

/**
 * Готовый запрос: промпт уже собран в главном потоке (там читаются роли и онлайн),
 * дальше он уходит в пул без ссылок на мир.
 *
 * @param route  имя маршрута из конфига: flavor, dialogue…
 * @param schema имя JSON-схемы ответа в rikoshet/schemas
 * @param tag    что это за запрос для лога: death, join, admin_test…
 * @param player игрок, к которому относится запрос, или null
 */
public record AiRequest(String route, String schema, String system, String user, String tag, UUID player) {
}
