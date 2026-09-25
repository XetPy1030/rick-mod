package ru.xetpy.rikoshet.ai;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Не больше N запросов за скользящую минуту. */
public final class RateLimiter {
	private static final long WINDOW_NANOS = 60_000_000_000L;

	private final LongSupplier nanos;
	private final ArrayDeque<Long> stamps = new ArrayDeque<>();
	private int perMinute;

	public RateLimiter(int perMinute, LongSupplier nanos) {
		this.perMinute = perMinute;
		this.nanos = nanos;
	}

	public synchronized void configure(int perMinute) {
		this.perMinute = perMinute;
	}

	public synchronized boolean tryAcquire() {
		long now = nanos.getAsLong();
		evict(now);
		if (stamps.size() >= perMinute) {
			return false;
		}
		stamps.addLast(now);
		return true;
	}

	public synchronized int lastMinute() {
		evict(nanos.getAsLong());
		return stamps.size();
	}

	public synchronized int perMinute() {
		return perMinute;
	}

	private void evict(long now) {
		while (!stamps.isEmpty() && now - stamps.peekFirst() >= WINDOW_NANOS) {
			stamps.removeFirst();
		}
	}
}
