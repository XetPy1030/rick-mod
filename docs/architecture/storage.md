# Хранилище

> **Статус:** этапы 1–2 реализованы (таблицы с пометкой этапа), остальное — черновик · **Этап:** 1–2 · **Решение:** [ADR 0004](../adr/0004-storage-sqlite.md) (принято)

## Где и как

- Файл БД: `<папка сервера>/rikoshet/rikoshet.db`. Это не папка мира: данные общие для всех измерений и переживают копии миров для ивентов.
- SQLite в режиме WAL, `synchronous=NORMAL`, `busy_timeout` 5 с, внешние ключи включены. Драйвер `sqlite-jdbc` вложен в jar мода.
- Все запросы идут через один поток БД `rikoshet-db`. Главный поток только ставит задачи в очередь; синхронно БД читается один раз — при старте сервера, когда данные загружаются в память. Код — `ru.xetpy.rikoshet.storage`.
- Миграции — пронумерованные SQL-файлы в ресурсах мода (`rikoshet/db/migrations/0001_init.sql` …), каждая в транзакции. Текущая версия схемы хранится в таблице `schema_version`. БД новее мода (откат на старую версию) — мод не запускается, сервер работает без него.
- Время — миллисекунды эпохи, дни — `YYYY-MM-DD` по `timezone` из [конфига](../ops/configuration.md).
- Папка `rikoshet/` входит в бэкапы сервера ([server-setup](../ops/server-setup.md#бэкапы)). В ней же лежит файл секретов с ключом OpenRouter ([server-setup](../ops/server-setup.md#ключ-openrouter)), поэтому папку целиком никуда не выкладываем.

## Схема

### Игроки

| Таблица | Поля | Для чего |
|---|---|---|
| `player` · этап 1 | `uuid` PK, `name`, `first_seen`, `last_seen`, `playtime_s`, `ai_opt_out`, `voice_opt_out` | профиль, `/rick off`, `/rick voice off`. UUID оффлайновый — от ника с учётом регистра. Запись появляется при входе после пароля EasyAuth |
| `player_role` · этап 1 | `uuid` PK, `name`, `title`, `archetype`, `archetype_manual`, `note`, `set_at` | роль игрока: кто он во вселенной ([player-roles](../design/player-roles.md)). `name` — ник на момент назначения: роль можно дать тому, кто ещё не заходил. `archetype_manual` — архетип задан вручную и не пересчитывается по названию |
| `player_stat_daily` · этапы 1–2 | `uuid`, `day`, `key`, `value` | всё за день: приросты ванильной статистики (`mined:stone`), производные `x:*`, занятия `act:*`, время по измерениям и биомам ([ключи](../design/chronicle.md#что-хранится-за-день)). Этап 1 писал сюда `deaths` и `death.<группа>`. Индекс `(key, value)` — для рекордов |
| `title` | `uuid`, `title_id`, `earned_at`, `active` | титулы |

### Летопись · этап 2

Что игроки делали: [chronicle](../design/chronicle.md). Миграция `0002_chronicle`.

| Таблица | Поля | Для чего |
|---|---|---|
| `chronicle_event` | `id`, `ts`, `day`, `uuid`, `type`, `subject`, `score`, `data` | заметные события: смерть с подробностями, «впервые», веха, достижение, босс, питомец, именной моб, визит, подарок, «спас», месть, вещи погибшего. `score` — значимость для газеты, `data` — JSON |
| `player_seen` | `uuid`, `kind`, `id`, `first_ts` | что игрок уже видел или получил: измерение, биом, структура, предмет-веха, достижение, босс. Самая ранняя запись по `kind`+`id` — «первый на сервере» |
| `player_cell` | `uuid`, `dim`, `cx`, `cz`, `seconds`, `first_ts`, `last_ts` | где бывал: клетки 64×64, секунды за всё время. Самая обжитая (от 2 ч) — дом |
| `pair_daily` | `day`, `a`, `b`, `key`, `value` | взаимодействия пар за день: симметричные (`together`, `overlap`, `joint:*`…) с `a < b`, направленные (`gift`, `rescue`, `visit`…) — `a` сделал, `b` получил ([ключи](../design/chronicle.md#взаимодействия-игроков)) |
| `player_session` | `id`, `uuid`, `start_ts`, `end_ts`, `seconds`, `afk_s`, `deaths`, `summary` | сессии: прогноз онлайна, пик, «в прошлый раз ты…». `summary` — JSON: занятия и итоги |
| `player_total` | `uuid`, `key`, `value`, `ts` | счётчики за всё время для вех и прогнозов, при каждом снимке |
| `player_profile` | `uuid`, `day`, `data` | профиль за 14 дней: стиль, привычки, лучший друг, немезида. Пересчитывается ночью |
| `chronicle_day` | `day`, `created_at`, `report` | итоги дня JSON-ом: сводка, игроки, связи, факты, прогнозы, профили |

### Постройки · этап 2

[builds](../design/builds.md). Миграция `0003_builds`.

| Таблица | Поля | Для чего |
|---|---|---|
| `build_site` | `dim`, `cx`, `cz`, `owner`, `placed`, `first_ts`, `build_ts`, `scan_ts`, `artificial`, `baseline`, `scan`, `history`, `name`, `description`, `named_at` | постройка — клетка 64×64, где строили: хозяин, рукотворных на последнем скане и на первом, скан JSON-ом (виды, материалы, высоты, биом, структура), история по дням, имя и описание от аналитика |
| `build_contrib` | `dim`, `cx`, `cz`, `uuid`, `placed`, `mined`, `last_ts` | кто сколько строил и копал на месте постройки |

### Персонажи

| Таблица | Поля | Для чего |
|---|---|---|
| `reputation` | `uuid`, `scale_id`, `value` | «Полезность для науки», Федерация, Цитадель |
| `persona_memory` | `uuid`, `persona_id`, `summary`, `updated_at` | сжатая память |
| `memory_fact` · этап 2 | `id`, `subject`, `slot`, `value`, `data`, `importance`, `confidence`, `first_ts`, `updated_ts`, `until_ts`, `source` | знания-слоты об игроке, паре или сервере: стиль, немезида, лучший друг, сюжетные линии. У слота одно текущее значение (`until_ts` пуст), прежние — история ([memory](memory.md)) |
| `dialogue_log` · этап 1 | `id`, `uuid`, `persona_id`, `role`, `text`, `ts` | сырая история; `role` — `player`, `persona` или `note` (заметка `remember` / `memory_note`). В промпт идут последние 5 заметок. На этапе 1 таблица только читается: заметки пишут диалоги (этап 3) |

### Квесты и экономика

| Таблица | Поля | Для чего |
|---|---|---|
| `quest_state` | `uuid`, `quest_id`, `state`, `progress_json`, `started_at`, `finished_at` | активные и завершённые квесты |
| `daily_experiment` | `date`, `experiment_json` | эксперимент дня |
| `ledger` | `id`, `uuid`, `delta`, `reason`, `ts` | журнал шмекелей (нужен и при физических монетах — для аналитики) |

### Мир и ивенты

| Таблица | Поля | Для чего |
|---|---|---|
| `named_mob` | `entity_uuid`, `name`, `owner_uuid`, `born_at`, `died_at`, `death_cause`, `biography`, `obituary` | именные мобы и некрологи |
| `world_change` | `id`, `source`, `dimension`, `bbox`, `structure`, `placed_at`, `removed_at` | реестр изменений мира от мода — для отката ([worlds](worlds.md#реестр-изменений)) |
| `event_run` | `id`, `event_id`, `phase`, `started_at`, `ended_at`, `result_json` | история ивентов |

### ИИ

| Таблица | Поля | Для чего |
|---|---|---|
| `content_pool` | `id`, `pool`, `key`, `text`, `generated_at`, `shown_count` | пулы заготовок |
| `pool_seen` | `uuid`, `pool_item_id` | чтобы игрок не видел одно и то же |
| `newspaper` · этап 2 | `day`, `published_at`, `headline`, `body`, `source`, `model`, `cost_usd` | архив газет: за какой день, JSON выпуска, `ai` или `fallback` |
| `ai_log` · этап 1 | `id`, `ts`, `day`, `route`, `tag`, `model`, `status`, `input_tokens`, `cached_tokens`, `output_tokens`, `reasoning_tokens`, `latency_ms`, `cost_usd`, `player_uuid`, `output`, `error` | расходы и отладка. Строка на каждую попытку, не на запрос. `tag` — задача (`death`, `join`, `test`…), `status` — `ok`, `invalid`, `refused`, `error`, `timeout`, `late` ([ai-integration](ai-integration.md#отказы-и-ошибки)), `output` — ответ до 4000 символов. По `day` восстанавливается дневной бюджет после перезапуска |
| `ai_report` · этап 1 | `id`, `ts`, `reporter_uuid`, `reporter_name`, `persona_id`, `line`, `line_ts`, `comment`, `resolved` | жалобы `/rick report`: на какую реплику и что не так. Разбор — `/rickadmin report` ([commands](../ops/commands.md)) |

## Хранение и чистка

| Данные | Срок |
|---|---|
| `dialogue_log` | 14 дней (`dialogue_retention_days`), дальше живёт только саммари. Чистка — при старте сервера |
| `ai_log` | 90 дней (`ai_log_retention_days`), чистка при старте |
| `content_pool` | неиспользованное старше 7 дней удаляется |
| остальное | бессрочно |

## Решённые вопросы

- Ванильную статистику читаем из памяти, снимками раз в 5 минут, а считаем своё только то, чего ванилла не знает: где, с кем, AFK, занятия ([ADR 0006](../adr/0006-chronicle-from-vanilla-stats.md)).
