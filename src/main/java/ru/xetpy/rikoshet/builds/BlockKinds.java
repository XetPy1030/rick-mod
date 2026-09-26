package ru.xetpy.rikoshet.builds;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Что это за блок в постройке: природа или рукотворное, и какое (docs/design/builds.md#что-рукотворное).
 * Правило по умолчанию — природа: камень, земля, листва, брёвна, руда, модовый рельеф.
 */
public final class BlockKinds {
	public enum Kind {
		NATURAL("природа"),
		STRUCTURE("стены и отделка"),
		FURNITURE("мебель"),
		STORAGE("склад"),
		FARM("ферма"),
		REDSTONE("редстоун"),
		WORKSHOP("мастерская"),
		MAGIC("чародейство"),
		LIGHT("свет"),
		BED("спальня"),
		RAIL("рельсы"),
		PORTAL("портал");

		public final String ru;

		Kind(String ru) {
			this.ru = ru;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public boolean artificial() {
			return this != NATURAL;
		}
	}

	/** Моды мебели и дверей из сборки (сверено с jar: voxelized_furniture, mcwdoors). */
	static final Set<String> FURNITURE_MODS = Set.of("voxelized_furniture", "mcwdoors");
	/**
	 * Похожее на рукотворное, но растёт само: оболочка жеод; бамбуковые кусты мебельного мода
	 * (его worldgen — только bambooclusterfeature, сверено с jar).
	 */
	static final Set<String> NATURAL_EXCEPTIONS = Set.of("minecraft:smooth_basalt", "voxelized_furniture:bamboo_cluster");

	private static final Set<String> REDSTONE = Set.of("redstone_wire", "repeater", "comparator", "piston", "sticky_piston",
			"observer", "dispenser", "dropper", "lever", "redstone_torch", "redstone_wall_torch", "redstone_lamp",
			"daylight_detector", "target", "tripwire_hook", "note_block", "crafter", "redstone_block");
	private static final Set<String> MAGIC = Set.of("enchanting_table", "bookshelf", "chiseled_bookshelf", "beacon", "lectern",
			"brewing_stand", "conduit", "respawn_anchor");
	private static final Set<String> WORKSHOP = Set.of("crafting_table", "furnace", "blast_furnace", "smoker", "anvil",
			"chipped_anvil", "damaged_anvil", "smithing_table", "stonecutter", "loom", "grindstone", "cartography_table",
			"fletching_table", "cauldron", "water_cauldron", "lava_cauldron", "powder_snow_cauldron", "composter");
	/** Кухня Farmer's Delight — мастерская; остальное из мода решают общие правила. */
	private static final List<String> FD_WORKSHOP = List.of("stove", "cooking_pot", "skillet", "cutting_board", "cabinet");
	private static final List<String> STORAGE = List.of("chest", "barrel", "shulker_box", "hopper");
	private static final List<String> LIGHT = List.of("torch", "lantern", "_lamp", "sea_lantern", "end_rod", "candle");
	private static final List<String> STRUCTURE_WORDS = List.of("glass", "concrete", "bricks", "tiles", "polished_", "smooth_",
			"chiseled_", "cut_", "stripped_", "glazed_terracotta", "quartz", "scaffolding", "ladder", "iron_bars", "chain",
			"cobblestone", "cobbled_", "copper_block", "waxed_", "_copper");
	private static final Set<String> STRUCTURE_EXACT = Set.of("iron_block", "gold_block", "diamond_block", "emerald_block",
			"netherite_block", "lapis_block", "hay_block", "stone_bricks", "packed_mud", "bamboo_mosaic");
	private static final Set<String> FARM = Set.of("farmland", "hay_block", "beehive");

	/** Теги, которые влияют на решение. Имена — как в данных игры. */
	static final Map<String, TagKey<Block>> TAGS = Map.ofEntries(
			Map.entry("planks", BlockTags.PLANKS), Map.entry("stairs", BlockTags.STAIRS), Map.entry("slabs", BlockTags.SLABS),
			Map.entry("walls", BlockTags.WALLS), Map.entry("fences", BlockTags.FENCES), Map.entry("fence_gates", BlockTags.FENCE_GATES),
			Map.entry("doors", BlockTags.DOORS), Map.entry("trapdoors", BlockTags.TRAPDOORS), Map.entry("wool", BlockTags.WOOL),
			Map.entry("wool_carpets", BlockTags.WOOL_CARPETS), Map.entry("all_signs", BlockTags.ALL_SIGNS),
			Map.entry("banners", BlockTags.BANNERS), Map.entry("flower_pots", BlockTags.FLOWER_POTS), Map.entry("beds", BlockTags.BEDS),
			Map.entry("rails", BlockTags.RAILS), Map.entry("crops", BlockTags.CROPS), Map.entry("candles", BlockTags.CANDLES));

	private static final Set<String> STRUCTURE_TAGS = Set.of("planks", "stairs", "slabs", "walls", "fences", "fence_gates",
			"doors", "trapdoors", "wool", "wool_carpets", "all_signs", "banners", "flower_pots");

	private final Map<Block, Kind> cache = new IdentityHashMap<>();

	/** Только главный поток. */
	public Kind of(BlockState s) {
		return cache.computeIfAbsent(s.getBlock(), BlockKinds::compute);
	}

	private static Kind compute(Block b) {
		BlockState s = b.defaultBlockState();
		if (s.isAir()) {
			return Kind.NATURAL;
		}
		Identifier id = BuiltInRegistries.BLOCK.getKey(b);
		Set<String> tags = new HashSet<>();
		TAGS.forEach((name, tag) -> {
			if (s.is(tag)) {
				tags.add(name);
			}
		});
		return classify(id.getNamespace(), id.getPath(), tags);
	}

	/** Чистое правило: пространство имён, путь id, имена тегов блока. */
	static Kind classify(String ns, String path, Set<String> tags) {
		if (NATURAL_EXCEPTIONS.contains(ns + ":" + path)) {
			return Kind.NATURAL;
		}
		if (FURNITURE_MODS.contains(ns)) {
			return Kind.FURNITURE;
		}
		if (path.equals("nether_portal")) {
			return Kind.PORTAL;
		}
		if (tags.contains("beds")) {
			return Kind.BED;
		}
		if (tags.contains("rails")) {
			return Kind.RAIL;
		}
		if (STORAGE.stream().anyMatch(path::contains)) {
			return Kind.STORAGE;
		}
		if (REDSTONE.contains(path)) {
			return Kind.REDSTONE;
		}
		if (MAGIC.contains(path)) {
			return Kind.MAGIC;
		}
		if (WORKSHOP.contains(path) || ns.equals("farmersdelight") && FD_WORKSHOP.stream().anyMatch(path::contains)) {
			return Kind.WORKSHOP;
		}
		if (FARM.contains(path) || tags.contains("crops")) {
			return Kind.FARM;
		}
		if (tags.contains("candles") || LIGHT.stream().anyMatch(path::contains) && !path.startsWith("torchflower")) {
			return Kind.LIGHT;
		}
		if (STRUCTURE_EXACT.contains(path) || tags.stream().anyMatch(STRUCTURE_TAGS::contains)
				|| STRUCTURE_WORDS.stream().anyMatch(path::contains) && !path.endsWith("_ore")) {
			return Kind.STRUCTURE;
		}
		return Kind.NATURAL;
	}
}
