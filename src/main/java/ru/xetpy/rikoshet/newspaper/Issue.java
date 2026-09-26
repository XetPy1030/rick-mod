package ru.xetpy.rikoshet.newspaper;

import java.util.List;

/**
 * Выпуск «Межпространственного вестника» за день.
 *
 * @param source ai — написала модель, fallback — собран из фактов без ИИ
 */
public record Issue(String day, String headline, List<Article> articles, String ad, String weather, String forecast,
		String source, String model, double costUsd, long publishedAt) {
	public record Article(String title, String body) {
	}

	public Issue withPublished(long ts) {
		return new Issue(day, headline, articles, ad, weather, forecast, source, model, costUsd, ts);
	}
}
