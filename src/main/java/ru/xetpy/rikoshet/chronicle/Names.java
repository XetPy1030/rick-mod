package ru.xetpy.rikoshet.chronicle;

import ru.xetpy.rikoshet.flavor.DeathCauses;

import java.util.Map;

/**
 * Русские названия для фактов и газеты без ИИ. Серверу доступен только английский язык игры,
 * поэтому словарь свой и небольшой: измерения, структуры, предметы-вехи. Остальное —
 * id без подчёркиваний; ИИ-редакция переведёт сама.
 */
public final class Names {
	private Names() {
	}

	private static final Map<String, String> DIMENSIONS = Map.of(
			"overworld", "Верхний мир",
			"the_nether", "Незер",
			"the_end", "Край");

	private static final Map<String, String> STRUCTURES = Map.ofEntries(
			Map.entry("ancient_city", "древний город"),
			Map.entry("bastion_remnant", "бастион"),
			Map.entry("buried_treasure", "зарытое сокровище"),
			Map.entry("desert_pyramid", "пирамида в пустыне"),
			Map.entry("end_city", "город Края"),
			Map.entry("fortress", "адская крепость"),
			Map.entry("igloo", "иглу"),
			Map.entry("jungle_pyramid", "храм в джунглях"),
			Map.entry("mansion", "лесной особняк"),
			Map.entry("mineshaft", "заброшенная шахта"),
			Map.entry("mineshaft_mesa", "заброшенная шахта"),
			Map.entry("monument", "подводная крепость"),
			Map.entry("nether_fossil", "окаменелость в Незере"),
			Map.entry("ocean_ruin_cold", "подводные руины"),
			Map.entry("ocean_ruin_warm", "подводные руины"),
			Map.entry("pillager_outpost", "аванпост разбойников"),
			Map.entry("shipwreck", "затонувший корабль"),
			Map.entry("shipwreck_beached", "выброшенный на берег корабль"),
			Map.entry("stronghold", "крепость Края"),
			Map.entry("swamp_hut", "хижина ведьмы"),
			Map.entry("trail_ruins", "руины троп"),
			Map.entry("trial_chambers", "испытательные камеры"));

	private static final Map<String, String> ITEMS = Map.ofEntries(
			Map.entry("diamond", "алмаз"),
			Map.entry("ancient_debris", "древние обломки"),
			Map.entry("netherite_ingot", "незеритовый слиток"),
			Map.entry("netherite_sword", "незеритовый меч"),
			Map.entry("netherite_pickaxe", "незеритовая кирка"),
			Map.entry("netherite_axe", "незеритовый топор"),
			Map.entry("netherite_helmet", "незеритовый шлем"),
			Map.entry("netherite_chestplate", "незеритовый нагрудник"),
			Map.entry("netherite_leggings", "незеритовые поножи"),
			Map.entry("netherite_boots", "незеритовые ботинки"),
			Map.entry("netherite_upgrade_smithing_template", "шаблон незеритового улучшения"),
			Map.entry("elytra", "элитры"),
			Map.entry("totem_of_undying", "тотем бессмертия"),
			Map.entry("nether_star", "звезда Незера"),
			Map.entry("beacon", "маяк"),
			Map.entry("dragon_egg", "яйцо дракона"),
			Map.entry("dragon_head", "голова дракона"),
			Map.entry("trident", "трезубец"),
			Map.entry("heart_of_the_sea", "сердце моря"),
			Map.entry("conduit", "морской источник"),
			Map.entry("enchanted_golden_apple", "зачарованное золотое яблоко"),
			Map.entry("echo_shard", "осколок эха"),
			Map.entry("recovery_compass", "компас восстановления"),
			Map.entry("heavy_core", "тяжёлое ядро"),
			Map.entry("mace", "булава"),
			Map.entry("wither_skeleton_skull", "череп скелета-иссушителя"),
			Map.entry("shulker_shell", "панцирь шалкера"),
			Map.entry("shulker_box", "шалкеровый ящик"),
			Map.entry("end_crystal", "кристалл Края"),
			Map.entry("sniffer_egg", "яйцо нюхача"),
			Map.entry("music_disc_pigstep", "пластинка Pigstep"),
			Map.entry("music_disc_otherside", "пластинка otherside"),
			Map.entry("ominous_trial_key", "зловещий ключ испытаний"),
			Map.entry("blaze_rod", "огненный стержень"),
			Map.entry("ender_eye", "око Края"));

	/** Измерение по короткому id: «the_nether» → «Незер». */
	public static String dimension(String id) {
		return DIMENSIONS.getOrDefault(Keys.shortId(id), pretty(id));
	}

	public static String structure(String id) {
		String s = Keys.shortId(id);
		if (s.startsWith("village_")) {
			return "деревня";
		}
		if (s.startsWith("ruined_portal")) {
			return "разрушенный портал";
		}
		return STRUCTURES.getOrDefault(s, pretty(id));
	}

	public static String item(String id) {
		return ITEMS.getOrDefault(Keys.shortId(id), pretty(id));
	}

	/** Моб по короткому или полному id. */
	public static String mob(String id) {
		return DeathCauses.mob(id.contains(":") ? id : "minecraft:" + id);
	}

	/** «terralith:alpine_grove» → «alpine grove». */
	public static String pretty(String id) {
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		return path.replace('_', ' ').replace('/', ' ');
	}
}
