package ru.xetpy.rikoshet.persona;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Вывод реплик персонажей в чат. Оформление отличается от чата игроков: «[Рик]» цветом
 * персонажа, *ремарки* курсивом серым. Получают только авторизованные игроки без /rick off.
 * Последние реплики хранятся для /rick report.
 */
public final class Speaker {
	private static final int RECENT = 30;

	/** about — о ком реплика: окно «ответа Рику» в чате считается от неё. */
	public record Line(long ts, Persona persona, String text, Set<UUID> recipients, Set<UUID> about) {
	}

	private final Logger log;
	private final Deque<Line> recent = new ArrayDeque<>();

	public Speaker(Logger log) {
		this.log = log;
	}

	/** Только из главного потока. */
	public void say(MinecraftServer server, Persona persona, String text, Predicate<ServerPlayer> canSee) {
		say(server, persona, text, canSee, Set.of());
	}

	/** Только из главного потока. about — о ком реплика (смерть, вход, ответ в чате). */
	public void say(MinecraftServer server, Persona persona, String text, Predicate<ServerPlayer> canSee, Set<UUID> about) {
		Component msg = format(persona, text);
		Set<UUID> got = new HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (canSee.test(p)) {
				p.sendSystemMessage(msg);
				got.add(p.getUUID());
			}
		}
		log.info("[{}] {}", persona.displayName(), text);
		synchronized (recent) {
			recent.addLast(new Line(System.currentTimeMillis(), persona, text, Set.copyOf(got), Set.copyOf(about)));
			while (recent.size() > RECENT) {
				recent.removeFirst();
			}
		}
	}

	/** Последние n реплик, которые видел игрок, старые первыми. */
	public List<String> recentSeenBy(UUID player, int n) {
		List<String> out = new ArrayList<>();
		synchronized (recent) {
			var it = recent.descendingIterator();
			while (it.hasNext() && out.size() < n) {
				Line l = it.next();
				if (l.recipients().contains(player)) {
					out.addFirst(l.text());
				}
			}
		}
		return out;
	}

	/** Когда персонаж последний раз говорил об игроке; 0 — не говорил. */
	public long lastAbout(UUID player) {
		synchronized (recent) {
			var it = recent.descendingIterator();
			while (it.hasNext()) {
				Line l = it.next();
				if (l.about().contains(player)) {
					return l.ts();
				}
			}
		}
		return 0;
	}

	/** Реплики не старше since, старые первыми. */
	public List<Line> since(long since) {
		List<Line> out = new ArrayList<>();
		synchronized (recent) {
			for (Line l : recent) {
				if (l.ts() >= since) {
					out.add(l);
				}
			}
		}
		return out;
	}

	/** Последняя реплика, которую видел игрок. */
	public Line lastSeenBy(UUID player) {
		synchronized (recent) {
			var it = recent.descendingIterator();
			while (it.hasNext()) {
				Line l = it.next();
				if (l.recipients().contains(player)) {
					return l;
				}
			}
		}
		return null;
	}

	public static Component format(Persona persona, String text) {
		MutableComponent out = Component.literal("[").withStyle(ChatFormatting.DARK_GRAY);
		out.append(Component.literal(persona.displayName()).withStyle(Style.EMPTY.withColor(persona.color()).withBold(true)));
		out.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
		for (Segment s : segments(text)) {
			out.append(s.aside()
					? Component.literal(s.text()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
					: Component.literal(s.text()).withStyle(ChatFormatting.WHITE));
		}
		return out;
	}

	record Segment(String text, boolean aside) {
	}

	/** Делит текст на обычные куски и *ремарки*. Непарная звёздочка остаётся как есть. */
	static List<Segment> segments(String text) {
		List<Segment> out = new ArrayList<>();
		int i = 0;
		while (i < text.length()) {
			int a = text.indexOf('*', i);
			int b = a < 0 ? -1 : text.indexOf('*', a + 1);
			if (a < 0 || b < 0 || b == a + 1) {
				out.add(new Segment(text.substring(i), false));
				break;
			}
			if (a > i) {
				out.add(new Segment(text.substring(i, a), false));
			}
			out.add(new Segment(text.substring(a, b + 1), true));
			i = b + 1;
		}
		return out;
	}
}
