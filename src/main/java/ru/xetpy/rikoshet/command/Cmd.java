package ru.xetpy.rikoshet.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import ru.xetpy.rikoshet.RikoshetRuntime;

import java.util.function.Supplier;

/** Общее для команд. */
final class Cmd {
	private Cmd() {
	}

	static RikoshetRuntime runtime(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = rt.get();
		if (r == null) {
			c.getSource().sendFailure(Component.literal("Рикошет не запущен: смотри лог сервера."));
		}
		return r;
	}

	static ServerPlayer player(CommandContext<CommandSourceStack> c) {
		ServerPlayer p = c.getSource().getPlayer();
		if (p == null) {
			c.getSource().sendFailure(Component.literal("Эта команда — для игрока."));
		}
		return p;
	}
}
