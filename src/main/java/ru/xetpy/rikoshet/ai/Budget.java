package ru.xetpy.rikoshet.ai;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Дневной бюджет в долларах по {@code usage.cost}. День — по часовому поясу из конфига.
 * Параллельные запросы могут перебрать лимит на стоимость нескольких ответов — это центы.
 */
public final class Budget {
	private final Clock clock;
	private ZoneId zone;
	private double limitUsd;
	private LocalDate day;
	private double spent;

	public Budget(Clock clock, ZoneId zone, double limitUsd) {
		this.clock = clock;
		this.zone = zone;
		this.limitUsd = limitUsd;
		this.day = today();
	}

	public synchronized void configure(ZoneId zone, double limitUsd) {
		this.zone = zone;
		this.limitUsd = limitUsd;
		roll();
	}

	/** Сумма за сегодня из ai_log при старте. */
	public synchronized void restore(LocalDate day, double spent) {
		roll();
		if (day.equals(this.day)) {
			this.spent = spent;
		}
	}

	public synchronized boolean canSpend() {
		roll();
		return spent < limitUsd;
	}

	public synchronized void add(double costUsd) {
		roll();
		spent += costUsd;
	}

	public synchronized double spentToday() {
		roll();
		return spent;
	}

	public synchronized double limit() {
		return limitUsd;
	}

	public synchronized LocalDate day() {
		roll();
		return day;
	}

	public synchronized ZoneId zone() {
		return zone;
	}

	private void roll() {
		LocalDate now = today();
		if (!now.equals(day)) {
			day = now;
			spent = 0;
		}
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(zone));
	}
}
