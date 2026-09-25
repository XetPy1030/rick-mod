package ru.xetpy.rikoshet.persona;

import java.util.UUID;

/**
 * Роль игрока во вселенной: docs/design/player-roles.md. Только флейвор, без бонусов.
 *
 * @param name            ник на момент последнего изменения роли
 * @param archetypeManual архетип задан админом, а не определён по названию
 * @param note            описание для ИИ или null
 */
public record Role(UUID uuid, String name, String title, Archetype archetype, boolean archetypeManual, String note, long setAt) {
	public Role withTitle(String title, long now) {
		return new Role(uuid, name, title, archetypeManual ? archetype : Archetype.detect(title), archetypeManual, note, now);
	}

	public Role withNote(String note, long now) {
		return new Role(uuid, name, title, archetype, archetypeManual, note, now);
	}

	public Role withArchetype(Archetype archetype, long now) {
		return new Role(uuid, name, title, archetype, true, note, now);
	}

	public Role withName(String name) {
		return new Role(uuid, name, title, archetype, archetypeManual, note, setAt);
	}
}
