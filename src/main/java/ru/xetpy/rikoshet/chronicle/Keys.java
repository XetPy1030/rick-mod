package ru.xetpy.rikoshet.chronicle;

/**
 * Ключи счётчиков в player_stat_daily и player_total. Ванильные — «тип:id» без пространства
 * minecraft ({@link StatKeys}), свои — с префиксом x:, время по занятиям, измерениям и биомам —
 * act:, dim:, biome:. Пары игроков — отдельно, в pair_daily ({@link ru.xetpy.rikoshet.chronicle.social.PairKeys}).
 * Описание — docs/design/chronicle.md.
 */
public final class Keys {
	private Keys() {
	}

	// ---------- свои ----------
	/** Секунды онлайн после пароля. */
	public static final String ONLINE = "x:online";
	/** Секунды без движения и поворота головы, от минуты подряд. */
	public static final String AFK = "x:afk";
	/** Секунды под землёй: Верхний мир, ниже уровня моря, неба не видно. */
	public static final String UNDERGROUND = "x:under";
	public static final String MINED = "x:mined";
	public static final String ORES = "x:ores";
	public static final String DIAMONDS = "x:diamonds";
	public static final String DEBRIS = "x:debris";
	public static final String LOGS = "x:logs";
	public static final String CROPS = "x:crops";
	/** Посажено: семена, саженцы. */
	public static final String PLANTED = "x:planted";
	/** Поставлено блоков (кроме посадок). */
	public static final String PLACED = "x:placed";
	public static final String CRAFTED = "x:crafted";
	public static final String HOSTILE = "x:hostile";
	public static final String PASSIVE = "x:passive";
	/** Блоков пройдено любым способом, кроме падения. */
	public static final String DISTANCE = "x:distance";
	/** Блоков на элитрах. */
	public static final String AVIATE = "x:aviate";
	/** Использований верстаков, печей, наковален и прочих станций. */
	public static final String STATIONS = "x:stations";
	/** Клеток 64×64, где игрок раньше не бывал. */
	public static final String NEW_CELLS = "x:new_cells";
	/** Клеток, где до него не бывал никто. */
	public static final String FIRST_CELLS = "x:first_cells";
	public static final String NEW_BIOMES = "x:new_biomes";
	public static final String CHAT = "x:chat";
	/** Сообщений, где упомянут Рик. */
	public static final String CHAT_RICK = "x:chat_rick";
	public static final String TOTEMS = "x:totems";

	// ---------- ванильные, которые читаем по имени ----------
	public static final String DEATHS = "custom:deaths";
	public static final String MOB_KILLS = "custom:mob_kills";
	public static final String PLAYER_KILLS = "custom:player_kills";
	public static final String FISH = "custom:fish_caught";
	public static final String BRED = "custom:animals_bred";
	public static final String TRADES = "custom:traded_with_villager";
	public static final String ENCHANTS = "custom:enchant_item";
	public static final String BREWING = "custom:interact_with_brewingstand";
	public static final String DAMAGE_DEALT = "custom:damage_dealt";
	public static final String DAMAGE_TAKEN = "custom:damage_taken";
	public static final String JUMPS = "custom:jump";
	public static final String SLEEPS = "custom:sleep_in_bed";
	/** Тики в игре — только для счётчика за всё время (часы). */
	public static final String PLAY_HOURS = "x:play_hours";

	// ---------- префиксы ----------
	public static final String ACT = "act:";
	public static final String DIM = "dim:";
	public static final String BIOME = "biome:";

	/** Короткий id: без «minecraft:». */
	public static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}
}
