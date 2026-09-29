package ru.xetpy.rikoshet.quest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import ru.xetpy.rikoshet.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Квесты игроков (docs/design/quests.md): выдача, проверка, сдача, просрочка. Выполнение решает код —
 * предметы в инвентаре или прирост статистики, — модель только говорит. Состояние — quest_state,
 * в памяти — активные квесты и когда какой квест сдан последний раз. Главный поток.
 */
public final class QuestService {
	/** Штраф к репутации за просрочку: эксперимент и обычный квест. */
	static final int EXPIRED_EXPERIMENT = -1;
	static final int EXPIRED_QUEST = -2;

	/** Квест у игрока. def — условие на момент выдачи: правка quests.json не ломает взятые квесты. */
	public record Active(UUID uuid, String giver, LocalDate day, long taken, int baseline, QuestDefs.Def def) {
		public LocalDate deadline() {
			return day.plusDays(def.days());
		}
	}

	/**
	 * Итог проверки квеста при разговоре.
	 *
	 * @param reward     что выдано — для сообщения игроку (название переводит клиент)
	 * @param rewardText то же по-русски — для промпта: сервер переводов не знает
	 */
	public record Outcome(Active quest, boolean done, Component reward, String rewardText, Reputation.Change change) {
	}

	private final Logger log;
	private final Database db;
	private volatile QuestDefs defs;
	private volatile Rewards rewards;
	private final Reputation reputation;
	private final Random rnd = new Random();
	private final Map<UUID, List<Active>> active = new ConcurrentHashMap<>();
	/** uuid|квест → день последней сдачи. */
	private final Map<String, LocalDate> lastDone = new HashMap<>();
	private final Map<UUID, Integer> experimentsDone = new HashMap<>();

	public QuestService(Logger log, Database db, QuestDefs defs, Rewards rewards, Reputation reputation) {
		this.log = log;
		this.db = db;
		this.defs = defs;
		this.rewards = rewards;
		this.reputation = reputation;
	}

