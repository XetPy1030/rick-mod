package ru.xetpy.rikoshet.chronicle.social;

import java.util.Map;

/**
 * Ценность предмета в «алмазах» для оценки подарков и мародёрства. Не экономика, а грубая шкала:
 * отличить «отдал незеритовую кирку» от «уронил булыжник». Незнакомое весит 0,05.
 */
final class GiftValues {
	private GiftValues() {
	}

	private static final Map<String, Double> EXACT = Map.ofEntries(
			Map.entry("diamond", 1.0),
			Map.entry("diamond_block", 9.0),
			Map.entry("emerald", 0.3),
			Map.entry("emerald_block", 2.7),
			Map.entry("netherite_ingot", 5.0),
			Map.entry("netherite_block", 45.0),
			Map.entry("netherite_scrap", 1.2),
			Map.entry("ancient_debris", 1.2),
			Map.entry("gold_ingot", 0.2),
			Map.entry("gold_block", 1.8),
			Map.entry("iron_ingot", 0.1),
			Map.entry("iron_block", 0.9),
			Map.entry("enchanted_book", 0.5),
			Map.entry("golden_apple", 0.8),
			Map.entry("enchanted_golden_apple", 10.0),
			Map.entry("totem_of_undying", 3.0),
			Map.entry("elytra", 10.0),
			Map.entry("trident", 4.0),
			Map.entry("mace", 6.0),
			Map.entry("nether_star", 8.0),
			Map.entry("beacon", 10.0),
			Map.entry("ender_pearl", 0.1),
			Map.entry("blaze_rod", 0.2),
			Map.entry("experience_bottle", 0.1),
			Map.entry("name_tag", 0.3),
			Map.entry("saddle", 0.3),
			Map.entry("heart_of_the_sea", 3.0),
			Map.entry("netherite_upgrade_smithing_template", 3.0),
			Map.entry("shulker_shell", 1.0));

	private static final String[] GEAR = {"_sword", "_pickaxe", "_axe", "_shovel", "_hoe", "_helmet", "_chestplate", "_leggings", "_boots"};

	static double of(String item) {
		Double exact = EXACT.get(item);
		if (exact != null) {
			return exact;
		}
		if (item.endsWith("shulker_box")) {
			return 5.0;
		}
		for (String g : GEAR) {
			if (item.endsWith(g)) {
				if (item.startsWith("netherite_")) {
					return 6.0;
				}
				if (item.startsWith("diamond_")) {
					return 2.0;
				}
				return 0.1;
			}
		}
		return 0.05;
	}
}
