package ru.xetpy.rikoshet.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.Map;

/**
 * Раса игрока из Origins: Legacy — для промпта (docs/architecture/server-integration.md). Без зависимости
 * при компиляции: раса лежит в данных игрока, в cardinal_components."origins:origin".OriginLayers.
 * Данные собираются сериализацией игрока — дорого для тика, поэтому только при начале разговора.
 */
public final class OriginsBridge {
	/** Названия — из ru_ru.json самого мода; черты — коротко, чтобы Рику было за что зацепиться. */
	private static final Map<String, String> RACES = Map.of(
			"origins:human", "человек, без способностей",
			"origins:merling", "мерлин: дышит под водой, на суше задыхается",
			"origins:arachnid", "арахнид: лазает по стенам, плетёт паутину, ест только мясо",
			"origins:blazeborn", "огнерождённый: не горит, вода его жжёт",
			"origins:avian", "авиан: медленно падает, не ест мясо, спит только высоко",
			"origins:phantom", "фантом: проходит сквозь блоки, горит на солнце",
			"origins:feline", "коточеловек: прыгучий, криперы его боятся, здоровья мало",
			"origins:elytrian", "элитрианец: с крыльями, под низким потолком ему плохо",
			"origins:enderian", "эндерианец: телепортируется жемчугом, вода его жжёт",
			"origins:shulk", "шалк: панцирь вместо брони, лишний инвентарь, медленно ест");

	private OriginsBridge() {
	}

	/** «авиан: медленно падает…» или null: Origins нет, раса не выбрана, данные не прочитались. */
	public static String race(ServerPlayer p) {
		if (!FabricLoader.getInstance().isModLoaded("origins")) {
			return null;
		}
		try {
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, p.level().registryAccess());
			p.saveWithoutId(out);
			CompoundTag cc = out.buildResult().getCompoundOrEmpty("cardinal_components");
			ListTag layers = cc.getCompoundOrEmpty("origins:origin").getListOrEmpty("OriginLayers");
			for (Tag t : layers) {
				if (t instanceof CompoundTag layer && "origins:origin".equals(layer.getStringOr("Layer", ""))) {
					String id = layer.getStringOr("Origin", "");
					if (id.isEmpty() || id.equals("origins:empty")) {
						return null;
					}
					return RACES.getOrDefault(id, id.substring(id.indexOf(':') + 1));
				}
			}
		} catch (RuntimeException e) {
			return null;
		}
		return null;
	}
}
