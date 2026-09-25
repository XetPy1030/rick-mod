-- Этап 1: игроки, роли, статистика за день, диалоги, журнал ИИ, жалобы.
-- Время — миллисекунды эпохи (ts), дни — 'YYYY-MM-DD' по timezone из конфига.

CREATE TABLE player (
    uuid          TEXT PRIMARY KEY,
    name          TEXT    NOT NULL,
    first_seen    INTEGER NOT NULL,
    last_seen     INTEGER NOT NULL,
    playtime_s    INTEGER NOT NULL DEFAULT 0,
    ai_opt_out    INTEGER NOT NULL DEFAULT 0,
    voice_opt_out INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX player_name ON player (name COLLATE NOCASE);

CREATE TABLE player_role (
    uuid             TEXT PRIMARY KEY,
    name             TEXT    NOT NULL, -- ник на момент назначения: роль можно дать тому, кто ещё не заходил
    title            TEXT    NOT NULL,
    archetype        TEXT    NOT NULL,
    archetype_manual INTEGER NOT NULL DEFAULT 0,
    note             TEXT,
    set_at           INTEGER NOT NULL
);

CREATE TABLE player_stat_daily (
    uuid  TEXT    NOT NULL,
    day   TEXT    NOT NULL,
    key   TEXT    NOT NULL,
    value INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, day, key)
);
CREATE INDEX player_stat_daily_day ON player_stat_daily (day);

CREATE TABLE dialogue_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid       TEXT    NOT NULL,
    persona_id TEXT    NOT NULL,
    role       TEXT    NOT NULL, -- player, persona, note
    text       TEXT    NOT NULL,
    ts         INTEGER NOT NULL
);
CREATE INDEX dialogue_log_player ON dialogue_log (uuid, persona_id, ts);
CREATE INDEX dialogue_log_ts ON dialogue_log (ts);

CREATE TABLE ai_log (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    ts               INTEGER NOT NULL,
    day              TEXT    NOT NULL,
    route            TEXT    NOT NULL,
    tag              TEXT,
    model            TEXT,
    status           TEXT    NOT NULL, -- ok, invalid, refused, error, timeout, late
    input_tokens     INTEGER NOT NULL DEFAULT 0,
    cached_tokens    INTEGER NOT NULL DEFAULT 0,
    output_tokens    INTEGER NOT NULL DEFAULT 0,
    reasoning_tokens INTEGER NOT NULL DEFAULT 0,
    latency_ms       INTEGER NOT NULL DEFAULT 0,
    cost_usd         REAL    NOT NULL DEFAULT 0,
    player_uuid      TEXT,
    output           TEXT,
    error            TEXT
);
CREATE INDEX ai_log_day ON ai_log (day);
CREATE INDEX ai_log_ts ON ai_log (ts);

CREATE TABLE ai_report (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    ts            INTEGER NOT NULL,
    reporter_uuid TEXT    NOT NULL,
    reporter_name TEXT    NOT NULL,
    persona_id    TEXT,
    line          TEXT,
    line_ts       INTEGER,
    comment       TEXT,
    resolved      INTEGER NOT NULL DEFAULT 0
);
