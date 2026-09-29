package ru.xetpy.rikoshet.chat;

import com.google.gson.JsonObject;
import ru.xetpy.rikoshet.ai.TextFilter;
import ru.xetpy.rikoshet.chronicle.ChronicleEvent;
import ru.xetpy.rikoshet.chronicle.ChronicleService;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.storage.DailyStats;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Заметки Рика об игроке из разговоров — в чате и в лаборатории: обиды, похвала, обещания, признания
 * становятся событием летописи chat_note и эпизодом памяти (docs/design/chat.md#память-что-запомнить).
 * Память у каналов общая. Главный поток.
 */
public final class NoteSaver {
	static final int NOTES_PER_DAY = 3;
	static final int NOTE_CHARS = 160;
	/** Важность заметок: всё выше порога эпизода памяти. Просьбы не храним. */
	static final Map<String, Integer> NOTE_SCORE = Map.of("insult", 8, "praise", 6, "promise", 8, "confession", 7);
	public static final Set<String> RECALL_TAGS = Set.of(ChronicleEvent.CHAT_NOTE, "kind:insult", "kind:praise", "kind:promise", "kind:confession");

	private final Supplier<RikoshetConfig> config;
	private final Supplier<LocalDate> today;
	private final DailyStats stats;
	private final ChronicleService chronicle;

	public NoteSaver(Supplier<RikoshetConfig> config, Supplier<LocalDate> today, DailyStats stats, ChronicleService chronicle) {
		this.config = config;
		this.today = today;
		this.stats = stats;
		this.chronicle = chronicle;
	}

	/** Заметка вида kind; не той важности, пустая или сверх лимита в сутки — не сохраняется. */
	public void save(UUID u, String kind, String note, long now) {
		Integer score = NOTE_SCORE.get(kind);
		if (score == null || note == null || note.isBlank()) {
			return;
		}
		if (stats.increment(u, "chat.note") > NOTES_PER_DAY) {
			return;
		}
		TextFilter.Result f = TextFilter.apply(note.strip(), NOTE_CHARS, config.get().content().blocklist());
		if (!f.ok()) {
			return;
		}
		JsonObject d = new JsonObject();
		d.addProperty("text", f.text());
		d.addProperty("kind", kind);
		chronicle.record(new ChronicleEvent(now, today.get().toString(), u, ChronicleEvent.CHAT_NOTE, kind, score, d));
	}
}
