package ru.xetpy.rikoshet.storage;

import java.util.UUID;

/** Профиль игрока. Время — миллисекунды эпохи. */
public record PlayerRecord(UUID uuid, String name, long firstSeen, long lastSeen, long playtimeSeconds, boolean aiOptOut) {
}
