package ru.xetpy.rikoshet.ai;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Последний фильтр перед чатом. Чистит то, что можно почистить (коды цвета §, markdown,
 * эмодзи, лишние пробелы, обрамляющие кавычки), и отклоняет реплику, если чистить нельзя:
 * ссылка, иероглифы, стоп-слово. Длинная реплика обрезается по концу фразы.
 */
public final class TextFilter {
	private static final Pattern SECTION = Pattern.compile("§.?", Pattern.DOTALL);
	private static final Pattern BOLD = Pattern.compile("\\*\\*|__|`");
	private static final Pattern HEADING = Pattern.compile("(?m)^\\s*#{1,6}\\s+");
	private static final Pattern SPACES = Pattern.compile("\\s+");
	private static final Pattern LINK = Pattern.compile(
			"(?iU)(https?://|www\\.|\\b[\\p{L}\\p{N}-]{2,}\\.(?:ru|com|net|org|gg|io|me|xyz|su|рф|info|link|site|ly)\\b)");
	private static final String QUOTES = "\"'«»“”„";

	private TextFilter() {
	}

	/** text — готовая реплика или null; reason — почему отклонена. */
	public record Result(String text, String reason) {
		public boolean ok() {
			return text != null;
		}

		static Result reject(String reason) {
			return new Result(null, reason);
		}
	}

	public static Result apply(String raw, int maxLength, List<String> blocklist) {
		if (raw == null) {
			return Result.reject("пусто");
		}
		String s = SECTION.matcher(raw).replaceAll("");
		s = HEADING.matcher(s).replaceAll("");
		s = BOLD.matcher(s).replaceAll("");
		s = stripSymbols(s);
		s = SPACES.matcher(s).replaceAll(" ").strip();
		s = unquote(s);
		if (s.isEmpty()) {
			return Result.reject("пусто");
		}
		if (LINK.matcher(s).find()) {
			return Result.reject("ссылка");
		}
		if (hasForeignScript(s)) {
			return Result.reject("иероглифы");
		}
		String low = normalize(s);
		for (String word : blocklist) {
			String w = normalize(word.strip());
			if (!w.isEmpty() && low.contains(w)) {
				return Result.reject("стоп-слово");
			}
		}
		return new Result(truncate(s, maxLength), null);
	}

	/** Убирает управляющие символы, эмодзи (всё вне BMP), селекторы вариантов и склейки эмодзи. */
	private static String stripSymbols(String s) {
		StringBuilder out = new StringBuilder(s.length());
		s.codePoints().forEach(cp -> {
			if (Character.isSupplementaryCodePoint(cp)) {
				return;
			}
			if (cp == '\n' || cp == '\t') {
				out.append(' ');
				return;
			}
			if (Character.isISOControl(cp)
					|| (cp >= 0xFE00 && cp <= 0xFE0F)   // селекторы вариантов
					|| cp == 0x200D || cp == 0x20E3     // склейка эмодзи, keycap
					|| cp == 0x200B || cp == 0xFEFF) {  // пробел нулевой ширины, BOM
				return;
			}
			out.appendCodePoint(cp);
		});
		return out.toString();
	}

	private static String unquote(String s) {
		while (s.length() >= 2 && QUOTES.indexOf(s.charAt(0)) >= 0 && QUOTES.indexOf(s.charAt(s.length() - 1)) >= 0) {
			String inner = s.substring(1, s.length() - 1);
			// «…» внутри — это цитата в реплике, а не обёртка
			if (inner.indexOf(s.charAt(0)) >= 0 || inner.indexOf(s.charAt(s.length() - 1)) >= 0) {
				break;
			}
			s = inner.strip();
		}
		return s;
	}

	private static boolean hasForeignScript(String s) {
		return s.codePoints().anyMatch(cp -> {
			Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
			return sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HANGUL
					|| sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA;
		});
	}

	static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
	}

	/** Обрезает до max символов: по концу предложения во второй половине, иначе по пробелу с «…». */
	static String truncate(String s, int max) {
		if (s.length() <= max) {
			return s;
		}
		String cut = s.substring(0, max);
		int sentence = Math.max(Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("! ")), cut.lastIndexOf("? "));
		if (sentence >= max / 2) {
			return cut.substring(0, sentence + 1);
		}
		String head = s.substring(0, max - 1);
		int space = head.lastIndexOf(' ');
		if (space >= max / 2) {
			head = head.substring(0, space);
		}
		return head.stripTrailing().replaceAll("[,;:—–-]+$", "") + "…";
	}
}
