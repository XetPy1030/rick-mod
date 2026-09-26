package ru.xetpy.rikoshet.newspaper;

import com.google.gson.Gson;
import ru.xetpy.rikoshet.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Архив газет (таблица newspaper): выпуск целиком — JSON в body. */
public final class NewspaperStore {
	private static final Gson GSON = new Gson();
	private final Database db;

	public NewspaperStore(Database db) {
		this.db = db;
	}

	public void save(Issue i) {
		String json = GSON.toJson(i);
		db.execute("newspaper", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO newspaper (day, published_at, headline, body, source, model, cost_usd) VALUES (?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (day) DO UPDATE SET published_at = excluded.published_at, headline = excluded.headline,
					body = excluded.body, source = excluded.source, model = excluded.model, cost_usd = excluded.cost_usd""")) {
				st.setString(1, i.day());
				st.setLong(2, i.publishedAt());
				st.setString(3, i.headline());
				st.setString(4, json);
				st.setString(5, i.source());
				st.setString(6, i.model());
				st.setDouble(7, i.costUsd());
				st.executeUpdate();
			}
		});
	}

	/** Свежий выпуск или null. Синхронно — только при старте. */
	public Issue latest() throws SQLException {
		return db.call(c -> {
			try (PreparedStatement st = c.prepareStatement("SELECT body FROM newspaper ORDER BY day DESC LIMIT 1");
					ResultSet rs = st.executeQuery()) {
				return rs.next() ? GSON.fromJson(rs.getString(1), Issue.class) : null;
			}
		});
	}

	public CompletableFuture<Issue> byDay(String day) {
		return db.submit(c -> {
			try (PreparedStatement st = c.prepareStatement("SELECT body FROM newspaper WHERE day = ?")) {
				st.setString(1, day);
				try (ResultSet rs = st.executeQuery()) {
					return rs.next() ? GSON.fromJson(rs.getString(1), Issue.class) : null;
				}
			}
		});
	}

	/** Заголовки выпусков до дня, свежие первыми. */
	public CompletableFuture<List<String>> headlinesBefore(String day, int n) {
		return db.submit(c -> {
			List<String> out = new ArrayList<>();
			try (PreparedStatement st = c.prepareStatement("SELECT headline FROM newspaper WHERE day < ? ORDER BY day DESC LIMIT ?")) {
				st.setString(1, day);
				st.setInt(2, n);
				try (ResultSet rs = st.executeQuery()) {
					while (rs.next()) {
						out.add(rs.getString(1));
					}
				}
			}
			return out;
		});
	}
}
