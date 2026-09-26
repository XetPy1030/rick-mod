package ru.xetpy.rikoshet;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.xetpy.rikoshet.command.DevCommand;
import ru.xetpy.rikoshet.command.RickAdminCommand;
import ru.xetpy.rikoshet.command.RickCommand;

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
			if (r != null && server.getTickCount() % 20 == 0) {
				safe("тик", r::everySecond);
			}
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
		LOG.info("Рикошет загружен");
	}

	private static void safe(String what, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			LOG.error("Рикошет: ошибка в обработчике «{}»", what, e);
		}
	}
}
