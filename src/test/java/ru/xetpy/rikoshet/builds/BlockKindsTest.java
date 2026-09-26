package ru.xetpy.rikoshet.builds;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockKindsTest {
	static BlockKinds.Kind k(String id, String... tags) {
		int i = id.indexOf(':');
		return BlockKinds.classify(i < 0 ? "minecraft" : id.substring(0, i), i < 0 ? id : id.substring(i + 1), Set.of(tags));
	}

	@Test
	void naturalByDefault() {
		assertEquals(BlockKinds.Kind.NATURAL, k("stone"));
		assertEquals(BlockKinds.Kind.NATURAL, k("oak_log"));
		assertEquals(BlockKinds.Kind.NATURAL, k("oak_leaves"));
		assertEquals(BlockKinds.Kind.NATURAL, k("deepslate_copper_ore"), "руда с «copper» — не отделка");
		assertEquals(BlockKinds.Kind.NATURAL, k("torchflower"), "цветок, не свет");
		assertEquals(BlockKinds.Kind.NATURAL, k("betterend:amber_moss"));
		assertEquals(BlockKinds.Kind.NATURAL, k("smooth_basalt"), "оболочка жеоды");
		assertEquals(BlockKinds.Kind.NATURAL, k("voxelized_furniture:bamboo_cluster"), "растёт сам");
	}

	@Test
	void structureByTagsAndWords() {
		assertEquals(BlockKinds.Kind.STRUCTURE, k("dark_oak_planks", "planks"));
		assertEquals(BlockKinds.Kind.STRUCTURE, k("betternether:stalagnate_stairs", "stairs"));
		assertEquals(BlockKinds.Kind.STRUCTURE, k("white_stained_glass"));
		assertEquals(BlockKinds.Kind.STRUCTURE, k("cobblestone"));
		assertEquals(BlockKinds.Kind.STRUCTURE, k("waxed_cut_copper"));
		assertEquals(BlockKinds.Kind.STRUCTURE, k("stone_bricks"));
	}

	@Test
	void functions() {
		assertEquals(BlockKinds.Kind.FURNITURE, k("voxelized_furniture:oak_chair"));
		assertEquals(BlockKinds.Kind.FURNITURE, k("mcwdoors:oak_barn_door", "doors"), "мод дверей — мебель, а не стены");
		assertEquals(BlockKinds.Kind.STORAGE, k("chest"));
		assertEquals(BlockKinds.Kind.STORAGE, k("red_shulker_box"));
		assertEquals(BlockKinds.Kind.REDSTONE, k("repeater"));
		assertEquals(BlockKinds.Kind.WORKSHOP, k("farmersdelight:stove"));
		assertEquals(BlockKinds.Kind.MAGIC, k("enchanting_table"));
		assertEquals(BlockKinds.Kind.FARM, k("farmland"));
		assertEquals(BlockKinds.Kind.FARM, k("wheat", "crops"));
		assertEquals(BlockKinds.Kind.LIGHT, k("lantern"));
		assertEquals(BlockKinds.Kind.BED, k("red_bed", "beds"));
		assertEquals(BlockKinds.Kind.RAIL, k("powered_rail", "rails"));
		assertEquals(BlockKinds.Kind.PORTAL, k("nether_portal"));
	}

	@Test
	void tally() {
		ScanTally t = new ScanTally();
		t.add(BlockKinds.Kind.STRUCTURE, "dark_oak_planks", 400, 4);
		t.add(BlockKinds.Kind.STRUCTURE, "glass", 100, 5);
		t.add(BlockKinds.Kind.NATURAL, "stone", 4000, 3);
		t.add(BlockKinds.Kind.STORAGE, "chest", 3, 4);
		t.add(BlockKinds.Kind.STRUCTURE, "oak_planks", 12, -3); // доски шахты глубоко внизу
		var j = t.json("plains", false);
		assertEquals(515, j.get("artificial").getAsLong());
		assertEquals(64, j.get("y_min").getAsInt());
		assertEquals(95, j.get("y_max").getAsInt());
		assertEquals("dark_oak_planks", j.getAsJsonArray("materials").get(0).getAsJsonArray().get(0).getAsString());
	}
}
