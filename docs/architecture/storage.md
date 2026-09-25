# Хранилище

> **Статус:** этап 1 реализован (таблицы ниже с пометкой «этап 1»), остальное — черновик · **Этап:** 1 · **Решение:** [ADR 0004](../adr/0004-storage-sqlite.md) (принято)

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
| `player_stat_daily` · этап 1 | `uuid`, `day`, `key`, `value` | статистика за день для газеты и титулов. На этапе 1 — только `deaths` |
| `title` | `uuid`, `title_id`, `earned_at`, `active` | титулы |

### Персонажи

| Таблица | Поля | Для чего |
|---|---|---|
| `reputation` | `uuid`, `scale_id`, `value` | «Полезность для науки», Федерация, Цитадель |
| `persona_memory` | `uuid`, `persona_id`, `summary`, `updated_at` | сжатая память |
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
| `newspaper` | `date`, `headline`, `body` | архив газет |
| `ai_log` · этап 1 | `id`, `ts`, `day`, `route`, `tag`, `model`, `status`, `input_tokens`, `cached_tokens`, `output_tokens`, `reasoning_tokens`, `latency_ms`, `cost_usd`, `player_uuid`, `output`, `error` | расходы и отладка. Строка на каждую попытку, не на запрос. `tag` — задача (`death`, `join`, `test`…), `status` — `ok`, `invalid`, `refused`, `error`, `timeout`, `late` ([ai-integration](ai-integration.md#отказы-и-ошибки)), `output` — ответ до 4000 символов. По `day` восстанавливается дневной бюджет после перезапуска |
| `ai_report` · этап 1 | `id`, `ts`, `reporter_uuid`, `reporter_name`, `persona_id`, `line`, `line_ts`, `comment`, `resolved` | жалобы `/rick report`: на какую реплику и что не так. Разбор — `/rickadmin report` ([commands](../ops/commands.md)) |

## Хранение и чистка

| Данные | Срок |
|---|---|
| `dialogue_log` | 14 дней (`dialogue_retention_days`), дальше живёт только саммари. Чистка — при старте сервера |
| `ai_log` | 90 дней (`ai_log_retention_days`), чистка при старте |
| `content_pool` | неиспользованное старше 7 дней удаляется |
| остальное | бессрочно |

## Открытые вопросы

- Ванильную статистику игроков (`world/stats/*.json`) читать или считать своё? Ванилла уже считает смерти, убийства и добычу; своё нужно только для «за сутки» и для того, чего ванилла не знает.
