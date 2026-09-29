package ru.xetpy.rikoshet.voice;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.ai.AiRoute;
import ru.xetpy.rikoshet.ai.AiService;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.storage.PlayerStore;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Озвучка живых реплик персонажей (docs/design/voice.md). Синтез — в своём потоке, расшифровка
 * сверяется с текстом, звук играет от NPC через Simple Voice Chat. Реплики одного NPC идут по
 * очереди, не поверх друг друга. С sync_text текст появляется вместе со звуком, как субтитры;
 * не вышло озвучить — текст сразу, без звука.
 */
public final class VoiceService {
	public static final String ROUTE = "voice_out";
	/** Реплика прождала бы очередь дольше — не озвучиваем, только текст. */
	static final long MAX_QUEUE_MS = 12_000;
	/** Пауза между репликами одного NPC. */
	static final long GAP_MS = 250;

	private final Logger log;
	private final Clock clock;
	private final Supplier<RikoshetConfig> config;
	private final AiService ai;
	private final PlayerStore players;
	private final MinecraftServer server;
	/** Синтез начат (+1) или закончен (−1): Рик «думает». Главный поток. */
	private final IntConsumer thinking;
	/** Рик говорит вслух до этого момента. Главный поток. */
	private final LongConsumer speaking;
	private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "rikoshet-voice");
		t.setDaemon(true);
		return t;
	});
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "rikoshet-voice-queue");
		t.setDaemon(true);
		return t;
	});
	/** До какого момента занят голос каждого NPC. Главный поток. */
	private final Map<UUID, Long> busyUntil = new HashMap<>();
	private volatile boolean stopping;
	private int played;
	private int dropped;

	public VoiceService(Logger log, Clock clock, Supplier<RikoshetConfig> config, AiService ai, PlayerStore players, MinecraftServer server,
			IntConsumer thinking, LongConsumer speaking) {
		this.log = log;
		this.clock = clock;
		this.config = config;
		this.ai = ai;
		this.players = players;
		this.server = server;
		this.thinking = thinking;
		this.speaking = speaking;
	}

	/**
	 * Сказать реплику: show выводит её текст. Озвучить не выйдет (нет голосового чата, ключа, бюджета,
	 * флага) — show сразу. Иначе show — вместе с началом звука (sync_text) или сразу, а звук догонит.
	 * Главный поток; сама озвучка — в фоне.
	 */
	public void speak(Entity source, String persona, String text, Runnable show) {
		RikoshetConfig cfg = config.get();
		RikoshetConfig.VoiceSpec spec = cfg.voice().of(persona);
		AiRoute route = cfg.ai().route(ROUTE);
		if (stopping || source == null || text == null || text.isBlank() || spec == null || !cfg.feature("voice") || !VoiceOut.available()
				|| !cfg.ai().enabled() || ai.paused() || !ai.secrets().hasKey() || !ai.budget().canSpend()
				|| route == null || route.models().isEmpty()) {
			show.run();
			return;
		}
		boolean sync = cfg.voice().syncText();
		Runnable showOnce = once(show);
		if (!sync) {
			showOnce.run();
		}
		String model = route.models().getFirst().id();
		String base = cfg.ai().baseUrl();
		String key = ai.secrets().openRouterKey();
		Duration timeout = Duration.ofSeconds(cfg.voice().ttsTimeoutSeconds());
		float distance = cfg.voice().radiusBlocks();
		double minFidelity = cfg.voice().minFidelity();
		java.util.List<String> blocklist = cfg.content().blocklist();
		long start = clock.millis();
		thinking.accept(1);
		try {
			pool.submit(() -> {
				Tts.Result r = Tts.synth(base, key, model, spec.voice(), spec.style(), text, timeout);
				Tts.Match m = r.ok() ? Tts.match(text, r.transcript()) : new Tts.Match(0, 0, 0);
				// Добавленное моделью от себя проходит тот же фильтр, что и текст реплики
				boolean clean = r.ok() && ru.xetpy.rikoshet.ai.TextFilter.apply(r.transcript(), Integer.MAX_VALUE, blocklist).ok();
				long took = clock.millis() - start;
				String status = !r.ok() ? ("timeout".equals(r.error()) ? "timeout" : "error") : m.ok(minFidelity) && clean ? "ok" : "invalid";
				String why = String.format("прочитано %.2f, лишних слов %d%s", m.recall(), m.extra(), clean ? "" : ", не прошло фильтр");
				ai.recordBatchResult(ROUTE, "voice", model, status, r.usage(), r.transcript(),
						r.error() != null ? r.error() : status.equals("invalid") ? why : null);
				boolean ok = status.equals("ok") && took <= timeout.toMillis();
				short[] pcm = ok ? Tts.to48k(r.pcm()) : null;
				if (!ok) {
					log.info("[голос] {}: {}, {}, {} мс", persona, status, why, took);
				}
				server.execute(() -> {
					thinking.accept(-1);
					if (pcm == null) {
						dropped++;
						showOnce.run();
					} else {
						enqueue(source, pcm, distance, showOnce);
					}
				});
			});
		} catch (RejectedExecutionException e) {
			thinking.accept(-1);
			showOnce.run();
		}
	}

	/** В очередь голоса NPC: играет после предыдущей реплики. Главный поток. */
	private void enqueue(Entity source, short[] pcm, float distance, Runnable show) {
		long now = clock.millis();
		long at = Math.max(now, busyUntil.getOrDefault(source.getUUID(), 0L));
		if (at - now > MAX_QUEUE_MS) {
			dropped++;
			show.run();
			return;
		}
		long durationMs = pcm.length * 1000L / 48_000;
		busyUntil.put(source.getUUID(), at + durationMs + GAP_MS);
		Runnable play = () -> {
			show.run();
			if (!source.isRemoved() && VoiceOut.play(source, pcm, distance, u -> !players.optedOut(u) && !players.voiceOff(u))) {
				played++;
				speaking.accept(clock.millis() + durationMs);
			}
		};
		if (at <= now) {
			play.run();
		} else {
			timer.schedule(() -> server.execute(play), at - now, TimeUnit.MILLISECONDS);
		}
	}

	private static Runnable once(Runnable r) {
		boolean[] done = {false};
		return () -> {
			if (!done[0]) {
				done[0] = true;
				r.run();
			}
		};
	}

	public String status() {
		return "Голос: " + (config.get().feature("voice") ? "включён" : "выключен") + ", голосовой чат "
				+ (VoiceOut.available() ? "подключён" : "не подключён") + "; с запуска озвучено " + played + ", отброшено " + dropped;
	}

	public void stopping() {
		stopping = true;
		pool.shutdownNow();
		timer.shutdownNow();
	}
}
