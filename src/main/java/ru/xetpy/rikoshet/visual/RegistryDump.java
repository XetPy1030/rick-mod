package ru.xetpy.rikoshet.visual;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.MapColor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Дамп реестров для визуального конвейера (docs/architecture/visual-pipeline.md#общая-библиотека-toolsmc):
 * блоки со свойствами, тегами, светом и цветом карты, id предметов, частиц, звуков, сущностей, биомов
 * и структур — всё, что реально есть на этом сервере с его модами. Генераторы и линтер в tools/ проверяют
 * по нему id, а не по памяти. Одна строка — один блок, чтобы diff файла в git читался.
 */
public final class RegistryDump {
	private static final Gson GSON = new Gson();

	private RegistryDump() {
	}

	public record Result(Path file, int blocks, int states, int items, int structures) {
	}

	public static Result write(MinecraftServer server, Path file) throws IOException {
		List<String> out = new ArrayList<>();
		out.add("{");
		out.add("\"meta\": " + GSON.toJson(meta()) + ",");
		out.add("\"map_colors\": " + GSON.toJson(mapColors()) + ",");

		out.add("\"blocks\": {");
		List<Identifier> blockIds = sorted(BuiltInRegistries.BLOCK);
		int states = 0;
		for (int i = 0; i < blockIds.size(); i++) {
			Block block = BuiltInRegistries.BLOCK.getValue(blockIds.get(i));
			states += block.getStateDefinition().getPossibleStates().size();
			String comma = i + 1 < blockIds.size() ? "," : "";
			out.add(GSON.toJson(blockIds.get(i).toString()) + ": " + GSON.toJson(block(blockIds.get(i), block)) + comma);
		}
		out.add("},");

		JsonObject items = new JsonObject();
		for (Identifier id : sorted(BuiltInRegistries.ITEM)) {
			Item item = BuiltInRegistries.ITEM.getValue(id);
			// Для блочного предмета — его блок, иначе пустая строка
			items.addProperty(id.toString(), item instanceof BlockItem bi ? BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString() : "");
		}
		out.add("\"items\": " + GSON.toJson(items) + ",");
		out.add("\"particles\": " + GSON.toJson(ids(sorted(BuiltInRegistries.PARTICLE_TYPE))) + ",");
		out.add("\"sounds\": " + GSON.toJson(ids(sorted(BuiltInRegistries.SOUND_EVENT))) + ",");
		out.add("\"entities\": " + GSON.toJson(ids(sorted(BuiltInRegistries.ENTITY_TYPE))) + ",");
		Registry<?> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
		out.add("\"biomes\": " + GSON.toJson(ids(sorted(biomes))) + ",");
		List<String> structures;
		try (Stream<Identifier> s = server.getStructureManager().listTemplates()) {
			structures = s.map(Identifier::toString).sorted().toList();
		}
		out.add("\"structures\": " + GSON.toJson(structures));
		out.add("}");

		Files.createDirectories(file.getParent());
		Files.write(file, out, StandardCharsets.UTF_8);
		return new Result(file, blockIds.size(), states, items.size(), structures.size());
	}

	private static JsonObject meta() {
		JsonObject m = new JsonObject();
		m.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
		m.addProperty("data_version", SharedConstants.getCurrentVersion().dataVersion().version());
		JsonObject mods = new JsonObject();
		FabricLoader.getInstance().getAllMods().stream()
				.map(c -> c.getMetadata())
				.sorted((a, b) -> a.getId().compareTo(b.getId()))
				.forEach(md -> mods.addProperty(md.getId(), md.getVersion().getFriendlyString()));
		m.add("mods", mods);
		return m;
	}

	/** Базовые цвета карты по id: 0 — прозрачный. Оттенки получаются множителями 180, 220, 255, 135 из 255. */
	private static JsonArray mapColors() {
		JsonArray a = new JsonArray();
		for (int i = 0; i < 64; i++) {
			a.add(String.format("#%06x", MapColor.byId(i).col & 0xFFFFFF));
		}
		return a;
	}

	private static JsonObject block(Identifier id, Block block) {
		BlockState def = block.defaultBlockState();
		JsonObject b = new JsonObject();
		JsonObject props = new JsonObject();
		JsonObject defaults = new JsonObject();
		for (Property<?> p : block.getStateDefinition().getProperties()) {
			props.add(p.getName(), values(p));
			defaults.addProperty(p.getName(), valueName(def, p));
		}
		if (!props.isEmpty()) {
			b.add("props", props);
			b.add("default", defaults);
		}
		JsonArray tags = new JsonArray();
		BuiltInRegistries.BLOCK.get(id).orElseThrow().tags().map(t -> t.location().toString()).sorted().forEach(tags::add);
		if (!tags.isEmpty()) {
			b.add("tags", tags);
		}
		int lightMax = block.getStateDefinition().getPossibleStates().stream().mapToInt(BlockState::getLightEmission).max().orElse(0);
		if (lightMax > 0) {
			b.addProperty("light", def.getLightEmission());
			b.addProperty("light_max", lightMax);
		}
		b.addProperty("map", def.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id);
		b.addProperty("render", def.getRenderShape().name().toLowerCase());
		flag(b, "full", def.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
		flag(b, "solid", def.isSolidRender());
		flag(b, "occlude", def.canOcclude());
		flag(b, "falling", block instanceof Fallable);
		flag(b, "block_entity", block instanceof EntityBlock);
		flag(b, "liquid", !def.getFluidState().isEmpty());
		flag(b, "item", block.asItem() != Items.AIR);
		return b;
	}

	private static void flag(JsonObject b, String name, boolean value) {
		if (value) {
			b.addProperty(name, true);
		}
	}

	private static <T extends Comparable<T>> JsonArray values(Property<T> p) {
		JsonArray a = new JsonArray();
		for (T v : p.getPossibleValues()) {
			a.add(p.getName(v));
		}
		return a;
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> p) {
		return p.getName(state.getValue(p));
	}

	private static List<Identifier> sorted(Registry<?> registry) {
		return registry.keySet().stream().sorted().toList();
	}

	private static List<String> ids(List<Identifier> ids) {
		return ids.stream().map(Identifier::toString).toList();
	}
}
