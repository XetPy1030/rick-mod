# Конфигурация мода

> **Статус:** реализовано (этап 1, летопись этапа 2) · **Этап:** 1–2

Файл: `config/rikoshet.json5`. При первом запуске мод кладёт туда конфиг по умолчанию с комментариями ([default-config.json5](../../src/main/resources/rikoshet/default-config.json5)). Перечитывается командой `/rickadmin reload` без перезапуска. Ключ OpenRouter в конфиге не хранится: он в переменной окружения или в `rikoshet/secrets.json5` ([server-setup](server-setup.md#ключ-openrouter)).

## Файлы мода на сервере

| Путь (от папки сервера) | Что это |
|---|---|
| `config/rikoshet.json5` | конфиг |
| `rikoshet/rikoshet.db` | база SQLite ([storage](../architecture/storage.md)) |
| `rikoshet/secrets.json5` | `{ openrouter_api_key: "…" }`, если ключ не в переменной окружения; переменная важнее файла |
| `rikoshet/prompts/…` | переопределения промптов: тот же путь, что в `resources/rikoshet/prompts/` мода, например `rikoshet/prompts/personas/rick.md` |
| `rikoshet/fallback/rick.json` | переопределение заготовок Рика ([flavor](../design/flavor.md#заготовки)) |
| `rikoshet/fallback/newspaper.json` | переопределение рекламы, погоды и пророчеств для газеты без ИИ: `{ ads: [], weather: [], prophecy: [] }` |

Промпты и заготовки перечитываются по `/rickadmin reload`. Схемы ответа ИИ — только из мода: их правка без кода сломает валидатор.

## Конфиг

```json5
{
  protocol_version: 1,

  // Часовой пояс для дневного бюджета, суточной статистики и газеты
  timezone: "Europe/Moscow",

  features: {
    rick: true,                    // Рик комментирует события в чате
    death_messages: true,          // комментарии к смертям
    join_leave: true,              // приветствия и прощания
    chronicle: false,              // летопись: что игроки делали за день — основа газеты
    newspaper: false,              // утренняя газета, нужна летопись
    motd_tab: false,               // этап 2
    roles_in_tab: false,           // «Ник · Роль» в таблисте
    voice: false,                  // этап 3, нужен Simple Voice Chat
    visits: false,
    mini_events: false,
    gadgets: false,
    events: false,
  },

  ai: {
    enabled: true,                 // false — только заготовки
    base_url: "https://openrouter.ai/api/v1",
    daily_budget_usd: 2.0,         // выше — живые запросы выключаются до полуночи
    max_concurrent: 4,             // параллельных запросов
    requests_per_minute: 20,       // живых запросов в минуту на сервер
    timeout_seconds: 15,           // таймаут для маршрутов без своего
    reasoning_effort: "low",       // размышления для маршрутов без своего
    routes: {
      flavor:    { models: ["deepseek/deepseek-v4.1-flash", "openai/gpt-6-luna"], reasoning: "none", max_tokens: 1024, timeout_seconds: 5 },
      dialogue:  { models: ["openai/gpt-6-sol", "x-ai/grok-4.7"], reasoning: "low", max_tokens: 1536, timeout_seconds: 15 },
      newspaper: { models: ["anthropic/claude-opus-5.5", "moonshotai/kimi-k3"], reasoning: "low", max_tokens: 4096, timeout_seconds: 120 },
      pools:     { models: ["moonshotai/kimi-k3", "anthropic/claude-sonnet-5"], reasoning: "low", max_tokens: 3072, timeout_seconds: 120 },
      // Ночная аналитика для других моделей: план номера газеты, дневник дня
      analyst:   { models: ["deepseek/deepseek-v4.1-flash", "openai/gpt-6-luna"], reasoning: "low", max_tokens: 2048, timeout_seconds: 90 },
    },
  },

  flavor: {
    death_cooldown_seconds: 20,    // смерти игрока чаще — без комментария
    series_window_minutes: 10,     // окно для «серии смертей»
    leave_delay_seconds: 15,       // прощание ждёт: вдруг игрок сразу переподключится
    rejoin_quiet_minutes: 3,       // вернулся раньше — без приветствия
  },

  content: {
    max_message_length: 256,       // длиннее — обрезается по концу фразы
    blocklist: [],                 // стоп-слова: реплика с ними не выводится
  },

  performance: {
    mspt_soft: 35,                 // выше — новые мини-события и визиты не начинаются
    mspt_hard: 45,                 // выше — живые запросы флейвора заменяются заготовками
    recover_seconds: 60,           // столько секунд ниже порога — и всё возвращается
  },

  storage: {
    path: "rikoshet/rikoshet.db",  // относительно папки сервера; меняется только с перезапуском
    dialogue_retention_days: 14,
    ai_log_retention_days: 90,
  },

  chronicle: {
    snapshot_minutes: 5,           // как часто снимать ванильную статистику игрока (1–30)
    sample_seconds: 15,            // как часто замерять, где игрок и что с ним (5–120)
  },

  newspaper: {
    hour: 7,                       // час выхода по timezone; выпуск — за вчерашний день
    max_facts: 25,                 // сколько фактов дня получает редакция
  },
}
```

Модели выбраны на [кастинге](../architecture/ai-integration.md#кастинг-моделей). `daily_budget_usd: 2.0` — принятый дневной бюджет ([№ 16](../open-questions.md)).

### Ключи, которые легко понять неправильно

- **`routes.<маршрут>.models`** — первая модель основная, дальше запасные; их по очереди перебирает мод, а не OpenRouter ([ai-integration](../architecture/ai-integration.md#запасные-модели-и-отказы)). Суффикс `@effort` задаёт размышления одной модели: `"google/gemini-3.8-flash@minimal"`.
- **`reasoning`** — `none`, `minimal`, `low`, `medium`, `high` или `default`; при `default` параметр в запрос не пишется.
- **`routes.<маршрут>.timeout_seconds`** — срок на все попытки вместе, а не на одну. Флейвор за 5 с не успел — выводится заготовка.
- **`daily_budget_usd`** — считается по фактической стоимости из ответа OpenRouter за сутки в `timezone`. После перезапуска расход восстанавливается из `ai_log`.
- **`leave_delay_seconds`** — 0–300. Прощание планируется по таймеру, а не по тикам, поэтому приходит и тогда, когда пустой сервер стоит на паузе (`pause-when-empty-seconds`).
- **`storage.path`** — `/rickadmin reload` его не меняет, только перезапуск.
- **`features.chronicle`** — выключение через `/rickadmin reload` закрывает сессии летописи, собранное остаётся. Включение начинает следить за теми, кто онлайн ([chronicle](../design/chronicle.md)).
- **`chronicle.snapshot_minutes`** — окно, в пределах которого летопись знает время события («в окне 21:35–21:40 добыл 3 алмазной руды»); занятие определяется по окну. Меньше — точнее, но больше строк в БД.
- **`chronicle.sample_seconds`** — шаг замеров времени по биомам, AFK, «рядом с кем». AFK засчитывается после 4 неподвижных замеров подряд.
- **`newspaper.max_facts`** — сколько фактов получает редакция, если плана номера от аналитика нет, и сколько показывает `/rickadmin chronicle day`; не больше 4 на игрока. Аналитик читает до 80.
- **`newspaper.hour`** — час выхода по `timezone`. Выпуск — за вчера; нужен и `features.chronicle`. Сервер был выключен в этот час — выпуск через минуту после старта.
- **`routes.analyst`** — ночная аналитика: план номера газеты и дневник дня ([каскад](../architecture/ai-integration.md#каскад-моделей)). Не ответил — газета идёт по обычной сводке, дневника нет.

## Зарезервировано на следующие этапы

Эти ключи мод знает и не ругается на них, но пока не читает: `ai.language`, `ai.batch_hour`, `content.profanity`, разделы `voice`, `personas`, `visits`. Маршруты `visit`, `judge`, `voice_in`, `voice_out` появятся со своими фичами:

```json5
routes: {
  visit:          { models: ["deepseek/deepseek-v4.1-flash", "openai/gpt-6-luna"], reasoning: "none", max_tokens: 1024 },
  judge:          { models: ["anthropic/claude-opus-5.5", "moonshotai/kimi-k3"], reasoning: "low", max_tokens: 4096 },
  voice_in:       { models: ["google/gemini-3.8-flash@minimal"], max_tokens: 1024 },
  voice_out:      { models: ["openai/gpt-audio-mini"] },
},
voice: {
  radius_blocks: 16,             // сколько слышно голос NPC
  max_utterance_seconds: 15,
  silence_end_ms: 700,           // пауза, после которой фраза считается законченной
  tts_timeout_seconds: 5,        // не успели озвучить — остаётся текст
  voices: { rick: { voice: "ash", style: "хриплый, пьяный, говорит быстро, рыгает посреди фраз" } },
},
personas: { rick: { enabled: true, replies_per_minute: 4 } },
visits: { min_minutes_between: 40, max_concurrent: 2 },
```

Модели этих маршрутов — гипотеза до своего этапа. Имя голоса `ash` — пример: список голосов берём из документации модели на кастинге.

## Флаги запуска

- **`-Drikoshet.dev=true`** — включает `/rickdev` для симуляции событий фейковыми игроками ([commands](commands.md#отладка)). На боевом сервере не ставим. Его ставит [tools/testserver](../../tools/testserver/README.md).

## Правила

- Любая новая фича получает флаг в `features` и по умолчанию выключена.
- Числа баланса (награды, цены, кулдауны) — в конфиге, а не в коде.
- Неизвестные ключи — предупреждение в лог, не ошибка. Значение вне допустимого диапазона или неверного типа — ошибка.
- Невалидный конфиг при `/rickadmin reload` — старый остаётся в силе, ошибка в чат админу.
- Невалидный конфиг при старте — действуют значения по умолчанию, ошибки видны в логе и в `/rickadmin ai status`.