	public void load() throws SQLException {
		active.clear();
		lastDone.clear();
		experimentsDone.clear();
		db.call(c -> {
			try (PreparedStatement st = c.prepareStatement(
					"SELECT uuid, quest, giver, day, taken, baseline, spec FROM quest_state WHERE state = 'active' ORDER BY taken");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					QuestDefs.Def d = fromSpec(rs.getString(2), rs.getString(7));
					if (d != null) {
						UUID u = UUID.fromString(rs.getString(1));
						active.computeIfAbsent(u, k -> new ArrayList<>())
								.add(new Active(u, rs.getString(3), LocalDate.parse(rs.getString(4)), rs.getLong(5), rs.getInt(6), d));
					}
				}
			}
			try (PreparedStatement st = c.prepareStatement(
					"SELECT uuid, quest, MAX(day), COUNT(*) FROM quest_state WHERE state = 'done' GROUP BY uuid, quest");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					UUID u = UUID.fromString(rs.getString(1));
					lastDone.put(key(u, rs.getString(2)), LocalDate.parse(rs.getString(3)));
					if (QuestDefs.EXPERIMENT.equals(rs.getString(2))) {
						experimentsDone.put(u, rs.getInt(4));
					}
				}
			}
			return null;
		});
	}

	/** /rickadmin reload: новые quests.json и rewards.json. Взятые квесты живут по условию на момент выдачи. */
	public void reload(QuestDefs defs, Rewards rewards) {
		this.defs = defs;
		this.rewards = rewards;
	}

	public QuestDefs defs() {
		return defs;
	}

	public Rewards rewards() {
		return rewards;
	}

	public List<Active> active(UUID u) {
		return List.copyOf(active.getOrDefault(u, List.of()));
	}

	public int experimentsDone(UUID u) {
		return experimentsDone.getOrDefault(u, 0);
	}

	/** Что Рик может выдать игроку сейчас: эксперимент дня (если не брал и не сдал) и квесты по уровню. */
	public List<QuestDefs.Def> available(UUID u, LocalDate today, int maxActive) {
		Level level = reputation.level(u);
		List<Active> mine = active.getOrDefault(u, List.of());
		if (level == Level.BIOMASS || mine.size() >= maxActive) {
			return List.of();
		}
		List<QuestDefs.Def> out = new ArrayList<>();
		QuestDefs.Def exp = defs.experiment(today);
		if (exp != null && mine.stream().noneMatch(a -> a.def().id().equals(QuestDefs.EXPERIMENT))
				&& !today.equals(lastDone.get(key(u, QuestDefs.EXPERIMENT)))) {
			out.add(exp);
		}
		for (QuestDefs.Def d : defs.all()) {
			if (!level.atLeast(d.level()) || mine.stream().anyMatch(a -> a.def().id().equals(d.id()))) {
				continue;
			}
			LocalDate done = lastDone.get(key(u, d.id()));
			if (done != null && done.plusDays(d.repeatDays()).isAfter(today)) {
				continue;
			}
			out.add(d);
		}
		return out;
	}

	/** Выдать квест из available. Эксперимент — на сегодня, остальные — на days дней. */
	public Active accept(ServerPlayer p, QuestDefs.Def d, String giver, LocalDate today, long now) {
		int baseline = d.kind() == QuestDefs.Kind.STAT ? StatRef.parse(d.stat()).read(p) : 0;
		Active a = new Active(p.getUUID(), giver, today, now, baseline, d);
		active.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(a);
		String spec = spec(d).toString();
		db.execute("квест выдан", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO quest_state (uuid, quest, giver, day, state, taken, baseline, target, spec)
					VALUES (?, ?, ?, ?, 'active', ?, ?, ?, ?)""")) {
				st.setString(1, a.uuid().toString());
				st.setString(2, d.id());
				st.setString(3, giver);
				st.setString(4, today.toString());
				st.setLong(5, now);
				st.setInt(6, baseline);
				st.setInt(7, d.target());
				st.setString(8, spec);
				st.executeUpdate();
			}
		});
		log.info("[квесты] {} взял «{}»: {}", p.getScoreboardName(), d.title(), d.goal());
		return a;
	}

	/** Сколько сделано, в единицах показа (блоки, штуки). */
	public int progress(ServerPlayer p, Active a) {
		QuestDefs.Def d = a.def();
		if (d.kind() == QuestDefs.Kind.BRING) {
			return count(p.getInventory(), item(d));
		}
		StatRef ref = StatRef.parse(d.stat());
		return ref == null ? 0 : Math.max(0, ref.read(p) - a.baseline()) / d.unit();
	}

	public boolean expired(Active a, LocalDate today) {
		return !today.isBefore(a.deadline());
	}

	/**
	 * Проверить квесты игрока: выполненные сдать (забрать предметы, выдать награду), просроченные —
	 * провалить со штрафом. Вызывается, когда игрок говорит с Риком.
	 */
	public List<Outcome> check(ServerPlayer p, LocalDate today, long now) {
		List<Active> mine = active.get(p.getUUID());
		if (mine == null || mine.isEmpty()) {
			return List.of();
		}
		List<Outcome> out = new ArrayList<>();
		for (Active a : List.copyOf(mine)) {
			QuestDefs.Def d = a.def();
			if (progress(p, a) >= d.count()) {
				if (d.kind() == QuestDefs.Kind.BRING && take(p.getInventory(), item(d), d.count()) < d.count()) {
					continue; // не должно случиться: посчитали и забрали в одном тике
				}
				mine.remove(a);
				finish(a, "done", now);
				lastDone.put(key(a.uuid(), d.id()), today);
				if (QuestDefs.EXPERIMENT.equals(d.id())) {
					experimentsDone.merge(a.uuid(), 1, Integer::sum);
				}
				Reputation.Change ch = reputation.add(a.uuid(), d.reputation(), "quest", d.id(), now, today.toString());
				Component reward = null;
				String rewardText = null;
				if (d.roll()) {
					Rewards.Entry e = rewards.roll(ch.to(), rnd);
					if (e != null) {
						int n = Rewards.count(e, rnd);
						reward = rewards.give(p, e, n, a.giver(), "quest:" + d.id(), now, today.toString());
						rewardText = (e.kind() == Rewards.Kind.ARTIFACT ? "«" + e.name() + "»" : e.name()) + (n > 1 ? " ×" + n : "");
					}
				}
				log.info("[квесты] {} сдал «{}», репутация {} → {}", p.getScoreboardName(), d.title(), ch.before(), ch.after());
				out.add(new Outcome(a, true, reward, rewardText, ch));
			} else if (expired(a, today)) {
				mine.remove(a);
				finish(a, "failed", now);
				int penalty = QuestDefs.EXPERIMENT.equals(d.id()) ? EXPIRED_EXPERIMENT : EXPIRED_QUEST;
				Reputation.Change ch = reputation.add(a.uuid(), penalty, "quest", d.id() + " просрочен", now, today.toString());
				log.info("[квесты] {} просрочил «{}»", p.getScoreboardName(), d.title());
				out.add(new Outcome(a, false, null, null, ch));
			}
		}
		return out;
	}

	private void finish(Active a, String state, long now) {
		db.execute("квест " + state, c -> {
			try (PreparedStatement st = c.prepareStatement(
					"UPDATE quest_state SET state = ?, finished = ? WHERE uuid = ? AND quest = ? AND taken = ? AND state = 'active'")) {
				st.setString(1, state);
				st.setLong(2, now);
				st.setString(3, a.uuid().toString());
				st.setString(4, a.def().id());
				st.setLong(5, a.taken());
				st.executeUpdate();
			}
		});
	}

	// ---------- инвентарь ----------

	static Item item(QuestDefs.Def d) {
		return BuiltInRegistries.ITEM.getValue(Identifier.parse(d.item()));
	}

	static int count(Inventory inv, Item item) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	/** Забрать n штук; возвращает, сколько забрано. */
	static int take(Inventory inv, Item item, int n) {
		int left = n;
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				int k = Math.min(left, s.getCount());
				s.shrink(k);
				left -= k;
			}
		}
		inv.setChanged();
		return n - left;
	}

	// ---------- условие квеста в БД ----------

	static JsonObject spec(QuestDefs.Def d) {
		JsonObject o = new JsonObject();
		o.addProperty("title", d.title());
		o.addProperty("kind", d.kind().name().toLowerCase());
		if (d.item() != null) {
			o.addProperty("item", d.item());
			o.addProperty("name", d.name());
		}
		if (d.stat() != null) {
			o.addProperty("stat", d.stat());
			o.addProperty("what", d.what());
		}
		o.addProperty("count", d.count());
		o.addProperty("unit", d.unit());
		o.addProperty("level", d.level().id);
		o.addProperty("days", d.days());
		o.addProperty("repeat_days", d.repeatDays());
		o.addProperty("reputation", d.reputation());
		o.addProperty("roll", d.roll());
		if (d.brief() != null) {
			o.addProperty("brief", d.brief());
		}
		return o;
	}

	static QuestDefs.Def fromSpec(String id, String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			QuestDefs.Kind kind = "stat".equals(QuestDefs.str(o, "kind")) ? QuestDefs.Kind.STAT : QuestDefs.Kind.BRING;
			Level level = Level.byId(QuestDefs.str(o, "level"));
			return new QuestDefs.Def(id, QuestDefs.str(o, "title"), kind, QuestDefs.str(o, "item"), QuestDefs.str(o, "name"),
					QuestDefs.str(o, "stat"), QuestDefs.str(o, "what"), QuestDefs.num(o, "count", 1), QuestDefs.num(o, "unit", 1),
					level == null ? Level.LAB : level, QuestDefs.num(o, "days", 1), QuestDefs.num(o, "repeat_days", 1),
					QuestDefs.num(o, "reputation", 0), QuestDefs.bool(o, "roll", false), QuestDefs.str(o, "brief"));
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String key(UUID u, String quest) {
		return u + "|" + quest;
	}
}
