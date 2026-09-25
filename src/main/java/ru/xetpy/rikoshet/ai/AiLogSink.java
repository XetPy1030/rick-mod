package ru.xetpy.rikoshet.ai;

/** Куда пишутся попытки запросов. В игре — таблица ai_log, в тестах — список. */
@FunctionalInterface
public interface AiLogSink {
	/** Вызывается из потока ИИ; реализация не должна блокировать надолго. */
	void write(AiLogEntry entry);
}
