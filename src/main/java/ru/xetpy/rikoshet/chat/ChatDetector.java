package ru.xetpy.rikoshet.chat;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Это Рику?» по тексту сообщения (docs/design/chat.md#детектор-это-рику). Решает код, а не
 * модель: на «токсик рик где ты» модели отвечают почти всегда. Чистые функции, главный поток.
 */
public final class ChatDetector {
	public enum Kind {
		/** Обращение: «рик, …», «эй рик», «…, рик». */
		ADDRESS,
		/** Упоминание: «что с риком». */
		MENTION,
		NONE
	}

	private static final String W = "[\\p{L}\\p{N}_]";
	private static final Pattern RICK = Pattern.compile(
			"(?<!" + W + ")(рик|рика|рику|риком|рике|рикки|рики|рикуля|рикуша|rick|rik)(?!" + W + ")",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	/** Формы, которыми зовут; косвенные падежи («рику», «риком») — упоминание. */
	private static final Set<String> CALL = Set.of("рик", "рикки", "рики", "рикуля", "рикуша", "rick", "rik");
	/** Что может стоять перед именем в обращении: «эй рик», «слышь, рик». */
	private static final Set<String> LEAD = Set.of("эй", "слышь", "ну", "а", "о", "ой", "ау", "алло", "yo", "hey", "hi", "привет", "здарова");
	private static final Pattern WORD = Pattern.compile(W + "+");

	private ChatDetector() {
	}

	/**
	 * qualifiers — слова перед «Рик» в ролях игроков («токсик», «фермер», «злой коп»): с ними
	 * «рик» — это игрок, а не персонаж.
	 */
	public static Kind classify(String text, Collection<String> qualifiers) {
		String t = normalize(text);
		boolean mention = false;
		Matcher m = RICK.matcher(t);
		while (m.find()) {
			List<String> before = words(t.substring(0, m.start()));
			if (qualified(before, qualifiers)) {
				continue;
			}
			String w = m.group(1);
			if (CALL.contains(w) && (atStart(before) || atEnd(t, m.end()) || calledBy(t, m.end()))) {
				return Kind.ADDRESS;
			}
			mention = true;
		}
		return mention ? Kind.MENTION : Kind.NONE;
	}

	/** Сообщение начинается с обращения к другому игроку: «морти, иди сюда», «salt115 ты где». */
	public static boolean addressedToOther(String text, Collection<String> names) {
		String t = normalize(text).replaceFirst("^[\\s@]+", "");
		for (String n : names) {
			String name = normalize(n);
			if (name.isEmpty() || !t.startsWith(name)) {
				continue;
			}
			if (t.length() == name.length() || !Character.isLetterOrDigit(t.charAt(name.length()))) {
				return true;
			}
		}
		return false;
	}

	public static int wordCount(String text) {
		return words(normalize(text)).size();
	}

	/** Слова перед «Рик» в названиях ролей: «Токсик Рик» → «токсик», «Злой коп Рик» → «злой коп». */
	public static Set<String> qualifiers(Collection<String> roleTitles) {
		Set<String> out = new HashSet<>();
		for (String title : roleTitles) {
			List<String> w = words(normalize(title));
			for (int i = 1; i < w.size(); i++) {
				if (CALL.contains(w.get(i))) {
					out.add(String.join(" ", w.subList(0, i)));
				}
			}
		}
		return out;
	}

	static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replace('ё', 'е').strip();
	}

	private static List<String> words(String s) {
		List<String> out = new java.util.ArrayList<>();
		Matcher m = WORD.matcher(s);
		while (m.find()) {
			out.add(m.group());
		}
		return out;
	}

	private static boolean qualified(List<String> before, Collection<String> qualifiers) {
		for (String q : qualifiers) {
			List<String> qw = words(q);
			if (!qw.isEmpty() && before.size() >= qw.size() && before.subList(before.size() - qw.size(), before.size()).equals(qw)) {
				return true;
			}
		}
		return false;
	}

	private static boolean atStart(List<String> before) {
		return before.stream().allMatch(LEAD::contains);
	}

	private static boolean atEnd(String t, int end) {
		return words(t.substring(end)).isEmpty();
	}

	private static boolean calledBy(String t, int end) {
		String rest = t.substring(end).stripLeading();
		return !rest.isEmpty() && ",!?:".indexOf(rest.charAt(0)) >= 0;
	}
}
