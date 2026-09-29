-- Этап 3: Цитадель, репутация, квесты, награды (docs/architecture/storage.md).

-- Мелкое состояние мода: поставлена ли Цитадель, где её точки
CREATE TABLE kv (
    key     TEXT PRIMARY KEY,
    value   TEXT    NOT NULL,
    updated INTEGER NOT NULL
);

-- Откуда игрок ушёл в Цитадель: туда вернёт портал обратно
CREATE TABLE citadel_return (
    uuid  TEXT PRIMARY KEY,
    dim   TEXT    NOT NULL,
    x     REAL    NOT NULL,
    y     REAL    NOT NULL,
    z     REAL    NOT NULL,
    yaw   REAL    NOT NULL,
    pitch REAL    NOT NULL,
    ts    INTEGER NOT NULL
);

-- Репутации: science — «Полезность для науки» у Рика, −100…+100
CREATE TABLE reputation (
    uuid    TEXT    NOT NULL,
    scale   TEXT    NOT NULL,
    value   INTEGER NOT NULL,
    updated INTEGER NOT NULL,
    PRIMARY KEY (uuid, scale)
);

CREATE TABLE reputation_log (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    ts     INTEGER NOT NULL,
    day    TEXT    NOT NULL,
    uuid   TEXT    NOT NULL,
    scale  TEXT    NOT NULL,
    delta  INTEGER NOT NULL,
    value  INTEGER NOT NULL, -- после изменения
    source TEXT    NOT NULL, -- talk, quest, admin
    reason TEXT
);
CREATE INDEX reputation_log_player ON reputation_log (uuid, ts);

-- Квесты игроков. Эксперимент дня — quest 'experiment' и его день в day
CREATE TABLE quest_state (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid     TEXT    NOT NULL,
    quest    TEXT    NOT NULL,
    giver    TEXT    NOT NULL,
    day      TEXT    NOT NULL, -- день выдачи
    state    TEXT    NOT NULL, -- active, done, failed, dropped
    taken    INTEGER NOT NULL,
    finished INTEGER,
    baseline INTEGER NOT NULL DEFAULT 0, -- значение статистики при выдаче
    target   INTEGER NOT NULL,
    spec     TEXT    NOT NULL           -- условие и награда на момент выдачи, JSON
);
CREATE INDEX quest_state_player ON quest_state (uuid, state);

-- Что мод выдал: награды квестов и подарки в разговоре
CREATE TABLE reward_log (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    ts     INTEGER NOT NULL,
    day    TEXT    NOT NULL,
    uuid   TEXT    NOT NULL,
    giver  TEXT    NOT NULL,
    source TEXT    NOT NULL, -- quest:<id>, gift
    reward TEXT    NOT NULL, -- id из таблицы наград
    item   TEXT    NOT NULL,
    count  INTEGER NOT NULL
);
CREATE INDEX reward_log_player ON reward_log (uuid, day);
