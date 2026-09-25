# Хранилище

> **Статус:** проработка · **Этап:** 1 · **Решение:** [ADR 0004](../adr/0004-storage-sqlite.md) (принято)

## Где и как

- Файл БД: `<папка сервера>/rikoshet/rikoshet.db`. Это не папка мира: данные общие для всех измерений и переживают копии миров для ивентов.
- SQLite в режиме WAL. Все запросы идут через один поток БД, главный поток к БД не обращается.
- Миграции — пронумерованные SQL-файлы в ресурсах мода (`db/migrations/0001_init.sql` …). Текущая версия схемы хранится в таблице `schema_version`.
- Папка `rikoshet/` входит в бэкапы сервера ([server-setup](../ops/server-setup.md#бэкапы)). В ней же лежит файл секретов с ключом OpenRouter ([server-setup](../ops/server-setup.md#ключ-openrouter)), поэтому папку целиком никуда не выкладываем.

## Схема (черновик)

### Игроки

| Таблица | Поля | Для чего |
|---|---|---|
| `player` | `uuid` PK, `name`, `first_seen`, `last_seen`, `playtime_s`, `ai_opt_out`, `voice_opt_out` | профиль, `/rick off`, `/rick voice off`. UUID оффлайновый — от ника с учётом регистра |
| `player_role` | `uuid` PK, `title`, `archetype`, `note`, `set_at` | роль игрока: кто он во вселенной ([player-roles](../design/player-roles.md)) |
| `player_stat_daily` | `uuid`, `date`, `key`, `value` | статистика за день для газеты и титулов: смерти по причинам, добыча, убийства |
| `title` | `uuid`, `title_id`, `earned_at`, `active` | титулы |

### Персонажи

| Таблица | Поля | Для чего |
|---|---|---|
| `reputation` | `uuid`, `scale_id`, `value` | «Полезность для науки», Федерация, Цитадель |
| `persona_memory` | `uuid`, `persona_id`, `summary`, `updated_at` | сжатая память |
| `dialogue_log` | `id`, `uuid`, `persona_id`, `role`, `text`, `ts` | сырая история; хранится N дней |

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
| `ai_log` | `ts`, `route`, `model`, `input_tokens`, `cached_tokens`, `output_tokens`, `latency_ms`, `status`, `cost_usd` | расходы и отладка |

## Хранение и чистка

| Данные | Срок |
|---|---|
| `dialogue_log` | 14 дней, дальше живёт только саммари |
| `ai_log` | 90 дней |
| `content_pool` | неиспользованное старше 7 дней удаляется |
| остальное | бессрочно |

## Открытые вопросы

- Ванильную статистику игроков (`world/stats/*.json`) читать или считать своё? Ванилла уже считает смерти, убийства и добычу; своё нужно только для «за сутки» и для того, чего ванилла не знает.
