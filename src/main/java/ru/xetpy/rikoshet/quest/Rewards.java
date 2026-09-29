package ru.xetpy.rikoshet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.storage.Database;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Таблица наград — rikoshet/rewards.json (docs/design/characters/rick.md#награды). Рик выдаёт только
 * отсюда: награду квеста выбирает код, подарок в разговоре — модель, но в пределах таблицы и уровня.
 * Каждая выдача — строка в reward_log.
 */
public final class Rewards {
	/** Доля артефактов при случайной награде, если артефакты по уровню есть. */
	static final double ARTIFACT_CHANCE = 0.25;

	public enum Kind {
		RESOURCE, ARTIFACT
	}

	public record Entry(String id, String item, String name, int min, int max, Level level, Kind kind, List<String> lore,
			Map<String, Integer> enchantments) {
	}

	private final Database db;
	private final Map<String, Entry> entries;

	private Rewards(Database db, Map<String, Entry> entries) {
		this.db = db;
		this.entries = entries;
	}

	public static Rewards load(Database db, Path dataDir, Logger log) {
		JsonObject root = QuestDefs.read("rewards.json", dataDir, log);
		Map<String, Entry> out = new LinkedHashMap<>();
		for (JsonElement el : QuestDefs.array(root, "rewards")) {
			JsonObject o = el.getAsJsonObject();
			String id = QuestDefs.str(o, "id");
			String item = QuestDefs.str(o, "item");
			Identifier itemId = item == null ? null : Identifier.tryParse(item);
			Level level = Level.byId(QuestDefs.str(o, "level") == null ? "lab" : QuestDefs.str(o, "level"));
			if (id == null || itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId) || level == null) {
				log.warn("[награды] пропущена: {}", o);
				continue;
			}
			Kind kind = "artifact".equals(QuestDefs.str(o, "kind")) ? Kind.ARTIFACT : Kind.RESOURCE;
			List<String> lore = new ArrayList<>();
			for (JsonElement l : QuestDefs.array(o, "lore")) {
				lore.add(l.getAsString());
			}
			Map<String, Integer> ench = new LinkedHashMap<>();
			if (o.has("enchantments") && o.get("enchantments").isJsonObject()) {
				o.getAsJsonObject("enchantments").entrySet().forEach(e -> ench.put(e.getKey(), e.getValue().getAsInt()));
			}
			int min = Math.max(1, QuestDefs.num(o, "min", 1));
			int max = Math.max(min, QuestDefs.num(o, "max", min));
			String name = QuestDefs.str(o, "name") == null ? item : QuestDefs.str(o, "name");
			out.put(id, new Entry(id, item, name, min, max, level, kind, List.copyOf(lore), Map.copyOf(ench)));
		}
		log.info("[награды] в таблице {}", out.size());
		return new Rewards(db, Map.copyOf(out));
	}

	public Entry get(String id) {
		return entries.get(id);
	}

	/** Что доступно на этом уровне. Биомусору — ничего. */
	public List<Entry> available(Level level) {
		if (level == Level.BIOMASS) {
			return List.of();
		}
		return entries.values().stream().filter(e -> level.atLeast(e.level())).toList();
	}

	/** Случайная награда по уровню: чаще ресурс, изредка артефакт. null — нечего дать. */
	public Entry roll(Level level, Random rnd) {
		List<Entry> all = available(level);
		List<Entry> artifacts = all.stream().filter(e -> e.kind() == Kind.ARTIFACT).toList();
		List<Entry> resources = all.stream().filter(e -> e.kind() == Kind.RESOURCE).toList();
		if (!artifacts.isEmpty() && (resources.isEmpty() || rnd.nextDouble() < ARTIFACT_CHANCE)) {
			return artifacts.get(rnd.nextInt(artifacts.size()));
		}
		return resources.isEmpty() ? null : resources.get(rnd.nextInt(resources.size()));
	}

	public static int count(Entry e, Random rnd) {
		return e.min() + rnd.nextInt(e.max() - e.min() + 1);
	}

	/**
	 * Выдать игроку: в инвентарь, что не влезло — под ноги. Главный поток.
	 *
	 * @param source quest:&lt;id&gt; или gift
	 * @return что выдано, для сообщения игроку
	 */
	public Component give(ServerPlayer p, Entry e, int count, String giver, String source, long now, String day) {
		ItemStack stack = stack(e, count, p.level().registryAccess());
		Component shown = stack.getDisplayName();
		int n = stack.getCount();
		if (!p.getInventory().add(stack) && !stack.isEmpty()) {
			p.drop(stack, false);
		}
		db.execute("награда", c -> {
			try (PreparedStatement st = c.prepareStatement(
					"INSERT INTO reward_log (ts, day, uuid, giver, source, reward, item, count) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
				st.setLong(1, now);
				st.setString(2, day);
				st.setString(3, p.getUUID().toString());
				st.setString(4, giver);
				st.setString(5, source);
				st.setString(6, e.id());
				st.setString(7, e.item());
				st.setInt(8, n);
				st.executeUpdate();
			}
		});
		return n > 1 ? Component.empty().append(shown).append(" ×" + n) : shown;
	}

	static ItemStack stack(Entry e, int count, RegistryAccess access) {
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(e.item()));
		ItemStack s = new ItemStack(item, Math.max(1, Math.min(count, item.getDefaultMaxStackSize())));
		if (e.kind() == Kind.ARTIFACT) {
			s.set(DataComponents.CUSTOM_NAME, Component.literal(e.name()).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GREEN)));
			if (!e.lore().isEmpty()) {
				s.set(DataComponents.LORE, new ItemLore(e.lore().stream()
						.map(l -> (Component) Component.literal(l).withStyle(ChatFormatting.GRAY)).toList()));
			}
			var enchantments = access.lookupOrThrow(Registries.ENCHANTMENT);
			e.enchantments().forEach((id, lvl) -> {
				Identifier eid = Identifier.tryParse(id);
				if (eid != null) {
					enchantments.get(ResourceKey.create(Registries.ENCHANTMENT, eid)).ifPresent(h -> s.enchant(h, lvl));
				}
			});
		}
		return s;
	}
}
