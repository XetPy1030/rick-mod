package ru.xetpy.rikoshet.ai;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Счётчики с запуска сервера для /rickadmin ai status. */
public final class AiStats {
	private static final int RING = 100;

	private final Map<String, Route> routes = new LinkedHashMap<>();

	public static final class Route {
		private final EnumMap<AiStatus, Integer> byStatus = new EnumMap<>(AiStatus.class);
		private final long[] latencies = new long[RING];
		private int latencyCount;
		private int attempts;
		private double cost;
		private int inputTokens;
		private int cachedTokens;
		private String lastError;

		public synchronized Map<AiStatus, Integer> byStatus() {
			return new EnumMap<>(byStatus);
		}

		public synchronized int total() {
			return byStatus.values().stream().mapToInt(Integer::intValue).sum();
		}

		public synchronized int attempts() {
			return attempts;
		}

		public synchronized double cost() {
			return cost;
		}

		/** Доля входных токенов из кеша провайдера. */
		public synchronized double cacheShare() {
			return inputTokens == 0 ? 0 : (double) cachedTokens / inputTokens;
		}

		public synchronized String lastError() {
			return lastError;
		}

		/** Перцентиль задержки успешных запросов, мс; -1 — данных нет. */
		public synchronized long latency(double p) {
			int n = Math.min(latencyCount, RING);
			if (n == 0) {
				return -1;
			}
			long[] copy = Arrays.copyOf(latencies, n);
			Arrays.sort(copy);
			return copy[Math.min(n - 1, (int) Math.floor(p * n))];
		}
	}

	public synchronized Route route(String name) {
		return routes.computeIfAbsent(name, n -> new Route());
	}

	public synchronized Map<String, Route> all() {
		return new LinkedHashMap<>(routes);
	}

	void attempt(String route, OpenRouterClient.Usage usage) {
		Route r = route(route);
		synchronized (r) {
			r.attempts++;
			r.cost += usage.cost();
			r.inputTokens += usage.promptTokens();
			r.cachedTokens += usage.cachedTokens();
		}
	}

	void result(String route, AiResult res) {
		Route r = route(route);
		synchronized (r) {
			r.byStatus.merge(res.status(), 1, Integer::sum);
			if (res.ok()) {
				r.latencies[r.latencyCount % RING] = res.latencyMs();
				r.latencyCount++;
			} else if (res.error() != null) {
				r.lastError = res.status() + ": " + res.error();
			}
		}
	}
}
