package ru.xetpy.rikoshet.chronicle.social;

import java.util.Set;
import java.util.UUID;

/**
 * Ключи pair_daily. Симметричные хранятся с a &lt; b по строке UUID, направленные — a сделал, b
 * получил. Описание — docs/design/chronicle.md#взаимодействия-игроков.
 */
public final class PairKeys {
	private PairKeys() {
	}

	// ---------- симметричные ----------
	/** Секунды в 32 блоках друг от друга. */
	public static final String TOGETHER = "together";
	/** Секунды в 8 блоках: на таком расстоянии передают вещи. */
	public static final String CLOSE = "close";
	/** Секунды онлайн одновременно, где бы ни были. */
	public static final String OVERLAP = "overlap";
	/** Префикс: секунды, когда рядом и заняты одним делом — «joint:mining». */
	public static final String JOINT = "joint:";
	/** Реплики в чате в ответ друг другу (в течение 30 с). */
	public static final String DIALOGUE = "dialogue";

	// ---------- направленные: a → b ----------
	/** a назвал b в чате по нику или роли. */
	public static final String MENTION = "mention";
	/** Оценка ценности того, что a, похоже, передал b. */
	public static final String GIFT = "gift";
	/** a убил моба, который целился в b. */
	public static final String RESCUE = "rescue";
	/** a убил моба, который недавно убил b. */
	public static final String REVENGE = "revenge";
	/** Урон от a по b, ×10 как в ванильной статистике. */
	public static final String PVP_DAMAGE = "pvp_damage";
	public static final String PVP_KILL = "pvp_kill";
	/** a убил питомца b. */
	public static final String PET_KILL = "pet_kill";
	/** a был рядом, когда умер b. */
	public static final String WITNESS = "witness";
	/** Секунды a у дома b, хозяина рядом нет. */
	public static final String VISIT = "visit";
	/** Секунды a у дома b при хозяине. */
	public static final String HOST = "host";
	/** Блоков a поставил у дома b при хозяине. */
	public static final String BUILD_AT = "build_at";
	/** Блоков a поставил у дома b без хозяина. */
	public static final String BUILD_ABSENT = "build_absent";
	/** Блоков a добыл у дома b без хозяина. */
	public static final String MINE_ABSENT = "mine_absent";
	/** Ценность вещей погибшего b, которые подобрал a. */
	public static final String LOOT = "loot";

	private static final Set<String> SYMMETRIC = Set.of(TOGETHER, CLOSE, OVERLAP, DIALOGUE);

	public static boolean symmetric(String key) {
		return SYMMETRIC.contains(key) || key.startsWith(JOINT);
	}

	/** Строка-ключ пары для симметричного счётчика: меньший UUID первым. */
	public static String pair(UUID x, UUID y) {
		String a = x.toString();
		String b = y.toString();
		return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
	}

	/** Строка-ключ направленной пары. */
	public static String directed(UUID from, UUID to) {
		return from + "|" + to;
	}
}
