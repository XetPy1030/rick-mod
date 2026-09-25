package ru.xetpy.rikoshet.persona;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Блок «Кто есть кто» для системного промпта: шапка из prompts/roster.md и строка на каждого
 * игрока с ролью. Порядок стабильный (по нику), чтобы префикс промпта кешировался у провайдера.
 * Тот же формат собирает tools/casting/casting.py.
 */
public final class Roster {
	private Roster() {
	}

	/**
	 * @param names   актуальные ники по UUID (из профилей игроков); нет ника — берётся из роли
	 * @param visible кого можно упоминать: игроки с /rick off в ростер не попадают
	 */
	public static String block(String header, Iterable<Role> roles, Map<UUID, String> names, Predicate<UUID> visible) {
		List<String[]> rows = new ArrayList<>();
		for (Role r : roles) {
			if (!visible.test(r.uuid())) {
				continue;
			}
			String name = names.getOrDefault(r.uuid(), r.name());
			rows.add(new String[] {name, line(name, r)});
		}
		rows.sort(Comparator.comparing((String[] row) -> row[0], String.CASE_INSENSITIVE_ORDER).thenComparing(row -> row[0]));
		StringBuilder sb = new StringBuilder(header.strip()).append("\n");
		if (rows.isEmpty()) {
			sb.append("\nРолей пока ни у кого нет.");
		}
		for (String[] row : rows) {
			sb.append('\n').append(row[1]);
		}
		return sb.toString();
	}

	static String line(String name, Role r) {
		String line = "- " + name + " — " + r.title() + " (" + r.archetype().id() + ")";
		if (r.note() != null && !r.note().isBlank()) {
			line += ": " + r.note().strip();
		}
		return line;
	}

	/** Как назвать игрока в контексте: «ник — роль» или «ник — роли нет». */
	public static String describe(String name, Role role) {
		return role == null ? name + " — роли нет" : name + " — " + role.title();
	}
}
