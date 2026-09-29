package ru.xetpy.rikoshet.npc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.citadel.Citadel;

import java.util.Comparator;
import java.util.List;

/**
 * NPC мода — манекены со скином из ресурспака (docs/architecture/server-integration.md#скины).
 * Рик стоит в лаборатории на точке rick: пропал или сдвинулся — ставим заново. Голову поворачивает
 * к ближайшему игроку. Всё — главный поток, и только пока в Цитадели кто-то есть.
 */
public final class Npcs {
	public static final String TAG = "rikoshet.npc";
	public static final String RICK_TAG = "rikoshet.npc.rick";
	/** Скин — текстура из нашего ресурспака: assets/rikoshet/textures/entity/npc/rick.png. */
	static final String RICK_TEXTURE = "rikoshet:entity/npc/rick";
	static final double LOOK_DISTANCE = 8;
	/** Голова поворачивается не дальше этого от корпуса, иначе поворачивается и корпус. */
	static final float HEAD_LIMIT = 60;

	private final Logger log;
	private final Citadel citadel;
	/** Рик, найденный в последний раз, пока в Цитадели есть игроки; иначе null. */
	private Mannequin current;

	public Npcs(Logger log, Citadel citadel) {
		this.log = log;
		this.citadel = citadel;
	}

	public static boolean isRick(Entity e) {
		return e instanceof Mannequin && e.entityTags().contains(RICK_TAG);
	}

	public static boolean isNpc(Entity e) {
		return e.entityTags().contains(TAG);
	}

	/** Раз в секунду: Рик на месте и смотрит на того, кто рядом. */
	public void everySecond(MinecraftServer server) {
		if (!citadel.ready()) {
			return;
		}
		ServerLevel level = citadel.level(server);
		current = null;
		if (level == null || level.players().isEmpty()) {
			return;
		}
		Citadel.Marker m = citadel.marker(Citadel.RICK);
		if (!level.isLoaded(m.pos())) {
			return;
		}
		Mannequin rick = ensureRick(level, m);
		if (rick != null) {
			look(level, rick, m);
			current = rick;
		}
	}

	/** Рик сейчас: найденный в этой секунде или найденный заново, без спавна. null — чанк не загружен или Рика нет. */
	public Mannequin current(MinecraftServer server) {
		if (current != null && !current.isRemoved()) {
			return current;
		}
		ServerLevel level = citadel.ready() ? citadel.level(server) : null;
		Citadel.Marker m = level == null ? null : citadel.marker(Citadel.RICK);
		return m == null || !level.isLoaded(m.pos()) ? null : rick(level, m);
	}

	/** Рик стоит на точке — найденный или только что поставленный; null, если не вышло. */
	public Mannequin ensureRick(ServerLevel level, Citadel.Marker m) {
		Mannequin rick = rick(level, m);
		return rick != null ? rick : spawnRick(level, m);
	}

	/** Рик в лаборатории: единственный, на своём месте. Лишних убираем. */
	public Mannequin rick(ServerLevel level, Citadel.Marker m) {
		List<Mannequin> found = level.getEntitiesOfClass(Mannequin.class, new AABB(m.pos()).inflate(24), Npcs::isRick);
		if (found.isEmpty()) {
			return null;
		}
		Vec3 home = m.feet();
		found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(home)));
		for (int i = 1; i < found.size(); i++) {
			found.get(i).discard();
		}
		Mannequin rick = found.getFirst();
		if (rick.distanceToSqr(home) > 1) {
			rick.snapTo(home.x, home.y, home.z, m.yaw(), 0);
		}
		return rick;
	}

	private Mannequin spawnRick(ServerLevel level, Citadel.Marker m) {
		Vec3 at = m.feet();
		CompoundTag tag = new CompoundTag();
		tag.putString("id", "minecraft:mannequin");
		ListTag pos = new ListTag();
		pos.add(DoubleTag.valueOf(at.x));
		pos.add(DoubleTag.valueOf(at.y));
		pos.add(DoubleTag.valueOf(at.z));
		tag.put("Pos", pos);
		ListTag rot = new ListTag();
		rot.add(FloatTag.valueOf(m.yaw()));
		rot.add(FloatTag.valueOf(0));
		tag.put("Rotation", rot);
		CompoundTag profile = new CompoundTag();
		profile.putString("texture", RICK_TEXTURE);
		profile.putString("model", "wide");
		tag.put("profile", profile);
		tag.putBoolean("hide_description", true);
		tag.putBoolean("immovable", true);
		tag.putBoolean("Invulnerable", true);
		tag.putBoolean("PersistenceRequired", true);
		tag.putString("CustomName", "Рик");
		tag.putBoolean("CustomNameVisible", true);
		ListTag tags = new ListTag();
		tags.add(StringTag.valueOf(TAG));
		tags.add(StringTag.valueOf(RICK_TAG));
		tag.put("Tags", tags);
		// Фляжка в руке
		CompoundTag flask = new CompoundTag();
		flask.putString("id", "minecraft:honey_bottle");
		flask.putInt("count", 1);
		CompoundTag equipment = new CompoundTag();
		equipment.put("mainhand", flask);
		tag.put("equipment", equipment);

		Entity e = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
				level, EntitySpawnReason.COMMAND, x -> x);
		if (!(e instanceof Mannequin rick) || !level.addFreshEntity(rick)) {
			log.warn("[NPC] Рик не появился в {}", m.pos().toShortString());
			return null;
		}
		log.info("[NPC] Рик поставлен в {}", m.pos().toShortString());
		return rick;
	}

	/** Голова — на ближайшего игрока в 8 блоках; нет никого — прямо, как стоит. */
	private static void look(ServerLevel level, Mannequin npc, Citadel.Marker m) {
		ServerPlayer target = null;
		double best = LOOK_DISTANCE * LOOK_DISTANCE;
		for (ServerPlayer p : level.players()) {
			double d = p.distanceToSqr(npc);
			if (d < best && !p.isSpectator()) {
				best = d;
				target = p;
			}
		}
		float body = m.yaw();
		float head = body;
		float pitch = 0;
		if (target != null) {
			Vec3 eye = npc.getEyePosition();
			Vec3 to = target.getEyePosition().subtract(eye);
			head = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90;
			pitch = (float) -(Mth.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * Mth.RAD_TO_DEG);
			float diff = Mth.wrapDegrees(head - body);
			if (Math.abs(diff) > HEAD_LIMIT) {
				body = head - Math.signum(diff) * HEAD_LIMIT;
			}
		}
		npc.setYRot(body);
		npc.setYBodyRot(body);
		npc.setYHeadRot(head);
		npc.setXRot(pitch);
	}
}
