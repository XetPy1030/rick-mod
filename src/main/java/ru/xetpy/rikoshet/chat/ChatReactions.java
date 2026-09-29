package ru.xetpy.rikoshet.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import ru.xetpy.rikoshet.memory.MemoryService;
import ru.xetpy.rikoshet.storage.DailyStats;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Редкие реакции Рика в мире после ответа в чате (docs/architecture/ai-actions.md#реакции-чата-действие-выбирает-код).
 * Действие выбирает код по виду сообщения, модель о нём не знает. Возвращает ремарку для реплики
 * или null. Главный поток.
 */
final class ChatReactions {
	static final double BURP_CHANCE = 0.2;
	static final double GIFT_CHANCE = 0.1;
	static final long GIFT_EVERY_MS = TimeUnit.DAYS.toMillis(7);
	static final int NAUSEA_TICKS = 80;
	static final int GLOW_TICKS = 600;
	/** Столько тиков без урона — игрок не в бою. */
	static final int CALM_TICKS = 200;

	private record Junk(Item item, String name, String lore) {
	}

	private static final List<Junk> JUNK = List.of(
			new Junk(Items.STICK, "Палка утешения", "Подарок Рика. Ничего не делает. Как и ты."),
			new Junk(Items.POTATO, "Картофель мудрости", "Рик клянётся, что это не просто картошка. Врёт."),
			new Junk(Items.GLASS_BOTTLE, "Бутылка из-под бормотухи", "Пустая. Рик позаботился."));

	private final DailyStats stats;
	private final MemoryService memory;
	private final Random rnd;

	ChatReactions(DailyStats stats, MemoryService memory, Random rnd) {
		this.stats = stats;
		this.memory = memory;
		this.rnd = rnd;
	}

	/** kind — remember_kind из ответа модели. */
	String react(ServerPlayer p, String kind, long now) {
		UUID u = p.getUUID();
		if ("insult".equals(kind)) {
			int today = stats.increment(u, "chat.insult");
			if (today >= 2 && stats.get(u, "chat.glow") == 0 && calm(p)) {
				return glow(p);
			}
			if (stats.get(u, "chat.burp") == 0 && rnd.nextDouble() < BURP_CHANCE && calm(p)) {
				return burp(p);
			}
			return null;
		}
		if ("praise".equals(kind) && rnd.nextDouble() < GIFT_CHANCE && now - memory.slotUpdated(u, "rick_gift") >= GIFT_EVERY_MS) {
			return gift(p);
		}
		return null;
	}

	/** Для /rickdev chat react: burp, glow или gift — без шанса, лимитов и проверки «спокоен». */
	String force(ServerPlayer p, String what) {
		return switch (what) {
			case "burp" -> burp(p);
			case "glow" -> glow(p);
			case "gift" -> gift(p);
			default -> null;
		};
	}

	private String burp(ServerPlayer p) {
		stats.increment(p.getUUID(), "chat.burp");
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 1.0f, 0.7f);
		p.addEffect(new MobEffectInstance(MobEffects.NAUSEA, NAUSEA_TICKS, 0, false, false, true));
		return "*рыгает тебе прямо в наушник*";
	}

	private String glow(ServerPlayer p) {
		stats.increment(p.getUUID(), "chat.glow");
		p.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TICKS, 0, false, false, true));
		return "*вешает на тебя метку идиота*";
	}

	private String gift(ServerPlayer p) {
		Junk j = JUNK.get(rnd.nextInt(JUNK.size()));
		ItemStack s = new ItemStack(j.item());
		s.set(DataComponents.CUSTOM_NAME, Component.literal(j.name()).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GREEN)));
		s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(j.lore()).withStyle(ChatFormatting.GRAY))));
		if (!p.getInventory().add(s)) {
			return null;
		}
		memory.remember(p.getUUID(), "rick_gift", "получил от Рика подарок: «" + j.name() + "»", 5);
		return "*из портала вываливается «" + j.name() + "»*";
	}

	/** Можно трогать игрока: выживание или приключение, на земле, не в лаве и не горит, здоровье от половины, не в бою. */
	static boolean calm(ServerPlayer p) {
		return p.isAlive() && !p.isCreative() && !p.isSpectator() && p.onGround() && !p.isInLava() && !p.isOnFire()
				&& p.getHealth() >= p.getMaxHealth() / 2 && p.hurtTime == 0
				&& (p.getLastHurtByMob() == null || p.tickCount - p.getLastHurtByMobTimestamp() > CALM_TICKS);
	}
}
