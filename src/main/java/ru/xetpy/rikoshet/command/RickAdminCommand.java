package ru.xetpy.rikoshet.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import ru.xetpy.rikoshet.RikoshetRuntime;
import ru.xetpy.rikoshet.ai.AiRequest;
import ru.xetpy.rikoshet.ai.AiRoute;
import ru.xetpy.rikoshet.ai.AiService;
import ru.xetpy.rikoshet.ai.AiStats;
import ru.xetpy.rikoshet.ai.AiStatus;
import ru.xetpy.rikoshet.ai.ModelSpec;
import ru.xetpy.rikoshet.ai.PromptBuilder;
import ru.xetpy.rikoshet.ai.TextFilter;
import ru.xetpy.rikoshet.ai.action.ActionValidator;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.persona.Archetype;
import ru.xetpy.rikoshet.persona.Persona;
import ru.xetpy.rikoshet.persona.Role;
import ru.xetpy.rikoshet.persona.Roster;
import ru.xetpy.rikoshet.storage.ReportStore;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Команды админа (op 3): docs/ops/commands.md. Работают и из консоли. */
public final class RickAdminCommand {
	private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]{3,16}");
	private static final int MAX_TITLE = 48;
	private static final int MAX_NOTE = 200;

	private RickAdminCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> d, Supplier<RikoshetRuntime> rt) {
		SuggestionProvider<CommandSourceStack> nicks = (c, b) -> {
			RikoshetRuntime r = rt.get();
			Set<String> names = new LinkedHashSet<>();
			if (r != null) {
				r.server.getPlayerList().getPlayers().forEach(p -> names.add(p.getScoreboardName()));
				names.addAll(r.players.names());
				r.roles.all().forEach(role -> names.add(role.name()));
			}
			return SharedSuggestionProvider.suggest(names, b);
		};
		SuggestionProvider<CommandSourceStack> archetypes = (c, b) ->
				SharedSuggestionProvider.suggest(Arrays.stream(Archetype.values()).map(Archetype::id), b);

		d.register(Commands.literal("rickadmin")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("reload").executes(c -> reload(c, rt)))
				.then(Commands.literal("ai")
						.then(Commands.literal("status").executes(c -> status(c, rt)))
						.then(Commands.literal("pause").executes(c -> pause(c, rt, true)))
						.then(Commands.literal("resume").executes(c -> pause(c, rt, false)))
						.then(Commands.literal("test")
								.then(Commands.literal("flavor")
										.then(Commands.argument("context", StringArgumentType.greedyString())
												.executes(c -> test(c, rt, "flavor", StringArgumentType.getString(c, "context")))))
								.then(Commands.literal("dialogue")
										.then(Commands.argument("text", StringArgumentType.greedyString())
												.executes(c -> test(c, rt, "dialogue", StringArgumentType.getString(c, "text")))))))
				.then(Commands.literal("news")
						.then(Commands.literal("publish")
								.executes(c -> news(c, rt, null, true))
								.then(Commands.argument("date", StringArgumentType.word())
										.executes(c -> news(c, rt, StringArgumentType.getString(c, "date"), true))))
						.then(Commands.literal("preview")
								.executes(c -> news(c, rt, null, false))
								.then(Commands.argument("date", StringArgumentType.word())
										.executes(c -> news(c, rt, StringArgumentType.getString(c, "date"), false)))))
				.then(Commands.literal("builds")
						.executes(c -> buildsList(c, rt))
						.then(Commands.literal("scan")
								.then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
										.then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
												.executes(c -> buildsScan(c, rt))))))
				.then(Commands.literal("memory")
						.then(Commands.literal("show")
								.then(Commands.argument("nick", StringArgumentType.word()).suggests(nicks)
										.executes(c -> memoryShow(c, rt))))
						.then(Commands.literal("forget")
								.then(Commands.argument("nick", StringArgumentType.word()).suggests(nicks)
										.executes(c -> memoryForget(c, rt)))))
				.then(Commands.literal("chronicle")
						.then(Commands.literal("status").executes(c -> chronicleStatus(c, rt)))
						.then(Commands.literal("day")
								.executes(c -> chronicleDay(c, rt, null))
								.then(Commands.argument("date", StringArgumentType.word())
										.executes(c -> chronicleDay(c, rt, StringArgumentType.getString(c, "date")))))
						.then(Commands.literal("player")
								.then(Commands.argument("nick", StringArgumentType.word()).suggests(nicks)
										.executes(c -> chroniclePlayer(c, rt)))))
				.then(Commands.literal("report")
						.then(Commands.literal("list").executes(c -> reportList(c, rt)))
						.then(Commands.literal("resolve")
								.then(Commands.literal("all").executes(c -> reportResolve(c, rt, null)))
								.then(Commands.argument("id", LongArgumentType.longArg(1))
										.executes(c -> reportResolve(c, rt, LongArgumentType.getLong(c, "id"))))))
				.then(Commands.literal("role")
						.then(Commands.literal("list").executes(c -> roleList(c, rt)))
						.then(Commands.literal("set")
								.then(Commands.argument("nick", StringArgumentType.word()).suggests(nicks)
										.then(Commands.argument("title", StringArgumentType.greedyString())
												.executes(c -> roleSet(c, rt)))))
						.then(Commands.literal("note")
								.then(Commands.argument("nick", StringArgumentType.word()).suggests(nicks)
										.then(Commands.argument("text", StringArgumentType.greedyString())
												.executes(c -> roleNote(c, rt)))))
						.then(Commands.literal("archetype")
								.then(Commands.argument("nick", StringArgumentType.word()).suggests(nicks)
										.then(Commands.argument("archetype", StringArgumentType.word()).suggests(archetypes)
												.executes(c -> roleArchetype(c, rt)))))
						.then(Commands.literal("clear")
								.then(Commands.argument("nick", StringArgumentType.word()).suggests(nicks)
										.executes(c -> roleClear(c, rt))))));
	}

	// ---------- reload, pause, status ----------

	private static int reload(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		RikoshetRuntime.Reload res = r.reload();
		MutableComponent out = Component.literal(res.applied() ? "Конфиг перечитан." : "Конфиг НЕ применён, в силе прежний:")
				.withStyle(res.applied() ? ChatFormatting.GREEN : ChatFormatting.RED);
		res.errors().forEach(e -> out.append(Component.literal("\n  ошибка: " + e).withStyle(ChatFormatting.RED)));
		res.warnings().forEach(w -> out.append(Component.literal("\n  " + w).withStyle(ChatFormatting.YELLOW)));
		if (res.applied()) {
			c.getSource().sendSuccess(() -> out, true);
		} else {
			c.getSource().sendFailure(out);
		}
		return res.applied() ? 1 : 0;
	}

	private static int pause(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt, boolean pause) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		r.ai.setPaused(pause);
		c.getSource().sendSuccess(() -> Component.literal(pause
				? "Живые запросы ИИ на паузе: только заготовки."
				: "Живые запросы ИИ снова идут."), true);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		AiService ai = r.ai;
		RikoshetConfig cfg = r.config();
		String state;
		if (!cfg.ai().enabled()) {
			state = "выключен в конфиге (ai.enabled)";
		} else if (!ai.secrets().hasKey()) {
			state = "нет ключа — только заготовки";
		} else if (ai.paused()) {
			state = "пауза (/rickadmin ai resume)";
		} else if (ai.blockReason() != null) {
			state = "заблокирован: " + ai.blockReason();
		} else if (!ai.budget().canSpend()) {
			state = "бюджет на сегодня исчерпан";
		} else {
			state = "работает";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("ИИ: ").append(state).append('\n');
		sb.append("Ключ: ").append(ai.secrets().source()).append('\n');
		sb.append(String.format("Расход за %s: $%.4f из $%.2f%n", ai.budget().day(), ai.budget().spentToday(), ai.budget().limit()));
		sb.append("Запросов за минуту: ").append(ai.limiter().lastMinute()).append('/').append(ai.limiter().perMinute())
				.append(", в работе ").append(ai.active()).append(", в очереди ").append(ai.queued()).append('\n');
		sb.append(String.format("Нагрузка: %s, MSPT %.1f (мягкий порог %.0f, жёсткий %.0f)%n",
				r.load.level(), r.load.mspt(), cfg.performance().msptSoft(), cfg.performance().msptHard()));
		sb.append("EasyAuth: ").append(r.auth.easyAuthPresent() ? "есть, вход после пароля" : "нет, вход сразу").append('\n');
		sb.append("С запуска сервера:");
		Map<String, AiStats.Route> all = ai.stats().all();
		if (all.isEmpty()) {
			sb.append(" запросов не было");
		}
		for (var e : all.entrySet()) {
			AiStats.Route s = e.getValue();
			AiRoute route = cfg.ai().route(e.getKey());
			String models = route == null ? "?" : route.models().stream().map(ModelSpec::toString).collect(Collectors.joining(", "));
			int total = s.total();
			int ok = s.byStatus().getOrDefault(AiStatus.OK, 0);
			sb.append("\n ").append(e.getKey()).append(" [").append(models).append("]: ")
					.append(total).append(" запр., OK ").append(total == 0 ? 0 : ok * 100 / total).append("%");
			if (s.latency(0.5) >= 0) {
				sb.append(String.format(", p50 %.1f с, p90 %.1f с", s.latency(0.5) / 1000.0, s.latency(0.9) / 1000.0));
			}
			sb.append(String.format(", попыток %d, $%.4f, кеш %.0f%%", s.attempts(), s.cost(), s.cacheShare() * 100));
			String other = s.byStatus().entrySet().stream()
					.filter(x -> x.getKey() != AiStatus.OK)
					.map(x -> x.getKey().name().toLowerCase() + " " + x.getValue())
					.collect(Collectors.joining(", "));
			if (!other.isEmpty()) {
				sb.append("\n   не OK: ").append(other);
			}
			if (s.lastError() != null) {
				sb.append("\n   последняя ошибка: ").append(s.lastError());
			}
		}
		if (r.reports.open() > 0) {
			sb.append("\nЖалоб не разобрано: ").append(r.reports.open()).append(" — /rickadmin report list");
		}
		if (!r.startupProblems.isEmpty()) {
			sb.append("\nКонфиг: при старте ").append(r.startupProblems.size())
					.append(" ошибок, действуют значения по умолчанию; поправь и /rickadmin reload");
		}
		String text = sb.toString();
		c.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	// ---------- газета ----------

	/** publish — выпустить и разослать; preview — собрать и показать только себе. Дата по умолчанию — вчера. */
	private static int news(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt, String date, boolean publish) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		java.time.LocalDate day;
		try {
			day = date == null ? r.today().minusDays(1) : java.time.LocalDate.parse(date);
		} catch (java.time.format.DateTimeParseException e) {
			c.getSource().sendFailure(Component.literal("Дата — ГГГГ-ММ-ДД, например " + r.today()));
			return 0;
		}
		CommandSourceStack src = c.getSource();
		src.sendSuccess(() -> Component.literal((publish ? "Выпускаю" : "Собираю") + " газету за " + day + "… до пары минут")
				.withStyle(ChatFormatting.GRAY), false);
		var f = publish ? r.newspaper.publish(day) : r.newspaper.generate(day);
		f.whenComplete((issue, err) -> r.server.execute(() -> {
			if (err != null) {
				src.sendFailure(Component.literal("Не вышло: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage())));
				return;
			}
			if (issue == null) {
				src.sendFailure(Component.literal("За " + day + " писать не о чем: никто не играл или летопись выключена."));
				return;
			}
			src.sendSuccess(() -> ru.xetpy.rikoshet.newspaper.IssueView.full(issue), false);
			src.sendSuccess(() -> Component.literal(("ai".equals(issue.source()) ? "Написала редакция: " + issue.model() : "Собран без ИИ")
					+ String.format(", $%.4f", issue.costUsd()) + (publish ? ", разослан" : ", не опубликован")).withStyle(ChatFormatting.DARK_GRAY), false);
		}));
		return 1;
	}

	// ---------- постройки ----------

	/** Постройки по размеру: где (координаты видит только админ), хозяин, имя, виды блоков. */
	private static int buildsList(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		var all = r.builds.all();
		MutableComponent out = Component.literal("Постройки (" + all.size() + ", в очереди скана " + r.builds.queued() + ")"
				+ (r.config().feature("builds") ? "" : " — features.builds выключен")).withStyle(ChatFormatting.GOLD);
		int n = 0;
		for (var s : all) {
			if (n++ >= 15) {
				break;
			}
			String owner = s.owner() == null ? "?" : r.chronicle.whoPublic(s.owner());
			out.append(Component.literal("\n" + s.dim() + " " + (s.cx() * 64 + 32) + " " + (s.cz() * 64 + 32) + " — " + owner + ": "
					+ s.artificial() + " рукотворных" + (s.name() == null ? "" : ", «" + s.name() + "»")).withStyle(ChatFormatting.WHITE));
			if (s.scan() != null && s.scan().has("kinds")) {
				out.append(Component.literal("\n  " + s.scan().getAsJsonObject("kinds")).withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		c.getSource().sendSuccess(() -> out, false);
		return all.size();
	}

	private static int buildsScan(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		int x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "x");
		int z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "z");
		var level = c.getSource().getLevel();
		String dim = ru.xetpy.rikoshet.chronicle.Keys.shortId(level.dimension().identifier().toString());
		r.builds.scanNow(dim, Math.floorDiv(x, 64), Math.floorDiv(z, 64));
		c.getSource().sendSuccess(() -> Component.literal("Клетка " + Math.floorDiv(x, 64) + " " + Math.floorDiv(z, 64)
				+ " в очереди: скан — 16 секунд, если чанки загружены"), false);
		return 1;
	}

	// ---------- память ----------

	private static int memoryShow(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		Target t = r == null ? null : resolve(c, r);
		if (t == null) {
			return 0;
		}
		MutableComponent out = Component.literal("Рик помнит о " + r.chronicle.whoPublic(t.uuid())).withStyle(ChatFormatting.GOLD);
		var knowledge = r.memory.knowledge(t.uuid());
		if (knowledge.isEmpty()) {
			out.append(Component.literal("\nЗнаний пока нет: они появляются после ночного анализа.").withStyle(ChatFormatting.DARK_GRAY));
		}
		knowledge.forEach((slot, value) -> out.append(Component.literal("\n" + slot + ": ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(value).withStyle(ChatFormatting.GRAY)));
		String recalled = r.memory.recall(List.of(t.uuid()), java.util.Set.of("join"), 800, 0);
		if (recalled != null) {
			out.append(Component.literal("\nВспомнит при входе:\n").withStyle(ChatFormatting.GOLD))
					.append(Component.literal(recalled).withStyle(ChatFormatting.GRAY));
		}
		c.getSource().sendSuccess(() -> out, false);
		return 1;
	}

	private static int memoryForget(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		Target t = r == null ? null : resolve(c, r);
		if (t == null) {
			return 0;
		}
		CommandSourceStack src = c.getSource();
		r.memory.forget(t.uuid()).whenComplete((n, err) -> r.server.execute(() -> {
			if (err != null) {
				src.sendFailure(Component.literal("Не стёрлось: " + err.getMessage()));
			} else {
				src.sendSuccess(() -> Component.literal("Рик забыл " + t.name() + ": удалено записей " + n
						+ ". Статистика летописи осталась."), true);
			}
		}));
		return 1;
	}

	// ---------- летопись ----------

	private static int chronicleStatus(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		List<String> names = r.chronicle.trackedNames();
		String text = "Летопись: " + (r.chronicle.enabled() ? "включена" : "выключена (features.chronicle)")
				+ "\nДень: " + r.chronicle.currentDay()
				+ "\nВедёт: " + (names.isEmpty() ? "никого" : String.join(", ", names))
				+ "\nДомов найдено: " + r.chronicle.homes().size();
		c.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	/** Итоги дня: пересчитать и показать сводку, как её увидит редакция газеты. */
	private static int chronicleDay(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt, String date) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		java.time.LocalDate day;
		try {
			day = date == null ? r.today().minusDays(1) : java.time.LocalDate.parse(date);
		} catch (java.time.format.DateTimeParseException e) {
			c.getSource().sendFailure(Component.literal("Дата — ГГГГ-ММ-ДД, например " + r.today()));
			return 0;
		}
		CommandSourceStack src = c.getSource();
		src.sendSuccess(() -> Component.literal("Считаю итоги " + day + "…").withStyle(ChatFormatting.GRAY), false);
		r.chronicle.analyze(day).whenComplete((rep, err) -> r.server.execute(() -> {
			if (err != null) {
				src.sendFailure(Component.literal("Анализ не удался: " + err));
				return;
			}
			String text = ru.xetpy.rikoshet.chronicle.analysis.DigestWriter.write(rep, List.of(), List.of(), r.config().newspaper().maxFacts());
			r.log.info("[летопись] итоги {}:\n{}", day, text);
			src.sendSuccess(() -> Component.literal(text), false);
		}));
		return 1;
	}

	private static int chroniclePlayer(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		Target t = r == null ? null : resolve(c, r);
		if (t == null) {
			return 0;
		}
		MutableComponent out = Component.literal(r.chronicle.whoPublic(t.uuid()) + (r.chronicle.tracked(t.uuid()) ? " (онлайн)" : ""))
				.withStyle(ChatFormatting.GOLD);
		for (String line : ru.xetpy.rikoshet.chronicle.PlayerCard.lines(r.stats.today(t.uuid()), r.chronicle.profile(t.uuid()), r.chronicle::whoPublic)) {
			out.append(Component.literal("\n" + line).withStyle(ChatFormatting.GRAY));
		}
		String last = r.chronicle.lastSession(t.uuid());
		if (last != null) {
			out.append(Component.literal("\nПрошлая сессия: " + last).withStyle(ChatFormatting.DARK_GRAY));
		}
		var totals = r.chronicle.totals(t.uuid());
		if (!totals.isEmpty()) {
			out.append(Component.literal("\nЗа всё время: " + totals).withStyle(ChatFormatting.DARK_GRAY));
		}
		c.getSource().sendSuccess(() -> out, false);
		return 1;
	}

	// ---------- жалобы ----------

	private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

	private static int reportList(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		CommandSourceStack src = c.getSource();
		r.reports.unresolved(20).whenComplete((list, err) -> r.server.execute(() -> {
			if (err != null) {
				src.sendFailure(Component.literal("Не прочитал жалобы: " + err.getMessage()));
				return;
			}
			if (list.isEmpty()) {
				src.sendSuccess(() -> Component.literal("Жалоб нет.").withStyle(ChatFormatting.GRAY), false);
				return;
			}
			MutableComponent out = Component.literal("Жалобы (" + r.reports.open() + ", старые первыми):").withStyle(ChatFormatting.GOLD);
			for (ReportStore.Report rep : list) {
				String when = REPORT_TIME.format(Instant.ofEpochMilli(rep.ts()).atZone(r.config().timezone()));
				out.append(Component.literal("\n#" + rep.id() + " " + when + " " + rep.reporterName()).withStyle(ChatFormatting.WHITE));
				if (rep.line() != null) {
					out.append(Component.literal(" на «" + rep.line() + "»").withStyle(ChatFormatting.GRAY));
				}
				if (rep.comment() != null) {
					out.append(Component.literal(": " + rep.comment()).withStyle(ChatFormatting.YELLOW));
				}
			}
			out.append(Component.literal("\nРазобрал — /rickadmin report resolve <id>|all").withStyle(ChatFormatting.DARK_GRAY));
			src.sendSuccess(() -> out, false);
		}));
		return 1;
	}

	private static int reportResolve(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt, Long id) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		CommandSourceStack src = c.getSource();
		r.reports.resolve(id).whenComplete((n, err) -> r.server.execute(() -> {
			if (err != null) {
				src.sendFailure(Component.literal("Не записал: " + err.getMessage()));
			} else if (n == 0) {
				src.sendFailure(Component.literal(id == null ? "Неразобранных жалоб нет." : "Жалобы #" + id + " нет или она уже разобрана."));
			} else {
				src.sendSuccess(() -> Component.literal("Разобрано: " + n + ". Осталось: " + r.reports.open()), true);
			}
		}));
		return 1;
	}

	// ---------- ai test ----------

	private static int test(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt, String route, String input) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		CommandSourceStack src = c.getSource();
		ServerPlayer p = src.getPlayer();
		String name = p == null ? "console" : p.getScoreboardName();
		Role role = p == null ? null : r.roles.get(p.getUUID());
		String system;
		String user;
		String schema;
		if (route.equals("flavor")) {
			system = PromptBuilder.system(r.prompts, r.flavor.rosterBlock(), Persona.RICK.id(), "death");
			user = PromptBuilder.user(null, "Событие: смерть\nИгрок: " + Roster.describe(name, role) + "\n" + input, null, null);
			schema = "line";
		} else {
			system = PromptBuilder.system(r.prompts, r.flavor.rosterBlock(), Persona.RICK.id(), "dialogue");
			user = PromptBuilder.user(null, "Событие: разговор\nИгрок: " + Roster.describe(name, role), null, input);
			schema = "dialogue";
		}
		src.sendSuccess(() -> Component.literal("Запрос ушёл в " + route + "…").withStyle(ChatFormatting.GRAY), false);
		UUID uuid = p == null ? null : p.getUUID();
		r.ai.submit(new AiRequest(route, schema, system, user, "admin_test", uuid))
				.whenComplete((res, err) -> r.server.execute(() -> {
					if (err != null) {
						src.sendFailure(Component.literal("Ошибка: " + err));
						return;
					}
					RikoshetConfig cfg = r.config();
					StringBuilder sb = new StringBuilder();
					sb.append(res.status()).append(res.model() == null ? "" : " · " + res.model())
							.append(String.format(" · %.1f с · попыток %d · $%.5f", res.latencyMs() / 1000.0, res.attempts(), res.costUsd()));
					if (res.error() != null) {
						sb.append("\nошибка: ").append(res.error());
					}
					if (res.ok()) {
						JsonObject v = res.value();
						TextFilter.Result f = TextFilter.apply(v.get("say").getAsString(), cfg.content().maxMessageLength(), cfg.content().blocklist());
						sb.append("\nsay: ").append(f.ok() ? f.text() : "(отклонено фильтром: " + f.reason() + ") " + v.get("say").getAsString());
						if (v.has("mood")) {
							sb.append("\nmood: ").append(v.get("mood").getAsString());
						}
						if (v.has("actions") || v.has("memory_note")) {
							ActionValidator.Outcome o = ActionValidator.validate(v, Persona.RICK.allowedActions(), cfg.content().blocklist());
							sb.append("\nдействия: ").append(o.accepted().isEmpty() ? "нет" : o.accepted().toString());
							if (!o.rejected().isEmpty()) {
								sb.append("\nотклонено: ").append(String.join("; ", o.rejected()));
							}
							sb.append("\n(тест: действия не применяются)");
						}
					}
					String text = sb.toString();
					src.sendSuccess(() -> Component.literal(text), false);
				}));
		return 1;
	}

	// ---------- роли ----------

	private record Target(UUID uuid, String name) {
	}

	/** Онлайн-игрок, потом профиль в БД, потом роль, потом оффлайн-UUID (сервер без авторизации Mojang). */
	private static Target resolve(CommandContext<CommandSourceStack> c, RikoshetRuntime r) {
		String name = StringArgumentType.getString(c, "nick");
		ServerPlayer online = r.server.getPlayerList().getPlayerByName(name);
		if (online != null) {
			return new Target(online.getUUID(), online.getScoreboardName());
		}
		var known = r.players.byName(name);
		if (known.isPresent()) {
			return new Target(known.get().uuid(), known.get().name());
		}
		for (Role role : r.roles.all()) {
			if (role.name().equalsIgnoreCase(name)) {
				return new Target(role.uuid(), role.name());
			}
		}
		if (!r.server.usesAuthentication() && NICK.matcher(name).matches()) {
			return new Target(UUIDUtil.createOfflinePlayerUUID(name), name);
		}
		c.getSource().sendFailure(Component.literal("Игрок " + name + " не найден: он ещё ни разу не заходил."));
		return null;
	}

	private static String clean(String s, int max) {
		TextFilter.Result f = TextFilter.apply(s, max, List.of());
		return f.ok() ? f.text() : null;
	}

	private static int roleSet(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		Target t = r == null ? null : resolve(c, r);
		if (t == null) {
			return 0;
		}
		String title = clean(StringArgumentType.getString(c, "title"), MAX_TITLE);
		if (title == null) {
			c.getSource().sendFailure(Component.literal("Название роли пустое или со ссылкой."));
			return 0;
		}
		Role role = r.roles.set(t.uuid(), t.name(), title, r.clock.millis());
		c.getSource().sendSuccess(() -> Component.literal(t.name() + " — " + role.title() + " (" + role.archetype().id()
				+ (role.archetypeManual() ? ", вручную" : "") + ")"), true);
		return 1;
	}

	private static int roleNote(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		Target t = r == null ? null : resolve(c, r);
		if (t == null) {
			return 0;
		}
		String raw = StringArgumentType.getString(c, "text");
		String note = raw.strip().equals("-") ? null : clean(raw, MAX_NOTE);
		if (note == null && !raw.strip().equals("-")) {
			c.getSource().sendFailure(Component.literal("Заметка пустая или со ссылкой. Стереть — «-»."));
			return 0;
		}
		Role role = r.roles.note(t.uuid(), note, r.clock.millis());
		if (role == null) {
			c.getSource().sendFailure(Component.literal("У " + t.name() + " нет роли: сначала /rickadmin role set."));
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal(t.name() + ": " + (note == null ? "заметка стёрта" : "заметка — " + note)), true);
		return 1;
	}

	private static int roleArchetype(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		Target t = r == null ? null : resolve(c, r);
		if (t == null) {
			return 0;
		}
		String id = StringArgumentType.getString(c, "archetype");
		Archetype a = Archetype.byId(id).orElse(null);
		if (a == null) {
			c.getSource().sendFailure(Component.literal("Архетип: rick, morty, jerry, summer, beth или other."));
			return 0;
		}
		Role role = r.roles.archetype(t.uuid(), a, r.clock.millis());
		if (role == null) {
			c.getSource().sendFailure(Component.literal("У " + t.name() + " нет роли: сначала /rickadmin role set."));
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal(t.name() + " — " + role.title() + " (" + a.id() + ", вручную)"), true);
		return 1;
	}

	private static int roleClear(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		Target t = r == null ? null : resolve(c, r);
		if (t == null) {
			return 0;
		}
		Role old = r.roles.clear(t.uuid());
		c.getSource().sendSuccess(() -> Component.literal(old == null ? "У " + t.name() + " и так нет роли." : t.name() + ": роль «" + old.title() + "» снята"), true);
		return old == null ? 0 : 1;
	}

	private static int roleList(CommandContext<CommandSourceStack> c, Supplier<RikoshetRuntime> rt) {
		RikoshetRuntime r = Cmd.runtime(c, rt);
		if (r == null) {
			return 0;
		}
		List<Role> all = r.roles.all();
		MutableComponent out = Component.literal("Роли (" + all.size() + "):").withStyle(ChatFormatting.GOLD);
		for (Role role : all) {
			out.append(Component.literal("\n" + role.name() + " — " + role.title()).withStyle(ChatFormatting.WHITE));
			out.append(Component.literal(" (" + role.archetype().id() + (role.archetypeManual() ? ", вручную" : "") + ")")
					.withStyle(ChatFormatting.DARK_GRAY));
			if (role.note() != null) {
				out.append(Component.literal(": " + role.note()).withStyle(ChatFormatting.GRAY));
			}
			if (r.players.optedOut(role.uuid())) {
				out.append(Component.literal(" [/rick off]").withStyle(ChatFormatting.DARK_RED));
			}
		}
		c.getSource().sendSuccess(() -> out, false);
		return all.size();
	}
}
