package ru.xetpy.rikoshet.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import ru.xetpy.rikoshet.RikoshetRuntime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Симуляция входа, смерти и выхода без клиента — для проверки на копии сервера
 * (tools/testserver). Регистрируется только с -Drikoshet.dev=true.
 */
public final class DevCommand {
	public static final String PROPERTY = "rikoshet.dev";

	private static final Map<String, FakePlayer> FAKES = new ConcurrentHashMap<>();

	private DevCommand() {
	}

	public static boolean enabled() {
		return Boolean.getBoolean(PROPERTY);
	}

	public static void register(CommandDispatcher<CommandSourceStack> d, Supplier<RikoshetRuntime> rt) {
		d.register(Commands.literal("rickdev")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("join")
						.then(Commands.argument("nick", StringArgumentType.word()).executes(c -> join(c, rt))))
				.then(Commands.literal("death")
						.then(Commands.argument("nick", StringArgumentType.word())
								.then(Commands.argument("type", StringArgumentType.greedyString()).executes(c -> death(c, rt)))))
				.then(Commands.literal("leave")
						.then(Commands.argument("nick", StringArgumentType.word()).executes(c -> leave(c, rt))))
				.then(Commands.literal("chat")
						.then(Commands.literal("test")
								.then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> chatTest(c, rt))))
						.then(Commands.literal("say")
								.then(Commands.argument("nick", StringArgumentType.word())
										.then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> chatSay(c, rt)))))
						.then(Commands.literal("react")
								.then(Commands.argument("nick", StringArgumentType.word())
										.then(Commands.argument("kind", StringArgumentType.word()).executes(c -> chatReact(c, rt))))))
				.then(Commands.literal("chronicle")
						.then(Commands.literal("cycle").executes(c -> cycle(c, rt)))
						.then(Commands.literal("analyze")
								.then(Commands.argument("date", StringArgumentType.word()).executes(c -> analyze(c, rt))))
						.then(Commands.literal("stat")
								.then(Commands.argument("nick", StringArgumentType.word())
										.then(Commands.argument("delta", IntegerArgumentType.integer(1))
												.then(Commands.argument("stat", StringArgumentType.greedyString())
														.executes(c -> stat(c, rt))))))
						.then(Commands.literal("move")
								.then(Commands.argument("nick", StringArgumentType.word())
										.then(Commands.argument("x", DoubleArgumentType.doubleArg())
												.then(Commands.argument("z", DoubleArgumentType.doubleArg())
														.executes(c -> move(c, rt))))))
						.then(Commands.literal("chat")
								.then(Commands.argument("nick", StringArgumentType.word())
										.then(Commands.argument("text", StringArgumentType.greedyString())
												.executes(c -> chat(c, rt)))))));
	}

	private static FakePlayer fake(RikoshetRuntime r, String nick) {
		return FAKES.computeIfAbsent(nick, n -> {
			ServerLevel level = r.server.overworld();
			FakePlayer p = FakePlayer.get(level, new GameProfile(UUIDUtil.createOfflinePlayerUUID(n), n));
			var spawn = level.getRespawnData().pos();
			p.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
			return p;
		});
	}

	private static int join(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		FakePlayer p = fake(r, StringArgumentType.getString(c, "nick"));
		r.auth.devMarkAuthenticated(p);
		r.chronicle.devTrack(p);
		r.flavor.onJoin(p);
		c.getSource().sendSuccess(() -> Component.literal("вход " + p.getScoreboardName() + " " + p.getUUID()), false);
		return 1;
	}

	/** type — id типа урона и, через пробел, id сущности-убийцы: «minecraft:mob_attack minecraft:creeper». */
	private static int death(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		FakePlayer p = fake(r, StringArgumentType.getString(c, "nick"));
		String[] parts = StringArgumentType.getString(c, "type").strip().split("\\s+");
		ServerLevel level = p.level();
		var type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
				.get(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(parts[0])));
		if (type.isEmpty()) {
			c.getSource().sendFailure(Component.literal("нет типа урона " + parts[0]));
			return 0;
		}
		Entity killer = null;
		if (parts.length > 1) {
			killer = FAKES.containsKey(parts[1]) ? FAKES.get(parts[1])
					: BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(parts[1]))
							.map(t -> (Entity) t.create(level, EntitySpawnReason.COMMAND)).orElse(null);
			if (killer != null) {
				killer.setPos(p.position());
			}
		}
		DamageSource source = new DamageSource(type.get(), killer);
		r.chronicle.beforeDeath(p);
		r.chronicle.death(p, source);
		r.flavor.onDeath(p, source);
		c.getSource().sendSuccess(() -> Component.literal("смерть " + p.getScoreboardName() + ": " + String.join(" ", parts)), false);
		return 1;
	}

	private static int cycle(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		r.chronicle.devCycle(r.server);
		c.getSource().sendSuccess(() -> Component.literal("снимок летописи: " + r.chronicle.trackedNames()), false);
		return 1;
	}

	private static int analyze(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		java.time.LocalDate day = java.time.LocalDate.parse(StringArgumentType.getString(c, "date"));
		CommandSourceStack src = c.getSource();
		r.chronicle.analyze(day).whenComplete((rep, err) -> r.server.execute(() -> {
			if (err != null) {
				r.log.warn("[летопись] анализ", err);
				src.sendFailure(Component.literal("анализ: " + err));
				return;
			}
			String text = ru.xetpy.rikoshet.chronicle.analysis.DigestWriter.write(rep, java.util.List.of(), java.util.List.of());
			r.log.info("[летопись] итоги {}:\n{}", day, text);
			src.sendSuccess(() -> Component.literal("итоги " + day + ": фактов " + rep.facts().size() + ", связей " + rep.relations().size()
					+ ", прогнозов " + rep.forecasts().size() + " — текст в логе"), false);
		}));
		return 1;
	}

	/** stat — ключ летописи: «mined:diamond_ore», «custom:jump», «dropped:diamond». */
	private static int stat(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		FakePlayer p = FAKES.get(StringArgumentType.getString(c, "nick"));
		String key = StringArgumentType.getString(c, "stat").strip();
		int delta = IntegerArgumentType.getInteger(c, "delta");
		net.minecraft.stats.Stat<?> stat = p == null ? null : statByKey(key);
		if (stat == null) {
			c.getSource().sendFailure(Component.literal(p == null ? "нет такого фейкового игрока" : "нет счётчика " + key));
			return 0;
		}
		p.getStats().increment(p, stat, delta);
		c.getSource().sendSuccess(() -> Component.literal(p.getScoreboardName() + " " + key + " +" + delta), false);
		return 1;
	}

	private static net.minecraft.stats.Stat<?> statByKey(String key) {
		int colon = key.indexOf(':');
		if (colon < 0) {
			return null;
		}
		var type = BuiltInRegistries.STAT_TYPE.getOptional(Identifier.parse("minecraft:" + key.substring(0, colon)));
		if (type.isEmpty()) {
			return null;
		}
		return statOf(type.get(), Identifier.parse(key.substring(colon + 1)));
	}

	private static <T> net.minecraft.stats.Stat<T> statOf(net.minecraft.stats.StatType<T> type, Identifier id) {
		return type.getRegistry().getOptional(id).map(type::get).orElse(null);
	}

	private static int move(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		FakePlayer p = FAKES.get(StringArgumentType.getString(c, "nick"));
		if (p == null) {
			c.getSource().sendFailure(Component.literal("нет такого фейкового игрока"));
			return 0;
		}
		double x = DoubleArgumentType.getDouble(c, "x");
		double z = DoubleArgumentType.getDouble(c, "z");
		ServerLevel level = p.level();
		int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
		if (y <= level.getMinY()) {
			y = 100; // чанк не загружен — высоты не знаем
		}
		p.setPos(x, y, z);
		int fy = y;
		c.getSource().sendSuccess(() -> Component.literal(p.getScoreboardName() + " → " + x + " " + fy + " " + z), false);
		return 1;
	}

	private static int chat(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		FakePlayer p = FAKES.get(StringArgumentType.getString(c, "nick"));
		if (r == null || p == null) {
			return 0;
		}
		r.chronicle.chat(p, StringArgumentType.getString(c, "text"));
		return 1;
	}

	private static int chatTest(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		String text = StringArgumentType.getString(c, "text");
		c.getSource().sendSuccess(() -> Component.literal("«" + text + "» → " + r.chat.classify(text)), false);
		return 1;
	}

	/** Реакция чата на фейковом игроке: burp, glow или gift — без шанса и лимитов. */
	private static int chatReact(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		FakePlayer p = FAKES.get(StringArgumentType.getString(c, "nick"));
		if (r == null || p == null) {
			return 0;
		}
		String aside = r.chat.devReact(p, StringArgumentType.getString(c, "kind"));
		var effects = p.getActiveEffects().stream().map(e -> e.getEffect().getRegisteredName() + " " + e.getDuration()).toList();
		c.getSource().sendSuccess(() -> Component.literal("реакция: " + aside + "; эффекты: " + effects
				+ "; в инвентаре: " + p.getInventory().getItem(0).getHoverName().getString()), false);
		return 1;
	}

	/** Сообщение в чат от фейкового игрока (сначала /rickdev join): летопись и Рик видят его как настоящее. */
	private static int chatSay(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		String nick = StringArgumentType.getString(c, "nick");
		FakePlayer p = FAKES.get(nick);
		if (r == null) {
			return 0;
		}
		if (p == null) {
			c.getSource().sendFailure(Component.literal("нет фейкового игрока " + nick + ": сначала /rickdev join " + nick));
			return 0;
		}
		String text = StringArgumentType.getString(c, "text");
		r.chronicle.chat(p, text);
		r.chat.onMessage(p, text);
		r.log.info("<{}> {}", nick, text);
		return 1;
	}

	private static int leave(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		FakePlayer p = FAKES.remove(StringArgumentType.getString(c, "nick"));
		if (p == null) {
			c.getSource().sendFailure(Component.literal("нет такого фейкового игрока"));
			return 0;
		}
		String session = r.chronicle.leave(p);
		r.flavor.onLeave(p, r.auth.onLeave(p), session);
		c.getSource().sendSuccess(() -> Component.literal("выход " + p.getScoreboardName()), false);
		return 1;
	}
}
