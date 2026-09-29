package ru.xetpy.rikoshet.citadel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Портал по вызову (решение № 25): зелёное кольцо частиц рядом с игроком на portal_seconds, шагнул —
 * в Цитадели, откуда пришёл — запомнено. Портал обратно стоит в Цитадели постоянно. Главный поток.
 */
public final class Portals {
	/** Пройдя через портал, игрок столько не может пройти снова: не болтаться туда-сюда. */
	static final long IMMUNE_MS = 3000;
	/** Первый прошёл — портал закрывается через столько: друзья успевают следом. */
	static final long CLOSE_AFTER_USE_MS = 3000;
	/** Не в бою: столько тиков без удара моба. Сбежать через портал из драки нельзя. */
	static final int CALM_TICKS = 200;
	static final double RADIUS_X = 0.9;
	static final double RADIUS_Y = 1.3;
	static final double CENTER_Y = 1.2;
	private static final ParticleOptions RING = new DustParticleOptions(0x39FF14, 1.3f);
	private static final ParticleOptions SWIRL = new DustParticleOptions(0xB6FF4A, 0.8f);

	public enum Call {
		OPENED, COOLDOWN, FIGHT, INSIDE, NOT_READY
	}

	private static final class Open {
		final ResourceKey<Level> dim;
		final Vec3 base;
		final float yaw;
		long until;

		Open(ResourceKey<Level> dim, Vec3 base, float yaw, long until) {
			this.dim = dim;
			this.base = base;
			this.yaw = yaw;
			this.until = until;
		}
	}

	private final Logger log;
	private final Clock clock;
	private final Citadel citadel;
	private final ReturnPoints returns;
	private final List<Open> open = new ArrayList<>();
	private final Map<UUID, Long> lastTravel = new HashMap<>();
	private final Map<UUID, Long> lastCall = new HashMap<>();
	private int ticks;
	private java.util.function.Consumer<ServerPlayer> onArrive = p -> { };

	public Portals(Logger log, Clock clock, Citadel citadel, ReturnPoints returns) {
		this.log = log;
		this.clock = clock;
		this.citadel = citadel;
		this.returns = returns;
	}

	/** Что сделать с игроком, пришедшим в Цитадель: достижение для главы FTB. */
	public void onArrive(java.util.function.Consumer<ServerPlayer> action) {
		this.onArrive = action;
	}

	/** Открыть портал рядом с игроком: в двух блоках впереди, а если там стена — прямо на нём. */
	public Call call(ServerPlayer p, int seconds, int cooldownSeconds) {
		if (!citadel.ready()) {
			return Call.NOT_READY;
		}
		if (Citadel.in(p)) {
			return Call.INSIDE;
		}
		long now = clock.millis();
		Long last = lastCall.get(p.getUUID());
		if (last != null && now - last < cooldownSeconds * 1000L) {
			return Call.COOLDOWN;
		}
		if (!calm(p)) {
			return Call.FIGHT;
		}
		lastCall.put(p.getUUID(), now);
		ServerLevel level = (ServerLevel) p.level();
		float yaw = p.getYRot();
		Vec3 ahead = p.position().add(forward(yaw).scale(2));
		Vec3 base = free(level, ahead) ? ahead : p.position();
		// Портал смотрит на игрока
		open.add(new Open(level.dimension(), base, yaw + 180, now + seconds * 1000L));
		level.playSound(null, base.x, base.y + 1, base.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0f, 1.6f);
		log.info("[Цитадель] портал для {} в {} {}", p.getScoreboardName(), level.dimension().identifier(), BlockPos.containing(base).toShortString());
		return Call.OPENED;
	}

