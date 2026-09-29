package ru.xetpy.rikoshet.citadel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.storage.KvStore;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Хаб-измерение Цитадель (docs/architecture/worlds.md#цитадель): лаборатория из NBT ставится один раз,
 * точки для кода — структурные блоки DATA внутри неё. В Цитадели выживание становится приключением,
 * урона нет, из пустоты возвращает на точку прибытия. Всё — главный поток.
 */
public final class Citadel {
	public static final ResourceKey<Level> LEVEL = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("rikoshet", "citadel"));
	static final Identifier LAB = Identifier.fromNamespaceAndPath("rikoshet", "citadel/lab");
	/** Нижний угол лаборатории: площадка встаёт вокруг (0, 65, 0). */
	static final BlockPos ORIGIN = new BlockPos(-13, 64, -15);
	/** Ниже этой высоты игрок падает в пустоту — возвращаем на точку прибытия. */
	static final int VOID_Y = ORIGIN.getY() - 12;
	/** Метка «режим приключения поставила Цитадель»: снимаем только его, чужой не трогаем. */
	static final String ADVENTURE_TAG = "rikoshet.citadel_adventure";
	static final String KV_PLACED = "citadel.placed";
	static final String KV_MARK = "citadel.mark.";

	public static final String RICK = "rick";
	public static final String SPAWN = "spawn";
	public static final String PORTAL_BACK = "portal_back";
	static final List<String> REQUIRED = List.of(RICK, SPAWN, PORTAL_BACK);

	/** Точка в Цитадели: блок, на котором стоят ноги, и куда смотреть. */
	public record Marker(BlockPos pos, float yaw) {
		public Vec3 feet() {
			return Vec3.atBottomCenterOf(pos);
		}
	}

	private final Logger log;
	private final Clock clock;
	private final KvStore kv;
	private final Map<String, Marker> markers = new LinkedHashMap<>();
	private boolean ready;
	private boolean warned;

	public Citadel(Logger log, Clock clock, KvStore kv) {
		this.log = log;
		this.clock = clock;
		this.kv = kv;
	}

	public static boolean in(ServerPlayer p) {
		return p.level().dimension() == LEVEL;
	}

	public ServerLevel level(MinecraftServer server) {
		return server.getLevel(LEVEL);
	}

	public boolean ready() {
		return ready;
	}

	public Marker marker(String name) {
		return markers.get(name);
	}

	public Map<String, Marker> markers() {
		return Map.copyOf(markers);
	}

	/**
	 * Лаборатория на месте и точки известны. Первый вызов ставит её в пустое измерение; дальше только
	 * читает точки из структуры и сдвиги из kv. Вызывается при старте и при включении фичи.
	 */
	public boolean ensure(MinecraftServer server) {
		if (ready) {
			return true;
		}
		ServerLevel level = level(server);
		if (level == null) {
			warnOnce("измерения rikoshet:citadel нет — датапак мода выключен?");
			return false;
		}
		Optional<StructureTemplate> template = server.getStructureManager().get(LAB);
		if (template.isEmpty()) {
			warnOnce("нет структуры " + LAB);
			return false;
		}
		StructurePlaceSettings settings = new StructurePlaceSettings();
		List<StructureTemplate.StructureBlockInfo> marks = template.get().filterBlocks(ORIGIN, settings, Blocks.STRUCTURE_BLOCK);
		readMarkers(marks);
		if (kv.get(KV_PLACED) == null) {
			place(level, template.get(), settings, marks);
			kv.put(KV_PLACED, LAB.toString(), clock.millis());
		}
		List<String> missing = REQUIRED.stream().filter(n -> !markers.containsKey(n)).toList();
		if (!missing.isEmpty()) {
			warnOnce("в лаборатории нет точек " + missing + " — структурные блоки DATA с metadata «имя:сторона»");
			return false;
		}
		ready = true;
		log.info("[Цитадель] готова: точки {}", markers.keySet());
		return true;
	}

	private void readMarkers(List<StructureTemplate.StructureBlockInfo> marks) {
		markers.clear();
		for (StructureTemplate.StructureBlockInfo info : marks) {
			String meta = info.nbt() == null ? "" : info.nbt().getStringOr("metadata", "");
			Marker m = parse(info.pos(), meta);
			if (m != null) {
				markers.put(name(meta), m);
			}
		}
		// Сдвиги из /rickadmin citadel mark — поверх структуры, пока её не пересохранили
		for (String name : REQUIRED) {
			String moved = kv.get(KV_MARK + name);
			if (moved != null) {
				String[] p = moved.split(" ");
				try {
					markers.put(name, new Marker(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])),
							Float.parseFloat(p[3])));
				} catch (RuntimeException e) {
					log.warn("[Цитадель] испорченный сдвиг точки {}: {}", name, moved);
				}
			}
		}
	}

	private void place(ServerLevel level, StructureTemplate template, StructurePlaceSettings settings,
			List<StructureTemplate.StructureBlockInfo> marks) {
		template.placeInWorld(level, ORIGIN, ORIGIN, settings, level.getRandom(), Block.UPDATE_CLIENTS);
		for (StructureTemplate.StructureBlockInfo info : marks) {
			level.setBlock(info.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		log.info("[Цитадель] лаборатория поставлена: {} от {}", template.getSize(), ORIGIN);
	}

	/** Поставить лабораторию заново поверх нынешней: /rickadmin citadel place. */
	public String replace(MinecraftServer server) {
		kv.remove(KV_PLACED);
		ready = false;
		warned = false;
		return ensure(server) ? "лаборатория поставлена заново" : "не вышло — смотри лог";
	}

	/** Сдвинуть точку на место админа: /rickadmin citadel mark. До пересохранения — в kv. */
	public String mark(String name, ServerPlayer p) {
		if (!REQUIRED.contains(name)) {
			return "нет такой точки: " + name + ", есть " + REQUIRED;
		}
		if (!in(p)) {
			return "встань в Цитадели";
		}
		BlockPos pos = p.blockPosition();
		float yaw = snapYaw(p.getYRot());
		kv.put(KV_MARK + name, pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + yaw, clock.millis());
		markers.put(name, new Marker(pos, yaw));
		return name + " → " + pos.toShortString() + ", смотрит " + facing(yaw);
	}

	/**
	 * Сохранить лабораторию, перестроенную в креативе: /rickadmin citadel save. Точки встают структурными
	 * блоками на свои места, область уходит в world/generated/rikoshet/structures/citadel/lab.nbt и дальше
	 * берётся оттуда вместо встроенной.
	 */
	public String save(MinecraftServer server) {
		ServerLevel level = level(server);
		if (!ready || level == null) {
			return "Цитадель не готова";
		}
		StructureTemplate template = server.getStructureManager().getOrCreate(LAB);
		Vec3i size = template.getSize();
		for (Map.Entry<String, Marker> e : markers.entrySet()) {
			BlockPos pos = e.getValue().pos();
			level.setBlock(pos, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.DATA), Block.UPDATE_CLIENTS);
			if (level.getBlockEntity(pos) instanceof StructureBlockEntity sbe) {
				sbe.setMode(StructureMode.DATA);
				sbe.setMetaData(e.getKey() + ":" + facing(e.getValue().yaw()));
			}
		}
		template.fillFromWorld(level, ORIGIN, size, false, List.of(Blocks.STRUCTURE_VOID));
		boolean ok = server.getStructureManager().save(LAB);
		for (Marker m : markers.values()) {
			level.setBlock(m.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		if (ok) {
			REQUIRED.forEach(n -> kv.remove(KV_MARK + n));
		}
		log.info("[Цитадель] лаборатория сохранена: {}", ok);
		return ok ? "сохранено " + size.toShortString() + " от " + ORIGIN.toShortString() : "не сохранилось — смотри лог";
	}

	// ---------- игроки в Цитадели ----------

	/** Раз в секунду: режим игры по месту, возврат из пустоты. */
	public void everySecond(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			syncMode(p);
			if (ready && in(p) && p.getY() < VOID_Y) {
				Marker spawn = markers.get(SPAWN);
				teleport(p, (ServerLevel) p.level(), spawn.feet(), spawn.yaw(), 0);
			}
		}
	}

	/**
	 * В Цитадели выживание — приключение, вне её — снова выживание. Снимаем только то приключение,
	 * которое поставили сами (метка на игроке), и не трогаем креатив и наблюдателя.
	 */
	public void syncMode(ServerPlayer p) {
		boolean inside = in(p);
		boolean ours = p.entityTags().contains(ADVENTURE_TAG);
		if (inside && p.gameMode() == GameType.SURVIVAL) {
			p.setGameMode(GameType.ADVENTURE);
			p.addTag(ADVENTURE_TAG);
		} else if (!inside && ours) {
			p.removeTag(ADVENTURE_TAG);
			if (p.gameMode() == GameType.ADVENTURE) {
				p.setGameMode(GameType.SURVIVAL);
			}
		}
	}

	/** В Цитадели игроков не бьёт ничто: ни мобы, ни другие игроки, ни падение. */
	public static boolean allowDamage(LivingEntity e) {
		return !(e instanceof ServerPlayer p && in(p));
	}

	static void teleport(ServerPlayer p, ServerLevel level, Vec3 pos, float yaw, float pitch) {
		p.stopRiding();
		p.resetFallDistance();
		p.teleport(new TeleportTransition(level, pos, Vec3.ZERO, yaw, pitch, TeleportTransition.DO_NOTHING));
	}

	// ---------- мелочи ----------

	/** «rick:south» → точка; сторона света по умолчанию — юг. */
	static Marker parse(BlockPos pos, String meta) {
		if (meta == null || meta.isBlank()) {
			return null;
		}
		int colon = meta.indexOf(':');
		String dir = colon < 0 ? "south" : meta.substring(colon + 1).strip().toLowerCase(Locale.ROOT);
		float yaw = switch (dir) {
			case "north" -> 180;
			case "west" -> 90;
			case "east" -> -90;
			default -> 0;
		};
		return new Marker(pos, yaw);
	}

	static String name(String meta) {
		int colon = meta.indexOf(':');
		return (colon < 0 ? meta : meta.substring(0, colon)).strip().toLowerCase(Locale.ROOT);
	}

	static float snapYaw(float yaw) {
		return Math.round(yaw / 90f) * 90f;
	}

	static String facing(float yaw) {
		int q = Math.floorMod(Math.round(yaw / 90f), 4);
		return switch (q) {
			case 0 -> "south";
			case 1 -> "west";
			case 2 -> "north";
			default -> "east";
		};
	}

	private void warnOnce(String msg) {
		if (!warned) {
			warned = true;
			log.error("[Цитадель] {}", msg);
		}
	}
}
