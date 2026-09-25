package ru.xetpy.rikoshet.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
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
						.then(Commands.argument("nick", StringArgumentType.word()).executes(c -> leave(c, rt)))));
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
		r.flavor.onDeath(p, new DamageSource(type.get(), killer));
		c.getSource().sendSuccess(() -> Component.literal("смерть " + p.getScoreboardName() + ": " + String.join(" ", parts)), false);
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
		r.flavor.onLeave(p, r.auth.onLeave(p));
		c.getSource().sendSuccess(() -> Component.literal("выход " + p.getScoreboardName()), false);
		return 1;
	}
}
