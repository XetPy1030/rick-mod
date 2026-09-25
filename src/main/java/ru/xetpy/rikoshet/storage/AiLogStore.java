package ru.xetpy.rikoshet.storage;

import ru.xetpy.rikoshet.ai.AiLogEntry;
import ru.xetpy.rikoshet.ai.AiLogSink;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;

/** Таблица ai_log: каждая попытка запроса к ИИ, с токенами и стоимостью. */
public final class AiLogStore implements AiLogSink {
	private final Database db;

	public AiLogStore(Database db) {
		this.db = db;
	}

	@Override
	public void write(AiLogEntry e) {
		db.execute("ai_log", c -> {
			try (PreparedStatement st = c.prepareStatement("""
					INSERT INTO ai_log (ts, day, route, tag, model, status, input_tokens, cached_tokens, output_tokens,
						reasoning_tokens, latency_ms, cost_usd, player_uuid, output, error)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
				st.setLong(1, e.ts().toEpochMilli());
				st.setString(2, e.day().toString());
				st.setString(3, e.route());
				st.setString(4, e.tag());
				st.setString(5, e.model());
				st.setString(6, e.status());
				st.setInt(7, e.inputTokens());
				st.setInt(8, e.cachedTokens());
				st.setInt(9, e.outputTokens());
				st.setInt(10, e.reasoningTokens());
				st.setLong(11, e.latencyMs());
				st.setDouble(12, e.costUsd());
				if (e.player() == null) {
					st.setNull(13, Types.VARCHAR);
				} else {
					st.setString(13, e.player().toString());
				}
				st.setString(14, e.output());
				st.setString(15, e.error());
				st.executeUpdate();
			}
		});
	}

	/** Сколько потрачено за день — для бюджета после перезапуска. */
	public double spent(LocalDate day) throws SQLException {
		return db.call(c -> {
			try (PreparedStatement st = c.prepareStatement("SELECT COALESCE(SUM(cost_usd), 0) FROM ai_log WHERE day = ?")) {
				st.setString(1, day.toString());
				try (ResultSet rs = st.executeQuery()) {
					return rs.next() ? rs.getDouble(1) : 0.0;
				}
			}
		});
	}
}
