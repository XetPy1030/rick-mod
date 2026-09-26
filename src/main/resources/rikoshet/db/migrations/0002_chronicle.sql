-- Этап 2: летопись (docs/design/chronicle.md) и газета.
-- player_stat_daily с этапа 1 теперь хранит всё за день: приросты ванильной статистики,
-- производные счётчики x:*, занятия act:*, время по измерениям и биомам.

-- Рекорды: лучший день по ключу
CREATE INDEX player_stat_daily_key ON player_stat_daily (key, value);

-- Заметные события: смерти, находки, вехи, достижения, питомцы, визиты
CREATE TABLE chronicle_event (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    ts      INTEGER NOT NULL,
    day     TEXT    NOT NULL,
    uuid    TEXT,              -- о ком событие; NULL — о сервере
    type    TEXT    NOT NULL,  -- death, first, milestone, advancement, pet_death, named_death, visit, session_record…
    subject TEXT,              -- что именно: id измерения, предмета, достижения, моба
    score   INTEGER NOT NULL DEFAULT 0,
    data    TEXT               -- JSON с подробностями
);
CREATE INDEX chronicle_event_day ON chronicle_event (day);
CREATE INDEX chronicle_event_player ON chronicle_event (uuid, type, ts);

-- Что игрок уже видел или получил: для «впервые» и «впервые на сервере»
CREATE TABLE player_seen (
    uuid     TEXT    NOT NULL,
    kind     TEXT    NOT NULL, -- dimension, biome, structure, item, advancement
    id       TEXT    NOT NULL,
    first_ts INTEGER NOT NULL,
    PRIMARY KEY (uuid, kind, id)
);
CREATE INDEX player_seen_first ON player_seen (first_ts);

-- Где игрок бывал: клетки 64×64 блока, секунды за всё время. Отсюда дом и разведка
CREATE TABLE player_cell (
    uuid     TEXT    NOT NULL,
    dim      TEXT    NOT NULL,
    cx       INTEGER NOT NULL,
    cz       INTEGER NOT NULL,
    seconds  INTEGER NOT NULL DEFAULT 0,
    first_ts INTEGER NOT NULL,
    last_ts  INTEGER NOT NULL,
    PRIMARY KEY (uuid, dim, cx, cz)
);

-- Взаимодействия пар игроков за день (docs/design/chronicle.md#взаимодействия-игроков).
-- Симметричные ключи (together, overlap, joint:*, dialogue…) — a < b по строке UUID;
-- направленные (gift, rescue, pvp_damage, visit…) — a кто сделал, b кому
CREATE TABLE pair_daily (
    day   TEXT    NOT NULL,
    a     TEXT    NOT NULL,
    b     TEXT    NOT NULL,
    key   TEXT    NOT NULL,
    value INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (day, a, b, key)
);
CREATE INDEX pair_daily_day ON pair_daily (day);

-- Сессии: для прогноза онлайна и «в прошлый раз ты…»
CREATE TABLE player_session (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid     TEXT    NOT NULL,
    start_ts INTEGER NOT NULL,
    end_ts   INTEGER NOT NULL,
    seconds  INTEGER NOT NULL,
    afk_s    INTEGER NOT NULL DEFAULT 0,
    deaths   INTEGER NOT NULL DEFAULT 0,
    summary  TEXT              -- JSON: занятия и итоги сессии
);
CREATE INDEX player_session_player ON player_session (uuid, start_ts);
CREATE INDEX player_session_start ON player_session (start_ts);

-- Счётчики за всё время для вех и прогнозов; обновляются при каждом снимке
CREATE TABLE player_total (
    uuid  TEXT    NOT NULL,
    key   TEXT    NOT NULL,
    value INTEGER NOT NULL,
    ts    INTEGER NOT NULL,
    PRIMARY KEY (uuid, key)
);

-- Профиль игрока: стиль игры, привычки; пересчитывается ночью
CREATE TABLE player_profile (
    uuid TEXT PRIMARY KEY,
    day  TEXT NOT NULL,
    data TEXT NOT NULL         -- JSON
);

-- Итоги дня: факты, прогнозы, сводки игроков
CREATE TABLE chronicle_day (
    day        TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    report     TEXT    NOT NULL -- JSON
);

-- Архив газет
CREATE TABLE newspaper (
    day          TEXT PRIMARY KEY, -- за какой день выпуск
    published_at INTEGER NOT NULL,
    headline     TEXT    NOT NULL,
    body         TEXT    NOT NULL, -- JSON выпуска
    source       TEXT    NOT NULL, -- ai, fallback
    model        TEXT,
    cost_usd     REAL    NOT NULL DEFAULT 0
);

-- Память: знания-слоты об игроке, паре или сервере (docs/architecture/memory.md).
-- У слота одно текущее значение (until_ts IS NULL); прежние остаются историей
CREATE TABLE memory_fact (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    subject    TEXT    NOT NULL, -- UUID игрока, «pair:<a>:<b>» или «server»
    slot       TEXT    NOT NULL, -- style, nemesis, best_friend, story:… 
    value      TEXT    NOT NULL, -- короткая фраза по-русски, как её увидит модель
    data       TEXT,             -- JSON: на чём основано
    importance INTEGER NOT NULL DEFAULT 10,
    confidence REAL    NOT NULL DEFAULT 1,
    first_ts   INTEGER NOT NULL, -- когда слот впервые получил это значение
    updated_ts INTEGER NOT NULL, -- когда значение последний раз подтвердилось
    until_ts   INTEGER,          -- когда значение сменилось; NULL — текущее
    source     TEXT    NOT NULL  -- chronicle, dialogue, admin
);
CREATE INDEX memory_fact_subject ON memory_fact (subject, slot, until_ts);
