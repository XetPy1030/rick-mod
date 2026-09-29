package ru.xetpy.rikoshet.voice;

import net.minecraft.world.entity.Entity;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Мост к Simple Voice Chat без его классов: выход ставит плагин, когда сервер голосового чата
 * запущен. Нет мода или он выключен — выхода нет, озвучка молча не работает, текст остаётся.
 */
public final class VoiceOut {
	/** Проиграть звук от сущности. pcm — 48 кГц, моно, 16 бит. hear — кто из игроков слышит. */
	public interface Output {
		void play(Entity source, short[] pcm, float distance, Predicate<UUID> hear);
	}

	private static volatile Output output;

	private VoiceOut() {
	}

	static void set(Output o) {
		output = o;
	}

	public static boolean available() {
		return output != null;
	}

	/** Только главный поток: сущность читается здесь. */
	public static boolean play(Entity source, short[] pcm, float distance, Predicate<UUID> hear) {
		Output o = output;
		if (o == null) {
			return false;
		}
		o.play(source, pcm, distance, hear);
		return true;
	}
}
