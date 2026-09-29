package ru.xetpy.rikoshet.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import ru.xetpy.rikoshet.RikoshetRuntime;
import ru.xetpy.rikoshet.citadel.Citadel;
import ru.xetpy.rikoshet.quest.Level;
import ru.xetpy.rikoshet.quest.QuestDefs;
import ru.xetpy.rikoshet.quest.QuestService;

import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Цитадель: /rick citadel и /rickadmin citadel … (docs/ops/commands.md). */
final class CitadelCommands {
	private CitadelCommands() {
	}

	/** /rick citadel — Рик открывает портал рядом. */
	static LiteralArgumentBuilder<CommandSourceStack> player(Supplier<RikoshetRuntime> rt) {
		return Commands.literal("citadel").executes(c -> {
			RikoshetRuntime r = Cmd.runtime(c, rt);
			ServerPlayer p = Cmd.player(c);
			if (r == null || p == null) {
				return 0;
			}
			if (!r.config().feature("citadel")) {
				c.getSource().sendFailure(Component.literal("Цитадель пока закрыта."));
				return 0;
			}
			r.portalRequest(p);
			return 1;
		});
	}

	/** /rick science — «Полезность для науки», задания и прогресс по ним. */
	static LiteralArgumentBuilder<CommandSourceStack> science(Supplier<RikoshetRuntime> rt) {
		return Commands.literal("science").executes(c -> {
			RikoshetRuntime r = Cmd.runtime(c, rt);
			ServerPlayer p = Cmd.player(c);
			if (r == null || p == null) {
				return 0;
			}
			c.getSource().sendSuccess(() -> Component.literal(science(r, p)).withStyle(ChatFormatting.GRAY), false);
			return 1;
		});
	}

	static String science(RikoshetRuntime r, ServerPlayer p) {
		UUID u = p.getUUID();
		int value = r.reputation.get(u);
		StringBuilder sb = new StringBuilder("Полезность для науки: ").append(value).append(" — ").append(Level.of(value).title);
		if (!r.config().feature("quests")) {
			return sb.toString();
		}
		int done = r.quests.experimentsDone(u);
		if (done > 0) {
			sb.append("\nЭкспериментов сдано: ").append(done);
		}
		var active = r.quests.active(u);
		if (active.isEmpty()) {
			sb.append("\nЗаданий нет. Рик в Цитадели: /rick citadel");
		}
		DateTimeFormatter ddmm = DateTimeFormatter.ofPattern("dd.MM");
		for (QuestService.Active a : active) {
			QuestDefs.Def d = a.def();
			sb.append("\n«").append(d.title()).append("»: ").append(d.goal()).append(" — ").append(r.quests.progress(p, a)).append(" из ")
					.append(d.count());
			sb.append(r.quests.expired(a, r.today()) ? ", срок вышел" : ", до " + ddmm.format(a.deadline().minusDays(1)));
			sb.append(d.kind() == QuestDefs.Kind.BRING ? ". Сдать — подойти к Рику" : "");
		}
		return sb.toString();
	}

	/** /rick voice off|on — не слышать голос персонажей (текст остаётся). */
	static LiteralArgumentBuilder<CommandSourceStack> voice(Supplier<RikoshetRuntime> rt) {
		return Commands.literal("voice")
				.then(Commands.literal("off").executes(c -> voice(c, rt, true)))
				.then(Commands.literal("on").executes(c -> voice(c, rt, false)));
	}

	private static int voice(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt, boolean off) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		ServerPlayer p = Cmd.player(c);
		if (r == null || p == null) {
			return 0;
		}
		r.players.setVoiceOff(p.getUUID(), off);
		c.getSource().sendSuccess(() -> Component.literal(off ? "Голос персонажей тебе больше не слышен, текст остаётся. Вернуть — /rick voice on."
				: "Голос персонажей снова слышен.").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	/** /rickadmin rep <ник> <изменение> — поправить «Полезность для науки». */
	static LiteralArgumentBuilder<CommandSourceStack> rep(Supplier<RikoshetRuntime> rt) {
		return Commands.literal("rep")
				.then(Commands.argument("nick", StringArgumentType.word())
						.then(Commands.argument("delta", IntegerArgumentType.integer(-200, 200)).executes(c -> run(c, rt, r -> {
							String nick = StringArgumentType.getString(c, "nick");
							UUID u = r.players.byName(nick).map(ru.xetpy.rikoshet.storage.PlayerRecord::uuid).orElse(null);
							if (u == null) {
								return "не знаю игрока " + nick;
							}
							var ch = r.reputation.add(u, IntegerArgumentType.getInteger(c, "delta"), "admin", "команда", r.clock.millis(),
									r.today().toString());
							return nick + ": " + ch.before() + " → " + ch.after() + " (" + ch.to().title + ")";
						}))));
	}

	/** /rickadmin citadel status|place|save|mark|tp. */
	static LiteralArgumentBuilder<CommandSourceStack> admin(Supplier<RikoshetRuntime> rt) {
		return Commands.literal("citadel")
				.then(Commands.literal("status").executes(c -> status(c, rt)))
				.then(Commands.literal("place").executes(c -> run(c, rt, r -> r.citadel.replace(r.server))))
				.then(Commands.literal("save").executes(c -> run(c, rt, r -> r.citadel.save(r.server))))
				.then(Commands.literal("mark")
						.then(Commands.argument("point", StringArgumentType.word()).executes(c -> {
							ServerPlayer p = Cmd.player(c);
							return p == null ? 0 : run(c, rt, r -> r.citadel.mark(StringArgumentType.getString(c, "point"), p));
						})))
				.then(Commands.literal("tp").executes(c -> {
					ServerPlayer p = Cmd.player(c);
					return p == null ? 0 : run(c, rt, r -> {
						if (!r.citadel.ensure(r.server)) {
							return "Цитадель не готова — смотри лог";
						}
						if (Citadel.in(p)) {
							r.portals.home(r.server, p, r.clock.millis());
							return "обратно";
						}
						r.portals.toHub(r.server, p, r.clock.millis());
						return "в Цитадели";
					});
				}));
	}

	private static int status(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		StringBuilder sb = new StringBuilder("Цитадель: ").append(r.config().feature("citadel") ? "включена" : "выключена")
				.append(r.citadel.ready() ? ", готова" : ", не готова");
		ServerLevel level = r.citadel.level(r.server);
		sb.append("\nВ Цитадели игроков: ").append(level == null ? "— (измерения нет)" : level.players().size())
				.append(", порталов открыто: ").append(r.portals.openCount());
		r.citadel.markers().forEach((name, m) -> sb.append("\n  ").append(name).append(": ").append(m.pos().toShortString())
				.append(", yaw ").append((int) m.yaw()));
		sb.append("\n").append(r.talk.status());
		sb.append("\n").append(r.voice.status());
		var exp = r.quests.defs().experiment(r.today());
		if (exp != null) {
			sb.append("\nЭксперимент дня: ").append(exp.goal());
		}
		c.getSource().sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int run(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt, Function<RikoshetRuntime, String> action) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		String out = action.apply(r);
		c.getSource().sendSuccess(() -> Component.literal(out), true);
		return 1;
	}
}
