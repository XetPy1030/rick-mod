package ru.xetpy.rikoshet.core;

/**
 * Уровень нагрузки по MSPT: среднее ванильного getAverageTickTimeNanos за 10 секунд.
 * Вверх уровень меняется сразу, вниз — после recover_seconds ниже порога.
 * Никаких своих замеров: только чтение числа, которое сервер и так считает.
 */
public final class LoadMonitor {
	public enum Level { NORMAL, SOFT, HARD }

	private static final int WINDOW = 10;

	private final double[] ring = new double[WINDOW];
	private int count;
	private double soft;
	private double hard;
	private long recoverMillis;
	private volatile Level level = Level.NORMAL;
	private volatile double mspt;
	private long belowSince = -1;

	public LoadMonitor(RikoshetConfig.Performance cfg) {
		configure(cfg);
	}

	public synchronized void configure(RikoshetConfig.Performance cfg) {
		soft = cfg.msptSoft();
		hard = cfg.msptHard();
		recoverMillis = cfg.recoverSeconds() * 1000L;
	}

	/**
	 * Раз в секунду из главного потока. Возвращает новый уровень, если он сменился, иначе null.
	 */
	public synchronized Level sample(double msptNow, long nowMillis) {
		ring[count % WINDOW] = msptNow;
		count++;
		int n = Math.min(count, WINDOW);
		double sum = 0;
		for (int i = 0; i < n; i++) {
			sum += ring[i];
		}
		mspt = sum / n;
		Level target = mspt > hard ? Level.HARD : mspt > soft ? Level.SOFT : Level.NORMAL;
		Level old = level;
		if (target.ordinal() > old.ordinal()) {
			level = target;
			belowSince = -1;
		} else if (target.ordinal() < old.ordinal()) {
			if (belowSince < 0) {
				belowSince = nowMillis;
			}
			if (nowMillis - belowSince >= recoverMillis) {
				level = target;
				belowSince = -1;
			}
		} else {
			belowSince = -1;
		}
		return level != old ? level : null;
	}

	public Level level() {
		return level;
	}

	public double mspt() {
		return mspt;
	}

	public boolean hard() {
		return level == Level.HARD;
	}
}
