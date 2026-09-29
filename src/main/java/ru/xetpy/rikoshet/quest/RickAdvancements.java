package ru.xetpy.rikoshet.quest;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Скрытые достижения rikoshet:… — мост в главу FTB Quests «Задания Рика» (docs/design/quests.md#глава-ftb).
 * Их выдаёт только мод: игрок себе не выдаст, а FTB Quests закрывает квест задачей advancement.
 */
public final class RickAdvancements {
	public static final String VISIT = "citadel/visit";
	public static final String TALK = "rick/talk";

	private RickAdvancements() {
	}

	public static void award(ServerPlayer p, String path) {
		var server = p.level().getServer();
		AdvancementHolder h = server == null ? null : server.getAdvancements().get(Identifier.fromNamespaceAndPath("rikoshet", path));
		if (h != null) {
			p.getAdvancements().award(h, "done");
		}
	}

	/** После сдачи эксперимента: первый и десятый. */
	public static void experiments(ServerPlayer p, int done) {
		if (done >= 1) {
			award(p, "rick/experiment_1");
		}
		if (done >= 10) {
			award(p, "rick/experiment_10");
		}
	}

	/** После смены репутации: уровни, до которых игрок дошёл. */
	public static void level(ServerPlayer p, Level level) {
		switch (level) {
			case BIOMASS -> award(p, "rick/biomass");
			case ASSISTANT -> award(p, "rick/assistant");
			case ALMOST_MORTY -> {
				award(p, "rick/assistant");
				award(p, "rick/almost_morty");
			}
			case GENIUS -> {
				award(p, "rick/assistant");
				award(p, "rick/almost_morty");
				award(p, "rick/genius");
			}
			default -> {
			}
		}
	}
}
