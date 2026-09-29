package ru.xetpy.rikoshet.core;

import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.ai.AiRoute;
import ru.xetpy.rikoshet.ai.ModelSpec;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Конфиг мода, неизменяемый. Значения по умолчанию здесь и в {@code rikoshet/default-config.json5}
 * совпадают, это проверяет тест. Описание ключей — docs/ops/configuration.md.
 */
public record RikoshetConfig(
		int protocolVersion,
		ZoneId timezone,
		Map<String, Boolean> features,
		Ai ai,
		Flavor flavor,
		Content content,
		Performance performance,
		Storage storage,
		Chronicle chronicle,
		Newspaper newspaper
) {
	public static final int PROTOCOL_VERSION = 1;

	/** Флаги и их значения по умолчанию. Новая фича — новый флаг, по умолчанию выключен. */
	public static final Map<String, Boolean> FEATURE_DEFAULTS;

	static {
		Map<String, Boolean> f = new LinkedHashMap<>();
		f.put("rick", true);
		f.put("death_messages", true);
		f.put("join_leave", true);
		f.put("chronicle", false);
		f.put("builds", false);
		f.put("newspaper", false);
		f.put("motd_tab", false);
		f.put("roles_in_tab", false);
		f.put("voice", false);
		f.put("visits", false);
		f.put("mini_events", false);
		f.put("gadgets", false);
		f.put("events", false);
		FEATURE_DEFAULTS = java.util.Collections.unmodifiableMap(f);
	}

	public boolean feature(String name) {
		return features.getOrDefault(name, false);
	}

	public record Ai(
			boolean enabled,
			String baseUrl,
			double dailyBudgetUsd,
			int maxConcurrent,
			int requestsPerMinute,
			Map<String, AiRoute> routes,
			boolean batch,
			int batchHour
	) {
		public AiRoute route(String name) {
			AiRoute r = routes.get(name);
			return r != null ? r : DEFAULT_ROUTES.get(name);
		}
	}

	public record Flavor(
			int deathCooldownSeconds,
			int seriesWindowMinutes,
			int leaveDelaySeconds,
			int rejoinQuietMinutes
	) {
	}

	public record Content(int maxMessageLength, List<String> blocklist) {
	}

	public record Performance(double msptSoft, double msptHard, int recoverSeconds) {
	}

	public record Storage(String path, int dialogueRetentionDays, int aiLogRetentionDays) {
	}

	/** Летопись: как часто снимать статистику и замерять положение (docs/design/chronicle.md). */
	public record Chronicle(int snapshotMinutes, int sampleSeconds) {
	}

	/** Газета: во сколько выходит (час по timezone) и сколько фактов получает редакция. */
	public record Newspaper(int hour, int maxFacts) {
	}

	public static final Map<String, AiRoute> DEFAULT_ROUTES;

	static {
		Map<String, AiRoute> r = new LinkedHashMap<>();
		r.put("flavor", new AiRoute("flavor", models("deepseek/deepseek-v4.1-flash", "openai/gpt-6-luna"), "none", 1024, 5, 2500));
		r.put("dialogue", new AiRoute("dialogue", models("openai/gpt-6-sol", "x-ai/grok-4.7"), "low", 1536, 15));
		r.put("newspaper", new AiRoute("newspaper", models("anthropic/claude-opus-5.5", "moonshotai/kimi-k3"), "low", 4096, 120));
		r.put("pools", new AiRoute("pools", models("moonshotai/kimi-k3", "anthropic/claude-sonnet-5"), "low", 3072, 120));
		r.put("analyst", new AiRoute("analyst", models("deepseek/deepseek-v4.1-flash", "openai/gpt-6-luna"), "low", 2048, 90));
		DEFAULT_ROUTES = java.util.Collections.unmodifiableMap(r);
	}

	private static List<ModelSpec> models(String... specs) {
		List<ModelSpec> out = new ArrayList<>();
		for (String s : specs) {
			out.add(ModelSpec.parse(s, null));
		}
		return List.copyOf(out);
	}

	public static RikoshetConfig defaults() {
		return read(new JsonObject(), new ArrayList<>(), new ArrayList<>());
	}

	/** Читает конфиг. Ошибки типов и диапазонов копятся в errors, неизвестные ключи — в warnings. */
	public static RikoshetConfig read(JsonObject root, List<String> warnings, List<String> errors) {
		ConfigReader r = new ConfigReader(root, "", warnings, errors);
		int protocol = r.integer("protocol_version", PROTOCOL_VERSION, 1, 1000);
		if (protocol != PROTOCOL_VERSION) {
			warnings.add("protocol_version " + protocol + ", мод ожидает " + PROTOCOL_VERSION);
		}

		String tz = r.string("timezone", "Europe/Moscow");
		ZoneId zone;
		try {
			zone = ZoneId.of(tz);
		} catch (DateTimeException e) {
			errors.add("timezone: неизвестный часовой пояс " + tz);
			zone = ZoneId.of("Europe/Moscow");
		}

		ConfigReader fr = r.section("features");
		Map<String, Boolean> features = new LinkedHashMap<>();
		FEATURE_DEFAULTS.forEach((k, v) -> features.put(k, fr.bool(k, v)));
		fr.finish();

		ConfigReader ar = r.section("ai");
		boolean aiEnabled = ar.bool("enabled", true);
		String baseUrl = ar.string("base_url", "https://openrouter.ai/api/v1");
		if (!baseUrl.startsWith("https://")) {
			errors.add("ai.base_url: нужен https://");
			baseUrl = "https://openrouter.ai/api/v1";
		}
		double budget = ar.number("daily_budget_usd", 2.0, 0, 1000);
		int maxConcurrent = ar.integer("max_concurrent", 4, 1, 16);
		int rpm = ar.integer("requests_per_minute", 20, 1, 600);
		int defTimeout = ar.integer("timeout_seconds", 15, 1, 300);
		String defReasoning = ar.string("reasoning_effort", "low");
		checkEffort(defReasoning, "ai.reasoning_effort", errors);
		ar.reserve("language");
		boolean batch = ar.bool("batch", true);
		int batchHour = ar.integer("batch_hour", 3, 0, 23);
		Map<String, AiRoute> routes = new LinkedHashMap<>(DEFAULT_ROUTES);
		ConfigReader rr = ar.section("routes");
		for (String name : rr.keys()) {
			ConfigReader one = rr.section(name);
			AiRoute base = DEFAULT_ROUTES.get(name);
			List<String> specs = one.strings("models", base == null ? List.of() : base.models().stream().map(ModelSpec::toString).toList());
			List<ModelSpec> models = new ArrayList<>();
			for (String s : specs) {
				ModelSpec m = ModelSpec.parse(s, errors);
				if (m != null) {
					models.add(m);
				}
			}
			if (models.isEmpty() && base != null) {
				errors.add("ai.routes." + name + ".models: пустой список");
				models.addAll(base.models());
			}
			String reasoning = one.string("reasoning", base == null ? defReasoning : base.reasoning());
			checkEffort(reasoning, "ai.routes." + name + ".reasoning", errors);
			int maxTokens = one.integer("max_tokens", base == null ? 1024 : base.maxTokens(), 16, 32768);
			int timeout = one.integer("timeout_seconds", base == null ? defTimeout : base.timeoutSeconds(), 1, 600);
			double hedge = one.number("hedge_seconds", base == null ? 0 : base.hedgeMillis() / 1000.0, 0, 600);
			if (hedge > 0 && hedge >= timeout) {
				errors.add("ai.routes." + name + ".hedge_seconds: должно быть меньше timeout_seconds, гонка выключена");
				hedge = 0;
			}
			one.finish();
			routes.put(name, new AiRoute(name, List.copyOf(models), reasoning, maxTokens, timeout, (int) Math.round(hedge * 1000)));
		}
		ar.finish();
		Ai ai = new Ai(aiEnabled, stripSlash(baseUrl), budget, maxConcurrent, rpm, java.util.Collections.unmodifiableMap(routes),
				batch, batchHour);

		ConfigReader flr = r.section("flavor");
		Flavor flavor = new Flavor(
				flr.integer("death_cooldown_seconds", 20, 0, 3600),
				flr.integer("series_window_minutes", 10, 1, 240),
				flr.integer("leave_delay_seconds", 15, 0, 300),
				flr.integer("rejoin_quiet_minutes", 3, 0, 240));
		flr.finish();

		ConfigReader cr = r.section("content");
		cr.reserve("profanity");
		Content content = new Content(
				cr.integer("max_message_length", 256, 40, 1000),
				cr.strings("blocklist", List.of()));
		cr.finish();

		ConfigReader pr = r.section("performance");
		double soft = pr.number("mspt_soft", 35, 1, 1000);
		double hard = pr.number("mspt_hard", 45, 1, 1000);
		if (hard < soft) {
			errors.add("performance.mspt_hard меньше mspt_soft");
			hard = soft;
		}
		Performance perf = new Performance(soft, hard, pr.integer("recover_seconds", 60, 1, 3600));
		pr.finish();

		ConfigReader sr = r.section("storage");
		Storage storage = new Storage(
				sr.string("path", "rikoshet/rikoshet.db"),
				sr.integer("dialogue_retention_days", 14, 1, 3650),
				sr.integer("ai_log_retention_days", 90, 1, 3650));
		sr.finish();

		ConfigReader chr = r.section("chronicle");
		Chronicle chronicle = new Chronicle(
				chr.integer("snapshot_minutes", 5, 1, 30),
				chr.integer("sample_seconds", 15, 5, 120));
		chr.finish();

		ConfigReader nr = r.section("newspaper");
		Newspaper newspaper = new Newspaper(
				nr.integer("hour", 7, 0, 23),
				nr.integer("max_facts", 25, 5, 60));
		nr.finish();

		r.reserve("personas", "visits", "voice");
		r.finish();
		return new RikoshetConfig(protocol, zone, java.util.Collections.unmodifiableMap(features), ai, flavor, content, perf, storage,
				chronicle, newspaper);
	}

	private static final List<String> EFFORTS = List.of("none", "minimal", "low", "medium", "high", "default");

	private static void checkEffort(String effort, String where, List<String> errors) {
		if (!EFFORTS.contains(effort)) {
			errors.add(where + ": " + effort + " — ожидалось " + String.join(", ", EFFORTS));
		}
	}

	private static String stripSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
