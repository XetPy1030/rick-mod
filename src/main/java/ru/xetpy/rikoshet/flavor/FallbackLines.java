package ru.xetpy.rikoshet.flavor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Рукописные заготовки персонажа: rikoshet/fallback/<persona>.json в моде или на сервере.
 * Выбор — по списку ключей от частного к общему, без повторов последних реплик.
 */
public final class FallbackLines {
	private static final Pattern VAR = Pattern.compile("\\{([a-z_]+)}");
	private static final int NO_REPEAT = 8;

	private final Map<String, Map<String, List<String>>> sections;
	private final RandomGenerator random;
	private final Deque<String> recent = new ArrayDeque<>();
	/** Реплики пулов от ИИ: подмешиваются к рукописным (docs/architecture/ai-integration.md#пулы-заготовок). */
	private volatile Map<String, Map<String, List<String>>> extra = Map.of();
	/** Кому сообщить, какая реплика (до подстановки) выбрана: пулы считают показы. */
	private volatile java.util.function.Consumer<String> picked = s -> {
	};

	public void setExtra(Map<String, Map<String, List<String>>> extra, java.util.function.Consumer<String> picked) {
		this.extra = extra == null ? Map.of() : extra;
		this.picked = picked == null ? s -> {
		} : picked;
	}

	FallbackLines(Map<String, Map<String, List<String>>> sections, RandomGenerator random) {
		this.sections = sections;
		this.random = random;
	}

	/** Файл на сервере важнее файла в моде и заменяет его целиком. */
	public static FallbackLines load(String persona, Path overrideDir, RandomGenerator random) {
		String text;
		Path file = overrideDir == null ? null : overrideDir.resolve(persona + ".json");
		try {
			if (file != null && Files.isRegularFile(file)) {
				text = Files.readString(file, StandardCharsets.UTF_8);
			} else {
				try (InputStream in = FallbackLines.class.getResourceAsStream("/rikoshet/fallback/" + persona + ".json")) {
					if (in == null) {
						return new FallbackLines(Map.of(), random);
					}
					text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new FallbackLines(parse(JsonParser.parseString(text).getAsJsonObject()), random);
	}

	static Map<String, Map<String, List<String>>> parse(JsonObject root) {
		Map<String, Map<String, List<String>>> out = new HashMap<>();
		for (var s : root.entrySet()) {
			if (s.getKey().startsWith("_") || !s.getValue().isJsonObject()) {
				continue;
			}
			Map<String, List<String>> keys = new HashMap<>();
			for (var k : s.getValue().getAsJsonObject().entrySet()) {
				List<String> lines = new ArrayList<>();
				if (k.getValue().isJsonArray()) {
					for (JsonElement l : k.getValue().getAsJsonArray()) {
						lines.add(l.getAsString());
					}
				}
				keys.put(k.getKey(), List.copyOf(lines));
			}
			out.put(s.getKey(), keys);
		}
		return out;
	}

	/** Выбор из первого подходящего ключа. */
	public record Choice(String section, String key, double chance) {
	}

	/**
	 * Идёт по вариантам по порядку: вариант берётся с его вероятностью, если в нём есть реплика,
	 * для которой хватает переменных. Не выбран ни один — null.
	 */
	public synchronized String pick(List<Choice> choices, Map<String, String> vars) {
		for (Choice c : choices) {
			if (c.chance() < 1 && random.nextDouble() >= c.chance()) {
				continue;
			}
			List<String> own = sections.getOrDefault(c.section(), Map.of()).getOrDefault(c.key(), List.of());
			List<String> pool = extra.getOrDefault(c.section(), Map.of()).getOrDefault(c.key(), List.of());
			List<String> lines = pool.isEmpty() ? own : java.util.stream.Stream.concat(own.stream(), pool.stream()).toList();
			List<String> usable = lines.stream().filter(l -> fits(l, vars)).toList();
			if (usable.isEmpty()) {
				continue;
			}
			List<String> fresh = usable.stream().filter(l -> !recent.contains(l)).toList();
			List<String> from = fresh.isEmpty() ? usable : fresh;
			String line = from.get(random.nextInt(from.size()));
			recent.addLast(line);
			while (recent.size() > NO_REPEAT) {
				recent.removeFirst();
			}
			picked.accept(line);
			return fill(line, vars);
		}
		return null;
	}

	static boolean fits(String line, Map<String, String> vars) {
		Matcher m = VAR.matcher(line);
		while (m.find()) {
			String v = vars.get(m.group(1));
			if (v == null || v.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	static String fill(String line, Map<String, String> vars) {
		Matcher m = VAR.matcher(line);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(sb, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), "")));
		}
		m.appendTail(sb);
		// «{killer} — один…»: моб в начале реплики — с большой буквы. Ники не трогаем: salt115 — это имя
		if (line.startsWith("{killer}") && !sb.isEmpty()) {
			sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
		}
		return sb.toString();
	}

	/** Все реплики — для тестов. */
	Map<String, Map<String, List<String>>> sections() {
		return sections;
	}
}
