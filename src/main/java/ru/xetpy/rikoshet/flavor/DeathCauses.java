package ru.xetpy.rikoshet.flavor;

import java.util.Map;

/**
 * Причины смерти по id типа урона (data/minecraft/damage_type): русское описание для
 * контекста ИИ и группа для заготовок. Незнакомый тип (модовый) — группа other, в контексте
 * остаётся id и ванильное сообщение о смерти.
 */
public final class DeathCauses {
	private DeathCauses() {
	}

	public record Cause(String text, String group) {
	}

	private static final Map<String, Cause> BY_TYPE = Map.ofEntries(
			e("in_fire", "сгорел в огне", "fire"),
			e("campfire", "сгорел на костре", "fire"),
			e("on_fire", "горел заживо", "fire"),
			e("lightning_bolt", "молния", "fire"),
			e("fireball", "огненный шар", "fire"),
			e("unattributed_fireball", "огненный шар", "fire"),
			e("lava", "лава", "lava"),
			e("hot_floor", "раскалённый магмовый блок под ногами", "lava"),
			e("sulfur_cube_hot", "раскалённый серный куб", "lava"),
			e("in_wall", "задохнулся в стене", "crush"),
			e("cramming", "раздавлен толпой", "crush"),
			e("falling_block", "придавило упавшим блоком", "crush"),
			e("falling_anvil", "наковальня на голову", "crush"),
			e("falling_stalactite", "упавший сталактит", "crush"),
			e("drown", "утонул", "drown"),
			e("dry_out", "высох без воды", "drown"),
			e("starve", "умер от голода", "starve"),
			e("fall", "падение с высоты", "fall"),
			e("ender_pearl", "падение после жемчуга Края", "fall"),
			e("fly_into_wall", "влетел в стену на элитрах", "fall"),
			e("stalagmite", "упал на сталагмит", "fall"),
			e("fell_out_of_world", "выпал из мира в пустоту", "void"),
			e("outside_border", "ушёл за границу мира", "void"),
			e("magic", "магия", "magic"),
			e("indirect_magic", "магия", "magic"),
			e("wither", "иссушение", "magic"),
			e("dragon_breath", "дыхание дракона", "magic"),
			e("explosion", "взрыв", "explosion"),
			e("player_explosion", "взрыв, устроенный игроком", "explosion"),
			e("bad_respawn_point", "взорвалась кровать или якорь возрождения", "explosion"),
			e("fireworks", "фейерверк", "explosion"),
			e("mob_attack", "убит мобом", "mob"),
			e("mob_attack_no_aggro", "убит мобом", "mob"),
			e("sting", "ужалила пчела", "mob"),
			e("sonic_boom", "звуковой удар хранителя", "mob"),
			e("spit", "плевок ламы", "mob"),
			e("player_attack", "убит игроком", "player"),
			e("mace_smash", "удар булавой", "player"),
			e("spear", "копьё", "projectile"),
			e("arrow", "стрела", "projectile"),
			e("trident", "трезубец", "projectile"),
			e("mob_projectile", "снаряд моба", "projectile"),
			e("thrown", "брошенный предмет", "projectile"),
			e("wind_charge", "заряд ветра", "projectile"),
			e("wither_skull", "череп иссушителя", "projectile"),
			e("cactus", "кактус", "other"),
			e("sweet_berry_bush", "куст сладких ягод", "other"),
			e("thorns", "шипы чужой брони", "other"),
			e("freeze", "замёрз в рыхлом снегу", "other"),
			e("generic", "непонятно от чего", "other"),
			e("generic_kill", "команда /kill", "other"));

	/** Имена частых мобов по-русски; остальные идут в контекст по id — модели его понимают. */
	private static final Map<String, String> MOBS = Map.ofEntries(
			Map.entry("minecraft:zombie", "зомби"),
			Map.entry("minecraft:zombie_villager", "зомби-житель"),
			Map.entry("minecraft:husk", "кадавр"),
			Map.entry("minecraft:drowned", "утопленник"),
			Map.entry("minecraft:skeleton", "скелет"),
			Map.entry("minecraft:stray", "зимогор"),
			Map.entry("minecraft:bogged", "болотник"),
			Map.entry("minecraft:wither_skeleton", "скелет-иссушитель"),
			Map.entry("minecraft:creeper", "крипер"),
			Map.entry("minecraft:spider", "паук"),
			Map.entry("minecraft:cave_spider", "пещерный паук"),
			Map.entry("minecraft:enderman", "эндермен"),
			Map.entry("minecraft:witch", "ведьма"),
			Map.entry("minecraft:slime", "слизень"),
			Map.entry("minecraft:magma_cube", "магмовый куб"),
			Map.entry("minecraft:phantom", "фантом"),
			Map.entry("minecraft:pillager", "разбойник"),
			Map.entry("minecraft:vindicator", "поборник"),
			Map.entry("minecraft:evoker", "заклинатель"),
			Map.entry("minecraft:vex", "вредина"),
			Map.entry("minecraft:ravager", "разоритель"),
			Map.entry("minecraft:blaze", "ифрит"),
			Map.entry("minecraft:ghast", "гаст"),
			Map.entry("minecraft:piglin", "пиглин"),
			Map.entry("minecraft:piglin_brute", "пиглин-громила"),
			Map.entry("minecraft:zombified_piglin", "зомбифицированный пиглин"),
			Map.entry("minecraft:hoglin", "хоглин"),
			Map.entry("minecraft:zoglin", "зоглин"),
			Map.entry("minecraft:guardian", "страж"),
			Map.entry("minecraft:elder_guardian", "древний страж"),
			Map.entry("minecraft:shulker", "шалкер"),
			Map.entry("minecraft:silverfish", "чешуйница"),
			Map.entry("minecraft:warden", "хранитель"),
			Map.entry("minecraft:breeze", "вихрь"),
			Map.entry("minecraft:creaking", "скрипун"),
			Map.entry("minecraft:ender_dragon", "Эндер-дракон"),
			Map.entry("minecraft:wither", "иссушитель"),
			Map.entry("minecraft:wolf", "волк"),
			Map.entry("minecraft:iron_golem", "железный голем"),
			Map.entry("minecraft:polar_bear", "белый медведь"),
			Map.entry("minecraft:bee", "пчела"),
			Map.entry("minecraft:llama", "лама"),
			Map.entry("minecraft:goat", "коза"),
			Map.entry("minecraft:pufferfish", "иглобрюх"));

	private static Map.Entry<String, Cause> e(String type, String text, String group) {
		return Map.entry("minecraft:" + type, new Cause(text, group));
	}

	/** Причина по id типа урона, например {@code minecraft:fall}. */
	public static Cause of(String damageType) {
		Cause c = BY_TYPE.get(damageType);
		return c != null ? c : new Cause(damageType, "other");
	}

	/** Имя моба по id сущности: по-русски, если знаем, иначе id. */
	public static String mob(String entityType) {
		return MOBS.getOrDefault(entityType, entityType);
	}
}
