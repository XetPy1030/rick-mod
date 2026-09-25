package ru.xetpy.rikoshet.ai;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetTest {
	static final class MutableClock extends Clock {
		Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			MutableClock self = this;
			return new Clock() {
				@Override
				public ZoneId getZone() {
					return zone;
				}

				@Override
				public Clock withZone(ZoneId z) {
					return self.withZone(z);
				}

				@Override
				public Instant instant() {
					return self.now;
				}
			};
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@Test
	void dayRollsAtMoscowMidnight() {
		// 20:59 UTC = 23:59 МСК
		MutableClock clock = new MutableClock(Instant.parse("2026-09-26T20:59:00Z"));
		Budget b = new Budget(clock, ZoneId.of("Europe/Moscow"), 1.0);
		assertEquals(LocalDate.parse("2026-09-26"), b.day());
		b.add(0.7);
		assertTrue(b.canSpend());
		b.add(0.4);
		assertFalse(b.canSpend());
		clock.now = Instant.parse("2026-09-26T21:00:01Z");
		assertEquals(LocalDate.parse("2026-09-27"), b.day());
		assertTrue(b.canSpend());
		assertEquals(0, b.spentToday(), 1e-9);
	}

	@Test
	void restoreOnlyToday() {
		MutableClock clock = new MutableClock(Instant.parse("2026-09-26T10:00:00Z"));
		Budget b = new Budget(clock, ZoneId.of("Europe/Moscow"), 1.0);
		b.restore(LocalDate.parse("2026-09-25"), 5);
		assertTrue(b.canSpend());
		b.restore(LocalDate.parse("2026-09-26"), 5);
		assertFalse(b.canSpend());
	}

	@Test
	void zeroBudgetMeansNoRequests() {
		Budget b = new Budget(Clock.systemUTC(), ZoneId.of("UTC"), 0);
		assertFalse(b.canSpend());
	}

	@Test
	void rateLimiterSlidingMinute() {
		AtomicLong nanos = new AtomicLong();
		RateLimiter r = new RateLimiter(2, nanos::get);
		assertTrue(r.tryAcquire());
		assertTrue(r.tryAcquire());
		assertFalse(r.tryAcquire());
		nanos.addAndGet(59_000_000_000L);
		assertFalse(r.tryAcquire());
		nanos.addAndGet(1_000_000_000L);
		assertTrue(r.tryAcquire());
		assertEquals(1, r.lastMinute());
	}
}
