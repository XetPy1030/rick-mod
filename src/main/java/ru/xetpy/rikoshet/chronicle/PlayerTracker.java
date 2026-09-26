package ru.xetpy.rikoshet.chronicle;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Живое состояние отслеживаемого игрока между входом и выходом. Только главный поток. */
final class PlayerTracker {
	final UUID uuid;
	final String name;
	final long sessionStart;

	/** Значения ванильной статистики на момент прошлого снимка. */
	final Object2IntOpenHashMap<Stat<?>> baseline = new Object2IntOpenHashMap<>();
	/** Счётчики за всё время на прошлом снимке — для вех. */
	Map<String, Long> lifetime;
	/** Выполненные достижения (с отображением), чтобы не проверять их снова. */
	final Set<Identifier> advancements = new HashSet<>();

	// ---------- окно между снимками ----------
	long windowStart;
	int samples;
	int idle;
	int underground;
	int newCells;
	/** Счётчики из замеров (время по измерениям и биомам, AFK, чат…) до ближайшего снимка. */
	final Map<String, Long> pending = new HashMap<>();
	/** Секунды в клетках до сброса в player_cell. */
	final Long2LongOpenHashMap cellSeconds = new Long2LongOpenHashMap();

	// ---------- прошлый замер ----------
	long lastSample;
	boolean hasLast;
	double lx;
	double ly;
	double lz;
	float lyaw;
	float lpitch;
	int idleStreak;
	long idleUncounted;
	int sampleNo;

	// ---------- сессия ----------
	final Map<String, Long> session = new HashMap<>();
	int sessionDeaths;

	// ---------- смерть ----------
	/** Что было при себе в момент смертельного удара (ALLOW_DEATH), до выпадения лута. */
	DeathLoss pendingLoss;
	long pendingLossTick = -1;
	/** Секунд с прошлой смерти — ванилла обнуляет счётчик раньше, чем мы узнаём о смерти. */
	long secondsSinceDeath = -1;

	PlayerTracker(UUID uuid, String name, long now) {
		this.uuid = uuid;
		this.name = name;
		this.sessionStart = now;
		this.windowStart = now;
		this.lastSample = now;
	}

	void resetWindow(long now) {
		windowStart = now;
		samples = 0;
		idle = 0;
		underground = 0;
		newCells = 0;
	}

	/** Ценное при себе: для заметки «погиб с 23 алмазами». items — короткие id выпавших ценных вещей. */
	record DeathLoss(int diamonds, int netherite, int enchanted, int elytra, int totems, int shulkers, int value, Set<String> items) {
		boolean big() {
			return value >= 20;
		}
	}
}