	/** Каждый тик: частицы раз в два тика, проход через портал — каждый тик. */
	public void tick(MinecraftServer server) {
		if (!citadel.ready()) {
			return;
		}
		ServerLevel hub = citadel.level(server);
		boolean anyoneInHub = hub != null && !hub.players().isEmpty();
		if (open.isEmpty() && !anyoneInHub) {
			return;
		}
		long now = clock.millis();
		ticks++;
		open.removeIf(o -> o.until < now);
		boolean draw = ticks % 2 == 0;
		Citadel.Marker back = citadel.marker(Citadel.PORTAL_BACK);
		if (draw) {
			for (Open o : open) {
				ServerLevel level = server.getLevel(o.dim);
				if (level != null) {
					draw(level, o.base, o.yaw);
				}
			}
			if (anyoneInHub) {
				draw(hub, back.feet(), back.yaw());
			}
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.isAlive() || p.isSpectator()) {
				continue;
			}
			Long t = lastTravel.get(p.getUUID());
			if (t != null && now - t < IMMUNE_MS) {
				continue;
			}
			if (Citadel.in(p)) {
				if (inside(p.position(), back.feet())) {
					home(server, p, now);
				}
				continue;
			}
			for (Iterator<Open> it = open.iterator(); it.hasNext(); ) {
				Open o = it.next();
				if (o.dim == p.level().dimension() && inside(p.position(), o.base)) {
					toHub(server, p, now);
					o.until = Math.min(o.until, now + CLOSE_AFTER_USE_MS);
					break;
				}
			}
		}
	}

	/** В Цитадель: запоминаем, откуда ушёл, и ставим на точку прибытия. */
	public void toHub(MinecraftServer server, ServerPlayer p, long now) {
		ServerLevel hub = citadel.level(server);
		if (hub == null || !citadel.ready()) {
			return;
		}
		returns.put(p.getUUID(), new ReturnPoints.Point(p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ(),
				p.getYRot(), p.getXRot()), now);
		Citadel.Marker spawn = citadel.marker(Citadel.SPAWN);
		lastTravel.put(p.getUUID(), now);
		Citadel.teleport(p, hub, spawn.feet(), spawn.yaw(), 0);
		hub.playSound(null, spawn.feet().x, spawn.feet().y + 1, spawn.feet().z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 1.2f);
		onArrive.accept(p);
		log.info("[Цитадель] {} в Цитадели", p.getScoreboardName());
	}

	/** Обратно туда, откуда пришёл; не знаем откуда — на точку возрождения мира. */
	public void home(MinecraftServer server, ServerPlayer p, long now) {
		ReturnPoints.Point r = returns.get(p.getUUID());
		ServerLevel target = r == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(r.dim())));
		lastTravel.put(p.getUUID(), now);
		if (target == null || target.dimension() == Citadel.LEVEL) {
			ServerLevel overworld = server.overworld();
			BlockPos spawn = overworld.getRespawnData().pos();
			Citadel.teleport(p, overworld, Vec3.atBottomCenterOf(spawn), p.getYRot(), 0);
		} else {
			Citadel.teleport(p, target, new Vec3(r.x(), r.y(), r.z()), r.yaw(), r.pitch());
		}
		p.level().playSound(null, p.getX(), p.getY() + 1, p.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 1.2f);
		log.info("[Цитадель] {} вернулся", p.getScoreboardName());
	}

	/** Сколько порталов открыто сейчас — для /rickadmin citadel status. */
	public int openCount() {
		return open.size();
	}

	// ---------- геометрия ----------

	private static void draw(ServerLevel level, Vec3 base, float yaw) {
		Vec3 right = forward(yaw + 90);
		double cx = base.x;
		double cy = base.y + CENTER_Y;
		double cz = base.z;
		int n = 20;
		for (int i = 0; i < n; i++) {
			double a = 2 * Math.PI * i / n;
			double h = Math.cos(a) * RADIUS_X;
			double v = Math.sin(a) * RADIUS_Y;
			level.sendParticles(RING, cx + right.x * h, cy + v, cz + right.z * h, 1, 0, 0, 0, 0);
		}
		level.sendParticles(SWIRL, cx, cy, cz, 6, RADIUS_X * 0.4, RADIUS_Y * 0.4, RADIUS_X * 0.4, 0);
	}

	/** Игрок в портале: по горизонтали ближе метра к центру, по высоте — в пределах кольца. */
	static boolean inside(Vec3 player, Vec3 base) {
		double dx = player.x - base.x;
		double dz = player.z - base.z;
		double dy = player.y - base.y;
		return dx * dx + dz * dz < 0.9 * 0.9 && dy > -0.6 && dy < 1.6;
	}

	/** Направление взгляда по горизонтали: yaw 0 — на юг (+Z). */
	public static Vec3 forwardOf(float yaw) {
		return forward(yaw);
	}

	static Vec3 forward(float yaw) {
		double r = Math.toRadians(yaw);
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/** Место под портал: ноги и голова в воздухе, под ногами опора. */
	private static boolean free(ServerLevel level, Vec3 at) {
		BlockPos feet = BlockPos.containing(at);
		return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
				&& !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
	}

	static boolean calm(ServerPlayer p) {
		return p.isAlive() && p.hurtTime == 0
				&& (p.getLastHurtByMob() == null || p.tickCount - p.getLastHurtByMobTimestamp() > CALM_TICKS);
	}
}
