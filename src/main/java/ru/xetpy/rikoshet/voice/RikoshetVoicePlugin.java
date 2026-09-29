package ru.xetpy.rikoshet.voice;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import ru.xetpy.rikoshet.Rikoshet;

import java.util.UUID;

/**
 * Плагин Simple Voice Chat (entrypoint voicechat в fabric.mod.json). Единственный класс мода,
 * который знает API голосового чата: без мода он не загружается вовсе. Звук идёт из канала,
 * привязанного к NPC, и затихает с расстоянием; громкость — своя категория в настройках чата.
 */
public final class RikoshetVoicePlugin implements VoicechatPlugin {
	static final String CATEGORY = "rikoshet";

	@Override
	public String getPluginId() {
		return Rikoshet.MOD_ID;
	}

	@Override
	public void registerEvents(EventRegistration events) {
		events.registerEvent(VoicechatServerStartedEvent.class, this::started);
		events.registerEvent(VoicechatServerStoppedEvent.class, e -> VoiceOut.set(null));
	}

	private void started(VoicechatServerStartedEvent event) {
		VoicechatServerApi api = event.getVoicechat();
		VolumeCategory category = api.volumeCategoryBuilder()
				.setId(CATEGORY)
				.setName("Персонажи")
				.setDescription("Рик и другие персонажи «Рикошета»")
				.build();
		api.registerVolumeCategory(category);
		VoiceOut.set((source, pcm, distance, hear) -> {
			EntityAudioChannel channel = api.createEntityAudioChannel(UUID.randomUUID(), api.fromEntity(source));
			if (channel == null) {
				return;
			}
			channel.setCategory(CATEGORY);
			channel.setDistance(distance);
			channel.setFilter(p -> hear.test(p.getUuid()));
			AudioPlayer player = api.createAudioPlayer(channel, api.createEncoder(), pcm);
			player.startPlaying();
		});
		Rikoshet.LOG.info("[голос] Simple Voice Chat подключён");
	}
}
