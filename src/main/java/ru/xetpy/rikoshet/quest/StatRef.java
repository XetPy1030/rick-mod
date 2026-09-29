package ru.xetpy.rikoshet.quest;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;

/**
 * Ванильная статистика по ключу «тип:id»: killed:minecraft:zombie, custom:minecraft:walk_one_cm,
 * used:minecraft:ender_pearl. Квест на статистику — прирост с момента выдачи.
 */
public record StatRef(String key, Stat<?> stat) {
	/** null — нет такого типа или id в реестре. */
	public static StatRef parse(String key) {
		int colon = key == null ? -1 : key.indexOf(':');
		if (colon <= 0) {
			return null;
		}
		Identifier typeId = Identifier.tryParse("minecraft:" + key.substring(0, colon));
		Identifier valueId = Identifier.tryParse(key.substring(colon + 1));
		if (typeId == null || valueId == null || !BuiltInRegistries.STAT_TYPE.containsKey(typeId)) {
			return null;
		}
		Stat<?> stat = stat(BuiltInRegistries.STAT_TYPE.getValue(typeId), valueId);
		return stat == null ? null : new StatRef(key, stat);
	}

	private static <T> Stat<T> stat(StatType<T> type, Identifier id) {
		Registry<T> registry = type.getRegistry();
		if (!registry.containsKey(id)) {
			return null;
		}
		return type.get(registry.getValue(id));
	}

	public int read(ServerPlayer p) {
		return p.getStats().getValue(stat);
	}
}
