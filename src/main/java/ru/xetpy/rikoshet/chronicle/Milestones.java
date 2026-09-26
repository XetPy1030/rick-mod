package ru.xetpy.rikoshet.chronicle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Что считается вехой: пороги счётчиков за всё время, предметы, которые впервые получить —
 * событие, боссы, структуры и измерения. Числа — очки значимости для газеты.
 */
public final class Milestones {
	private Milestones() {
	}

	/** Пороги счётчиков за всё время по возрастанию. */
	public static final Map<String, long[]> LIFETIME;

	static {
		Map<String, long[]> m = new LinkedHashMap<>();
		m.put(Keys.DEATHS, new long[] {10, 25, 50, 100, 250, 500, 1000});
		m.put(Keys.MOB_KILLS, new long[] {100, 500, 1000, 2500, 5000, 10_000, 25_000});
		m.put(Keys.PLAYER_KILLS, new long[] {1, 10, 25, 50, 100});
		m.put(Keys.MINED, new long[] {1000, 10_000, 50_000, 100_000, 250_000, 500_000, 1_000_000});
		m.put(Keys.PLACED, new long[] {1000, 10_000, 50_000, 100_000, 250_000, 500_000});
		m.put(Keys.DISTANCE, new long[] {10_000, 100_000, 500_000, 1_000_000, 5_000_000});
		m.put(Keys.AVIATE, new long[] {10_000, 100_000, 1_000_000});
		m.put(Keys.DIAMONDS, new long[] {10, 50, 100, 250, 500, 1000});
		m.put(Keys.DEBRIS, new long[] {4, 16, 64, 128, 256});
		m.put(Keys.FISH, new long[] {10, 50, 100, 500, 1000});
		m.put(Keys.BRED, new long[] {10, 100, 500, 1000});
		m.put(Keys.TRADES, new long[] {10, 100, 500, 1000, 5000});
		m.put(Keys.ENCHANTS, new long[] {10, 50, 100, 500});
		m.put(Keys.JUMPS, new long[] {10_000, 50_000, 100_000, 500_000});
		m.put(Keys.TOTEMS, new long[] {1, 5, 10, 25});
		m.put(Keys.PLAY_HOURS, new long[] {10, 24, 50, 100, 250, 500, 1000});
		LIFETIME = java.util.Collections.unmodifiableMap(m);
	}

	/** Очки за n-й порог (с нуля): чем дальше, тем значимее. */
	public static int lifetimeScore(int index) {
		return Math.min(40, 10 + 5 * index);
	}

	/** Порог, который перешли между old и now, или -1. Если перешли несколько — самый большой. */
	public static int crossed(long[] thresholds, long old, long now) {
		int hit = -1;
		for (int i = 0; i < thresholds.length; i++) {
			if (old < thresholds[i] && now >= thresholds[i]) {
				hit = i;
			}
		}
		return hit;
	}

	/** Предметы: впервые подобрал или скрафтил — событие. Короткий id → очки. */
	public static final Map<String, Integer> ITEMS = Map.ofEntries(
			Map.entry("diamond", 10),
			Map.entry("ancient_debris", 20),
			Map.entry("netherite_ingot", 25),
			Map.entry("netherite_sword", 10),
			Map.entry("netherite_pickaxe", 10),
			Map.entry("netherite_axe", 8),
			Map.entry("netherite_helmet", 8),
			Map.entry("netherite_chestplate", 10),
			Map.entry("netherite_leggings", 8),
			Map.entry("netherite_boots", 8),
			Map.entry("netherite_upgrade_smithing_template", 15),
			Map.entry("elytra", 40),
			Map.entry("totem_of_undying", 20),
			Map.entry("nether_star", 40),
			Map.entry("beacon", 35),
			Map.entry("dragon_egg", 60),
			Map.entry("dragon_head", 30),
			Map.entry("trident", 25),
			Map.entry("heart_of_the_sea", 20),
			Map.entry("conduit", 25),
			Map.entry("enchanted_golden_apple", 30),
			Map.entry("echo_shard", 10),
			Map.entry("recovery_compass", 15),
			Map.entry("heavy_core", 25),
			Map.entry("mace", 30),
			Map.entry("wither_skeleton_skull", 15),
			Map.entry("shulker_shell", 15),
			Map.entry("shulker_box", 20),
			Map.entry("end_crystal", 10),
			Map.entry("sniffer_egg", 20),
			Map.entry("music_disc_pigstep", 25),
			Map.entry("music_disc_otherside", 25),
			Map.entry("ominous_trial_key", 10),
			Map.entry("blaze_rod", 8),
			Map.entry("ender_eye", 10));

	/** Боссы: убийство видно по ванильной статистике killed:… */
	public static final Map<String, Integer> BOSSES = Map.of(
			"ender_dragon", 80,
			"wither", 70,
			"warden", 60,
			"elder_guardian", 30);

	/** Кого убивать — скандал: жители и защитники деревни. */
	public static final List<String> CRIMES = List.of("villager", "wandering_trader", "iron_golem", "snow_golem", "allay");

	/** Измерения: впервые для игрока; на сервере впервые — плюс SERVER_FIRST_BONUS. */
	public static int dimensionScore(String dim) {
		return switch (dim) {
			case "the_nether" -> 15;
			case "the_end" -> 25;
			default -> 10;
		};
	}

	/** Структуры: первое посещение. Незнакомая (модовая) — 3. */
	public static int structureScore(String id) {
		return switch (id) {
			case "ancient_city", "mansion" -> 30;
			case "end_city" -> 25;
			case "stronghold", "bastion_remnant", "monument" -> 20;
			case "fortress", "trial_chambers" -> 15;
			case "pillager_outpost" -> 8;
			case "desert_pyramid", "jungle_pyramid", "igloo", "swamp_hut", "trail_ruins" -> 5;
			default -> id.startsWith("village_") ? 5 : 3;
		};
	}

	public static final int SERVER_FIRST_BONUS = 20;
	/** Первый на сервере в новом биоме. */
	public static final int SERVER_FIRST_BIOME = 4;
}
