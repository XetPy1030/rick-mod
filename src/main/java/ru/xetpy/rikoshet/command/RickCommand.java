package ru.xetpy.rikoshet.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import ru.xetpy.rikoshet.RikoshetRuntime;
import ru.xetpy.rikoshet.chronicle.PlayerCard;
import ru.xetpy.rikoshet.persona.Role;
import ru.xetpy.rikoshet.persona.Speaker;

import java.util.function.Supplier;

/** Команды игроков: docs/ops/commands.md. */
public final class RickCommand {
	private RickCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> d, Supplier<RikoshetRuntime> rt) {
		d.register(Commands.literal("rick")
				.executes(c -> help(c))
				.then(Commands.literal("off").executes(c -> optOut(c, rt, true)))
				.then(Commands.literal("on").executes(c -> optOut(c, rt, false)))
				.then(Commands.literal("who").executes(c -> who(c, rt)))
				.then(Commands.literal("me").executes(c -> me(c, rt)))
				.then(Commands.literal("news").executes(c -> news(c, rt)))
				.then(Commands.literal("report")
						.executes(c -> report(c, rt, null))
						.then(Commands.argument("comment", StringArgumentType.greedyString())
								.executes(c -> report(c, rt, StringArgumentType.getString(c, "comment"))))));
	}

	private static int help(CommandContext<CommandSourceStack> c) {
		c.getSource().sendSuccess(() -> Component.literal("""
				Рикошет: на сервере живёт Рик. Он комментирует смерти, входы и выходы.
				/rick off — Рик тебя не видит и ты его не видишь; /rick on — вернуть.
				/rick who — кто есть кто среди тех, кто онлайн.
				/rick me — что ты сегодня делал, по записям летописи.
				/rick news — свежий «Межпространственный вестник».
				/rick report [комментарий] — пожаловаться админу на последнюю реплику.""").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int optOut(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt, boolean off) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		ServerPlayer p = Cmd.player(c);
		if (r == null || p == null) {
			return 0;
		}
		r.players.setOptOut(p.getUUID(), off);
		if (off) {
			r.chronicle.forget(p.getUUID());
		} else if (r.auth.isAuthenticated(p)) {
			r.chronicle.track(p);
		}
		c.getSource().sendSuccess(() -> Component.literal(off
				? "Рик тебя больше не видит, и ты его тоже. Вернуть — /rick on."
				: "Рик снова с тобой. Сам напросился.").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int who(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		MutableComponent out = Component.literal("Кто есть кто онлайн:").withStyle(ChatFormatting.GOLD);
		int n = 0;
		for (ServerPlayer p : r.server.getPlayerList().getPlayers()) {
			if (!r.flavor.canSee(p)) {
				continue;
			}
			Role role = r.roles.get(p.getUUID());
			out.append(Component.literal("\n" + p.getScoreboardName()).withStyle(ChatFormatting.WHITE));
			out.append(Component.literal(role == null ? " — вариант неизвестно откуда" : " — " + role.title())
					.withStyle(role == null ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN));
			n++;
		}
		if (n == 0) {
			out.append(Component.literal("\nникого").withStyle(ChatFormatting.DARK_GRAY));
		}
		c.getSource().sendSuccess(() -> out, false);
		return n;
	}

	private static int me(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		ServerPlayer p = Cmd.player(c);
		if (r == null || p == null) {
			return 0;
		}
		if (!r.chronicle.enabled() || r.players.optedOut(p.getUUID())) {
			c.getSource().sendFailure(Component.literal(r.chronicle.enabled()
					? "Летопись тебя не ведёт: у тебя /rick off." : "Летопись на сервере выключена."));
			return 0;
		}
		MutableComponent out = Component.literal("Летопись о тебе").withStyle(ChatFormatting.GOLD);
		for (String line : PlayerCard.lines(r.stats.today(p.getUUID()), r.chronicle.profile(p.getUUID()), r.chronicle::whoPublic)) {
			out.append(Component.literal("\n" + line).withStyle(ChatFormatting.GRAY));
		}
		var build = r.config().feature("builds") ? r.builds.mainOf(p.getUUID()) : null;
		if (build != null) {
			out.append(Component.literal("\nГлавная постройка: " + (build.name() != null ? "«" + build.name() + "», " : "")
					+ build.artificial() + " рукотворных блоков").withStyle(ChatFormatting.GRAY));
		}
		c.getSource().sendSuccess(() -> out, false);
		return 1;
	}

	private static int news(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		var issue = r.newspaper.current();
		if (!r.newspaper.enabled() || issue == null) {
			c.getSource().sendFailure(Component.literal(r.newspaper.enabled()
					? "Газета ещё не выходила. Первый номер — утром после первого игрового дня."
					: "Газета на сервере выключена."));
			return 0;
		}
		c.getSource().sendSuccess(() -> ru.xetpy.rikoshet.newspaper.IssueView.full(issue), false);
		return 1;
	}

	private static int report(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt, String comment) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		ServerPlayer p = Cmd.player(c);
		if (r == null || p == null) {
			return 0;
		}
		Speaker.Line line = r.speaker.lastSeenBy(p.getUUID());
		if (line == null && comment == null) {
			c.getSource().sendFailure(Component.literal("Ты ещё не видел ни одной реплики. Опиши проблему: /rick report <текст>"));
			return 0;
		}
		String name = p.getScoreboardName();
		r.reports.add(r.clock.millis(), p.getUUID(), name, line == null ? null : line.persona().id(),
				line == null ? null : line.text(), line == null ? null : line.ts(), comment);
		String summary = name + " жалуется" + (line == null ? "" : " на «" + line.text() + "»")
				+ (comment == null ? "" : ": " + comment);
		r.log.warn("[жалоба] {}", summary);
		r.alertAdmins(summary);
		c.getSource().sendSuccess(() -> Component.literal("Жалоба ушла админу. Спасибо.").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}
}
