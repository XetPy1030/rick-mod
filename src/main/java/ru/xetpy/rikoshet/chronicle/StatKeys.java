package ru.xetpy.rikoshet.chronicle;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stat;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Ванильный счётчик → наш ключ и признаки для производных счётчиков. «minecraft.mined:minecraft.stone»
 * становится «mined:stone», модовые id остаются полными. Stat — синглтоны, поэтому кеш по ссылке.
 * Только главный поток.
 */
final class StatKeys {
	static final int MINED = 1;
	static final int ORE = 1 << 1;
	static final int DIAMOND = 1 << 2;
	static final int DEBRIS = 1 << 3;
	static final int LOG = 1 << 4;
	static final int CROP = 1 << 5;
	static final int PLACED = 1 << 6;
	static final int PLANTED = 1 << 7;
	static final int CRAFTED = 1 << 8;
	static final int HOSTILE = 1 << 9;
	static final int PASSIVE = 1 << 10;
	static final int DISTANCE = 1 << 11;
	static final int AVIATE = 1 << 12;
	static final int STATION = 1 << 13;
	static final int TOTEM = 1 << 14;
	/** Растёт каждый тик сам по себе — в дневную статистику не пишем. */
	static final int SKIP = 1 << 15;

	record Info(String key, int flags) {
		boolean has(int flag) {
			return (flags & flag) != 0;
		}
	}

	/** Урожай, который добывают, а не собирают ПКМ. */
	private static final Set<String> CROP_BLOCKS = Set.of("melon", "pumpkin", "nether_wart", "cocoa", "sugar_cane", "cactus", "bamboo");
	private static final Set<String> STATIONS = Set.of(
			"interact_with_crafting_table", "interact_with_furnace", "interact_with_blast_furnace", "interact_with_smoker",
			"interact_with_anvil", "interact_with_smithing_table", "interact_with_stonecutter", "interact_with_loom",
			"interact_with_grindstone", "interact_with_cartography_table", "interact_with_campfire");
	/** Тег есть в данных игры, но константы в BlockTags для него нет. */
	private static final TagKey<Block> SAPLINGS = TagKey.create(Registries.BLOCK, Identifier.parse("minecraft:saplings"));
	private static final Set<String> TICKING = Set.of("play_time", "total_world_time", "time_since_death", "time_since_rest");

	private final Map<Stat<?>, Info> cache = new IdentityHashMap<>();

	Info info(Stat<?> stat) {
		return cache.computeIfAbsent(stat, StatKeys::compute);
	}

	private static Info compute(Stat<?> stat) {
		Identifier typeId = BuiltInRegistries.STAT_TYPE.getKey(stat.getType());
		String type = typeId == null ? "unknown" : typeId.getPath();
		Identifier id = idOf(stat);
		String path = id == null ? "?" : id.getPath();
		String key = type + ":" + (id == null ? "?" : Keys.shortId(id.toString()));
		int flags = 0;
		Object value = stat.getValue();
		switch (type) {
			case "mined" -> {
				flags |= MINED;
				if (path.endsWith("_ore") || path.equals("ancient_debris")) {
					flags |= ORE;
				}
				if (path.endsWith("diamond_ore")) {
					flags |= DIAMOND;
				}
				if (path.equals("ancient_debris")) {
					flags |= DEBRIS;
				}
				if (value instanceof Block b) {
					BlockState s = b.defaultBlockState();
					if (s.is(BlockTags.LOGS)) {
						flags |= LOG;
					}
					if (s.is(BlockTags.CROPS) || CROP_BLOCKS.contains(path)) {
						flags |= CROP;
					}
				}
			}
			case "used" -> {
				if (value instanceof BlockItem bi) {
					BlockState s = bi.getBlock().defaultBlockState();
					flags |= s.is(BlockTags.CROPS) || s.is(SAPLINGS) ? PLANTED : PLACED;
				}
				if (path.equals("totem_of_undying")) {
					flags |= TOTEM;
				}
			}
			case "crafted" -> flags |= CRAFTED;
			case "killed" -> {
				if (value instanceof EntityType<?> et) {
					MobCategory c = et.getCategory();
					if (c == MobCategory.MONSTER) {
						flags |= HOSTILE;
					} else if (c != MobCategory.MISC) {
						flags |= PASSIVE;
					}
				}
			}
			case "custom" -> {
				if (path.endsWith("_one_cm") && !path.equals("fall_one_cm")) {
					flags |= DISTANCE;
				}
				if (path.equals("aviate_one_cm")) {
					flags |= AVIATE;
				}
				if (STATIONS.contains(path)) {
					flags |= STATION;
				}
				if (TICKING.contains(path)) {
					flags |= SKIP;
				}
			}
			default -> {
			}
		}
		return new Info(key, flags);
	}

	private static <T> Identifier idOf(Stat<T> stat) {
		return stat.getType().getRegistry().getKey(stat.getValue());
	}

	/**
	 * Прибавить к производным счётчикам. Расстояния копятся в сантиметрах во временных ключах
	 * {@link #DISTANCE_CM} и {@link #AVIATE_CM}; в блоки их переводит {@link #finish}.
	 */
	static void derive(Info info, long d, Map<String, Long> out) {
		int f = info.flags();
		if (f == 0) {
			return;
		}
		if ((f & MINED) != 0) {
			out.merge(Keys.MINED, d, Long::sum);
		}
		if ((f & ORE) != 0) {
			out.merge(Keys.ORES, d, Long::sum);
		}
		if ((f & DIAMOND) != 0) {
			out.merge(Keys.DIAMONDS, d, Long::sum);
		}
		if ((f & DEBRIS) != 0) {
			out.merge(Keys.DEBRIS, d, Long::sum);
		}
		if ((f & LOG) != 0) {
			out.merge(Keys.LOGS, d, Long::sum);
		}
		if ((f & CROP) != 0) {
			out.merge(Keys.CROPS, d, Long::sum);
		}
		if ((f & PLACED) != 0) {
			out.merge(Keys.PLACED, d, Long::sum);
		}
		if ((f & PLANTED) != 0) {
			out.merge(Keys.PLANTED, d, Long::sum);
		}
		if ((f & CRAFTED) != 0) {
			out.merge(Keys.CRAFTED, d, Long::sum);
		}
		if ((f & HOSTILE) != 0) {
			out.merge(Keys.HOSTILE, d, Long::sum);
		}
		if ((f & PASSIVE) != 0) {
			out.merge(Keys.PASSIVE, d, Long::sum);
		}
		if ((f & DISTANCE) != 0) {
			out.merge(DISTANCE_CM, d, Long::sum);
		}
		if ((f & AVIATE) != 0) {
			out.merge(AVIATE_CM, d, Long::sum);
		}
		if ((f & STATION) != 0) {
			out.merge(Keys.STATIONS, d, Long::sum);
		}
		if ((f & TOTEM) != 0) {
			out.merge(Keys.TOTEMS, d, Long::sum);
		}
	}

	static final String DISTANCE_CM = "tmp:distance_cm";
	static final String AVIATE_CM = "tmp:aviate_cm";

	/** Сантиметры → блоки. */
	static void finish(Map<String, Long> out) {
		Long cm = out.remove(DISTANCE_CM);
		if (cm != null && cm >= 100) {
			out.merge(Keys.DISTANCE, cm / 100, Long::sum);
		}
		Long av = out.remove(AVIATE_CM);
		if (av != null && av >= 100) {
			out.merge(Keys.AVIATE, av / 100, Long::sum);
		}
	}
}
