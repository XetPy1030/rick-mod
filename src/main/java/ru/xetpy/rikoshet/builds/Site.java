package ru.xetpy.rikoshet.builds;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Постройка: клетка 64×64, где строили. Живёт в памяти, пишется в build_site. Главный поток. */
public final class Site {
	final String dim;
	final int cx;
	final int cz;
	UUID owner;
	long placed;
	long firstTs;
	long buildTs;
	long scanTs;
	long artificial;
	Long baseline;
	JsonObject scan;
	/** День (ГГГГ-ММ-ДД) → рукотворных на последнем скане дня. */
	final TreeMap<String, Long> history = new TreeMap<>();
	String name;
	String description;
	Long namedAt;
	/** Кто сколько поставил здесь. */
	final Map<UUID, Long> builders = new HashMap<>();

	Site(String dim, int cx, int cz, long now) {
		this.dim = dim;
		this.cx = cx;
		this.cz = cz;
		this.firstTs = now;
		this.buildTs = now;
	}

	public String key() {
		return key(dim, cx, cz);
	}

	static String key(String dim, int cx, int cz) {
		return dim + "|" + cx + "|" + cz;
	}

	/** Хозяин — кто поставил больше всех. */
	void recomputeOwner() {
		owner = builders.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	public String dim() {
		return dim;
	}

	public int cx() {
		return cx;
	}

	public int cz() {
		return cz;
	}

	public UUID owner() {
		return owner;
	}

	public long artificial() {
		return artificial;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	public JsonObject scan() {
		return scan;
	}
}
