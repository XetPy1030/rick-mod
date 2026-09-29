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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Озвучка живых реплик персонажей (docs/design/voice.md): текст уже в чате, звук догоняет. Синтез —
 * в своём потоке, расшифровка сверяется с текстом, звук играет от NPC через Simple Voice Chat.
 * Не успели за tts_timeout_seconds, не совпало, нет голосового чата или бюджета — только текст.
 */
public final class VoiceService {
	public static final String ROUTE = "voice_out";

	private final Logger log;
	private final Clock clock;
	private final Supplier<RikoshetConfig> config;
	private final AiService ai;
	private final PlayerStore players;
	private final MinecraftServer server;
	private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "rikoshet-voice");
		t.setDaemon(true);
		return t;
	});
	private volatile boolean stopping;
	private int played;
	private int dropped;

	public VoiceService(Logger log, Clock clock, Supplier<RikoshetConfig> config, AiService ai, PlayerStore players, MinecraftServer server) {
		this.log = log;
		this.clock = clock;
		this.config = config;
		this.ai = ai;
		this.players = players;
		this.server = server;
	}

	/** Озвучить реплику от сущности. Главный поток; сама озвучка — в фоне. */
	public void speak(Entity source, String persona, String text) {
		RikoshetConfig cfg = config.get();
		RikoshetConfig.VoiceSpec spec = cfg.voice().of(persona);
		if (stopping || source == null || text == null || text.isBlank() || spec == null || !cfg.feature("voice") || !VoiceOut.available()
				|| !cfg.ai().enabled() || ai.paused() || !ai.secrets().hasKey() || !ai.budget().canSpend()) {
			return;
		}
		AiRoute route = cfg.ai().route(ROUTE);
		if (route == null || route.models().isEmpty()) {
			return;
		}
		String model = route.models().getFirst().id();
		String base = cfg.ai().baseUrl();
		String key = ai.secrets().openRouterKey();
		Duration timeout = Duration.ofSeconds(cfg.voice().ttsTimeoutSeconds());
		float distance = cfg.voice().radiusBlocks();
		double minFidelity = cfg.voice().minFidelity();
		long start = clock.millis();
		try {
			pool.submit(() -> {
				Tts.Result r = Tts.synth(base, key, model, spec.voice(), spec.style(), text, timeout);
				double fid = r.ok() ? Tts.fidelity(text, r.transcript()) : 0;
				long took = clock.millis() - start;
				String status = !r.ok() ? ("timeout".equals(r.error()) ? "timeout" : "error") : fid < minFidelity ? "invalid" : "ok";
				ai.recordBatchResult(ROUTE, "voice", model, status, r.usage(), r.transcript(),
						r.error() != null ? r.error() : status.equals("invalid") ? String.format("совпадение %.2f", fid) : null);
				if (!status.equals("ok") || took > timeout.toMillis()) {
					dropped++;
					log.info("[голос] {}: {}, совпадение {}, {} мс", persona, status, String.format("%.2f", fid), took);
					return;
				}
				short[] pcm = Tts.to48k(r.pcm());
				server.execute(() -> {
					if (!source.isRemoved() && VoiceOut.play(source, pcm, distance, u -> !players.optedOut(u) && !players.voiceOff(u))) {
						played++;
					}
				});
			});
		} catch (RejectedExecutionException e) {
			dropped++;
		}
	}

	public String status() {
		return "Голос: " + (config.get().feature("voice") ? "включён" : "выключен") + ", голосовой чат "
				+ (VoiceOut.available() ? "подключён" : "не подключён") + "; с запуска озвучено " + played + ", отброшено " + dropped;
	}

	public void stopping() {
		stopping = true;
		pool.shutdownNow();
	}
}
