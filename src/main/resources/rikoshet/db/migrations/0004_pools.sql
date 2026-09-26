-- Этап 2: пулы заготовок и пакеты Batch API OpenRouter (docs/architecture/ai-integration.md#пулы-заготовок).

-- Пакет запросов: переживает перезапуск сервера — опрос продолжается
CREATE TABLE ai_batch (
    id         TEXT PRIMARY KEY, -- id пакета у OpenRouter
    created_at INTEGER NOT NULL,
    route      TEXT    NOT NULL,
    model      TEXT    NOT NULL,
    purpose    TEXT    NOT NULL, -- pools, …
    requests   TEXT    NOT NULL, -- JSON: custom_id → что это за запрос
    status     TEXT    NOT NULL, -- validating, in_progress, finalizing, completed, failed, expired, cancelled
    done_at    INTEGER,
    cost_usd   REAL    NOT NULL DEFAULT 0
);

-- Заготовки: реплики, MOTD, бегущая строка таблиста
CREATE TABLE content_pool (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    pool         TEXT    NOT NULL, -- death, death_archetype, join, leave, motd, tab
    key          TEXT    NOT NULL, -- lava, jerry, new…
    text         TEXT    NOT NULL,
    generated_at INTEGER NOT NULL,
    model        TEXT,
    shown_count  INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX content_pool_key ON content_pool (pool, key);
