package ru.xetpy.rikoshet.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoadMonitorTest {
	@Test
	void upImmediatelyDownAfterRecover() {
		LoadMonitor m = new LoadMonitor(new RikoshetConfig.Performance(35, 45, 60));
		long t = 0;
		for (int i = 0; i < 10; i++) {
			m.sample(10, t += 1000);
		}
		assertEquals(LoadMonitor.Level.NORMAL, m.level());
		LoadMonitor.Level changed = null;
		for (int i = 0; i < 10 && changed == null; i++) {
			changed = m.sample(200, t += 1000);
		}
		assertEquals(LoadMonitor.Level.HARD, changed);
		// Среднее падает ниже порогов, но уровень держится recover_seconds
		for (int i = 0; i < 30; i++) {
			assertNull(m.sample(5, t += 1000));
		}
		assertEquals(LoadMonitor.Level.HARD, m.level());
		LoadMonitor.Level back = null;
		for (int i = 0; i < 60 && back == null; i++) {
			back = m.sample(5, t += 1000);
		}
		assertEquals(LoadMonitor.Level.NORMAL, back);
	}
}
