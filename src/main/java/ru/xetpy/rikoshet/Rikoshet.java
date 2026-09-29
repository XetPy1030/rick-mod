package ru.xetpy.rikoshet;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.xetpy.rikoshet.command.DevCommand;
import ru.xetpy.rikoshet.command.RickAdminCommand;
import ru.xetpy.rikoshet.citadel.Citadel;
import ru.xetpy.rikoshet.command.RickCommand;
import ru.xetpy.rikoshet.npc.Npcs;
import ru.xetpy.rikoshet.pack.ResourcePack;

/**
 * Точка входа. Здесь только подписка на события; всё состояние — в {@link RikoshetRuntime}.
 * Любая ошибка мода логируется и не роняет сервер.
 */
public final class Rikoshet implements DedicatedServerModInitializer {
	public static final String MOD_ID = "rikoshet";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static volatile RikoshetRuntime runtime;

	public static RikoshetRuntime runtime() {
		return runtime;
	}

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			try {
				runtime = RikoshetRuntime.start(server, LOG);
			} catch (Exception e) {
				LOG.error("Рикошет не запустился, сервер работает без него", e);
				runtime = null;
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> safe("старт", () -> {
			RikoshetRuntime r = runtime;
			if (r != null) {
				r.started();
			}
		}));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> safe("остановка", () -> {
			RikoshetRuntime r = runtime;
			if (r != null) {
				r.stopping();
			}
		}));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> safe("закрытие", () -> {
			RikoshetRuntime r = runtime;
			runtime = null;
			if (r != null) {
				r.close();
			}
		}));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			RikoshetRuntime r = runtime;
			if (r == null) {
				return;
			}
			safe("порталы", r::everyTick);
			if (server.getTickCount() % 20 == 0) {
				safe("тик", r::everySecond);
			}
		});

		// Цитадель: урона нет, режим игры — по месту, NPC не бьются и говорят по ПКМ
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> Citadel.allowDamage(entity));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> {
			RikoshetRuntime r = runtime;
			if (r != null) {
				safe("смена мира", () -> r.citadel.syncMode(player));
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			RikoshetRuntime r = runtime;
			if (r != null) {
				safe("возрождение", () -> r.citadel.syncMode(newPlayer));
			}
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer sp) || !Npcs.isNpc(entity)) {
				return InteractionResult.PASS;
			}
			RikoshetRuntime r = runtime;
			if (r != null && hand == InteractionHand.MAIN_HAND && Npcs.isRick(entity)) {
				safe("ПКМ по Рику", () -> r.talkToRick(sp, entity));
			}
			return InteractionResult.SUCCESS;
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer sp) || !Npcs.isNpc(entity) || sp.isCreative()) {
				return InteractionResult.PASS;
			}
			RikoshetRuntime r = runtime;
			if (r != null && Npcs.isRick(entity)) {
				safe("удар по Рику", () -> r.hitRick(sp));
			}
			return InteractionResult.FAIL;
		});

		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			RikoshetRuntime r = runtime;
			if (r != null && entity instanceof ServerPlayer player) {
				safe("перед смертью", () -> r.chronicle.beforeDeath(player));
			}
			return true;
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			RikoshetRuntime r = runtime;
			if (r == null) {
				return;
			}
			if (entity instanceof ServerPlayer player) {
				safe("смерть", () -> {
					r.chronicle.death(player, source);
					r.flavor.onDeath(player, source);
				});
			} else {
				safe("смерть моба", () -> r.chronicle.entityDeath(entity, source));
			}
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			RikoshetRuntime r = runtime;
			if (r != null && entity instanceof ServerPlayer) {
				safe("урон", () -> r.chronicle.damage(entity, source, taken));
			}
		});
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			RikoshetRuntime r = runtime;
			if (r != null) {
				safe("чат", () -> r.chronicle.chat(sender, message.signedContent()));
				safe("чат: Рик", () -> {
					if (!r.talk.onMessage(sender, message.signedContent())) {
						r.chat.onMessage(sender, message.signedContent());
					}
				});
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			RikoshetRuntime r = runtime;
			if (r != null) {
				safe("вход", () -> r.auth.onJoin(handler.player));
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			RikoshetRuntime r = runtime;
			if (r != null) {
				safe("выход", () -> {
					String session = r.chronicle.leave(handler.player);
					r.flavor.onLeave(handler.player, r.auth.onLeave(handler.player), session);
				});
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			RickCommand.register(dispatcher, Rikoshet::runtime);
			RickAdminCommand.register(dispatcher, Rikoshet::runtime);
			if (DevCommand.enabled()) {
				DevCommand.register(dispatcher, Rikoshet::runtime);
			}
		});
		ResourcePack.init(LOG);
		// Версия в логе — чтобы по latest.log было видно, какая сборка стоит на сервере
		String version = FabricLoader.getInstance().getModContainer("rikoshet")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		LOG.info("Рикошет {} загружен", version);
	}

	private static void safe(String what, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			LOG.error("Рикошет: ошибка в обработчике «{}»", what, e);
		}
	}
}
