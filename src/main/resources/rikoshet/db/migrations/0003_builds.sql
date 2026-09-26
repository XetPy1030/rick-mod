-- Этап 2: анализ построек (docs/design/builds.md).
-- Постройка — клетка карты 64×64, где игроки строили; скан — счёт рукотворных блоков в её 16 чанках.

CREATE TABLE build_site (
    dim         TEXT    NOT NULL,
    cx          INTEGER NOT NULL,
    cz          INTEGER NOT NULL,
    owner       TEXT,              -- кто поставил здесь больше всех
    placed      INTEGER NOT NULL DEFAULT 0, -- поставлено здесь по следам летописи, за всё время
    first_ts    INTEGER NOT NULL,
    build_ts    INTEGER NOT NULL,  -- последний след стройки
    scan_ts     INTEGER,           -- последний скан
    artificial  INTEGER NOT NULL DEFAULT 0, -- рукотворных блоков на последнем скане
    baseline    INTEGER,           -- рукотворных на первом скане: деревни и крепости уже здесь
    scan        TEXT,              -- JSON: категории, материалы, высоты, биом, структура рядом
    history     TEXT,              -- JSON: день → рукотворных на последнем скане дня
    name        TEXT,
    description TEXT,
    named_at    INTEGER,           -- рукотворных, когда дали имя
    PRIMARY KEY (dim, cx, cz)
);

-- Кто сколько строил и копал на месте постройки
CREATE TABLE build_contrib (
    dim     TEXT    NOT NULL,
    cx      INTEGER NOT NULL,
    cz      INTEGER NOT NULL,
    uuid    TEXT    NOT NULL,
    placed  INTEGER NOT NULL DEFAULT 0,
    mined   INTEGER NOT NULL DEFAULT 0,
    last_ts INTEGER NOT NULL,
    PRIMARY KEY (dim, cx, cz, uuid)
);
