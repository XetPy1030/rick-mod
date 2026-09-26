package ru.xetpy.rikoshet.flavor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.core.RikoshetConfig;
import ru.xetpy.rikoshet.newspaper.Issue;
import ru.xetpy.rikoshet.newspaper.NewspaperService;
import ru.xetpy.rikoshet.pools.PoolService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * MOTD и таблист (docs/design/flavor.md#motd-и-таблист). Первая строка MOTD — за сборкой, вторая —
 * заголовок свежей газеты или новость другой вселенной. Шапка таблиста — бегущая новость,
 * подвал — онлайн и газета. Флаг motd_tab; выключили — MOTD возвращается к исходному.
 */
public final class ServerListService {
	static final long MOTD_MINUTES = 5;
	static final long TAB_SECONDS = 30;
	static final int MOTD_MAX = 45;
	/** Столько часов заголовок газеты — «свежий». */
	static final long FRESH_HOURS = 20;

	private final Logger log;
	private final Clock clock;
	private final Supplier<RikoshetConfig> config;
	private final NewspaperService newspaper;
	private final PoolService pools;
	private final Predicate<ServerPlayer> canSee;
	private final Path dataDir;
	private final Random rnd = new Random();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "rikoshet-serverlist");
		t.setDaemon(true);
		return t;
	});
	private volatile Map<String, List<String>> fallback = Map.of();
	private volatile MinecraftServer server;
	private volatile String original;
	private volatile boolean changed;

	public ServerListService(Logger log, Clock clock, Supplier<RikoshetConfig> config, NewspaperService newspaper, PoolService pools,
			Predicate<ServerPlayer> canSee, Path dataDir) {
		this.log = log;
		this.clock = clock;
		this.config = config;
		this.newspaper = newspaper;
		this.pools = pools;
		this.canSee = canSee;
		this.dataDir = dataDir;
		reload();
	}

	public void reload() {
		Map<String, List<String>> m = new HashMap<>();
		try (InputStream in = ServerListService.class.getResourceAsStream("/rikoshet/fallback/news.json")) {
			if (in != null) {
				read(m, new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			Path over = dataDir.resolve("fallback").resolve("news.json");
			if (Files.exists(over)) {
				read(m, Files.readString(over));
			}
		} catch (Exception e) {
			log.warn("[MOTD] заготовки новостей не прочитаны: {}", e.toString());
		}
		fallback = m;
	}

	private static void read(Map<String, List<String>> m, String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		for (String k : List.of("motd", "tab")) {
			if (o.has(k) && o.get(k).isJsonArray()) {
				List<String> l = new ArrayList<>();
				o.getAsJsonArray(k).forEach(e -> l.add(e.getAsString()));
				m.put(k, l);
			}
		}
	}

	public void start(MinecraftServer server) {
		this.server = server;
		this.original = server.getMotd();
		timer.scheduleAtFixedRate(() -> server.execute(this::motd), 5, MOTD_MINUTES * 60, TimeUnit.SECONDS);
		timer.scheduleAtFixedRate(() -> server.execute(this::tab), 10, TAB_SECONDS, TimeUnit.SECONDS);
	}

	public void stop() {
		timer.shutdownNow();
		MinecraftServer s = server;
		if (s != null && changed && original != null) {
			s.setMotd(original);
		}
	}

	private boolean enabled() {
		return config.get().feature("motd_tab");
	}

	/** Вторая строка MOTD. Главный поток. */
	void motd() {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}
		if (!enabled()) {
			if (changed) {
				s.setMotd(original);
				s.invalidateStatus();
				changed = false;
			}
			return;
		}
		String line = null;
		Issue i = fresh();
		if (i != null && rnd.nextDouble() < 0.4) {
			line = clip("Вестник: " + i.headline(), MOTD_MAX);
		}
		if (line == null) {
			line = pools.news("motd");
		}
		if (line == null) {
			line = pick("motd");
		}
		if (line == null) {
			return;
		}
		String first = original == null ? "" : original.split("\n", 2)[0];
		s.setMotd(first + "\n§7" + line);
		s.invalidateStatus();
		changed = true;
	}

	/** Шапка и подвал таблиста. Главный поток. */
	void tab() {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}
		List<ServerPlayer> players = s.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			return;
		}
		if (!enabled()) {
			return;
		}
		String news = pools.news("tab");
		if (news == null) {
			news = pick("tab");
		}
		Component header = Component.literal("Межпространственный вестник").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal(news == null ? "" : "\n" + news).withStyle(ChatFormatting.GRAY).withStyle(s2 -> s2.withBold(false)));
		long online = players.stream().filter(canSee).count();
		Issue i = fresh();
		Component footer = Component.literal("Онлайн: " + online + (i != null ? " · свежий номер — /rick news" : ""))
				.withStyle(ChatFormatting.DARK_GRAY);
		ClientboundTabListPacket ours = new ClientboundTabListPacket(header, footer);
		ClientboundTabListPacket empty = new ClientboundTabListPacket(Component.empty(), Component.empty());
		for (ServerPlayer p : players) {
			p.connection.send(canSee.test(p) ? ours : empty);
		}
	}

	private Issue fresh() {
		Issue i = newspaper.current();
		if (i == null || !newspaper.enabled() || clock.millis() - i.publishedAt() > TimeUnit.HOURS.toMillis(FRESH_HOURS)) {
			return null;
		}
		return i;
	}

	private String pick(String kind) {
		List<String> l = fallback.getOrDefault(kind, List.of());
		return l.isEmpty() ? null : l.get(rnd.nextInt(l.size()));
	}

	static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
