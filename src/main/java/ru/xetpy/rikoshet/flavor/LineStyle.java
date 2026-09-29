package ru.xetpy.rikoshet.flavor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Что мод решает за модель в одной реплике (docs/design/flavor.md#разнообразие-реплик). Каждый
 * запрос для модели первый, «через раз» она не умеет: на живом сервере *рыг* стоял в 32 репликах
 * из 32, а фразочка из заметки о роли — почти в каждой реплике этому игроку. Поэтому кубик
 * бросает мод и дописывает решение в хвост системного промпта: там указания, а не данные.
 */
final class LineStyle {
	/** Доля реплик, где *рыг* разрешён. Модель им пользуется почти всегда, когда можно. */
	static final double BURP = 0.35;
	/** Доля реплик, где коронные фразы ролей запрещены явно. Общий запрет DeepSeek не понимает. */
	static final double NO_CATCHPHRASE = 0.8;
	/** Доля реплик «совсем коротко». */
	static final double SHORT = 0.25;
	static final int SHORT_CHARS = 60;
	/** Коронная фраза в заметке о роли — в ёлочках: «Ууу-ии!». */
	private static final Pattern QUOTED = Pattern.compile("«([^«»\\n]{2,30})»");

	private LineStyle() {
	}

	/** Хвост системного промпта или пустая строка. notes — заметки о ролях тех, о ком реплика и кто онлайн. */
	static String tail(Random rnd, Collection<String> notes) {
		List<String> lines = new ArrayList<>();
		if (rnd.nextDouble() >= BURP) {
			lines.add("- Без *рыг* и других звуков.");
		}
		Set<String> phrases = catchphrases(notes);
		if (!phrases.isEmpty() && rnd.nextDouble() < NO_CATCHPHRASE) {
			lines.add("- Без «" + String.join("», «", phrases) + "».");
		}
		if (rnd.nextDouble() < SHORT) {
			lines.add("- Совсем коротко: до " + SHORT_CHARS + " символов.");
		}
		return lines.isEmpty() ? "" : "\n\n## Эта реплика\n\n" + String.join("\n", lines);
	}

	static Set<String> catchphrases(Collection<String> notes) {
		Set<String> out = new LinkedHashSet<>();
		for (String n : notes) {
			if (n == null) {
				continue;
			}
			Matcher m = QUOTED.matcher(n);
			while (m.find()) {
				out.add(m.group(1).strip());
			}
		}
		return out;
	}
}
