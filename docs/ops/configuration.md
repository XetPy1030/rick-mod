# Конфигурация мода

> **Статус:** проработка · **Этап:** 1

Файл: `config/rickmod.json5`. Перечитывается командой `/rickadmin reload` без перезапуска там, где это безопасно. Ключ API в конфиге не хранится.

## Разделы (черновик)

```json5
{
  protocol_version: 1,

  // Каждая фича включается и выключается отдельно
  features: {
    death_messages: true,
    join_leave: true,
    newspaper: true,
    motd_tab: true,
    rick: true,
    visits: false,
    mini_events: false,
    gadgets: false,
    events: false,
  },

  ai: {
    enabled: true,
    language: "ru",
    daily_budget_usd: 3.0,
    max_concurrent: 4,
    timeout_seconds: 15,
    requests_per_minute: 20,
    batch_hour: 4,                 // ночной батч, час по времени сервера
    routes: {
      dialogue:       { model: "claude-opus-5", effort: "low",    max_tokens: 1024 },
      visit:          { model: "claude-opus-5", effort: "low",    max_tokens: 1024 },
      death_special:  { model: "claude-opus-5", effort: "low",    max_tokens: 512 },
      newspaper:      { model: "claude-opus-5", effort: "medium", max_tokens: 4096 },
      judge:          { model: "claude-opus-5", effort: "high",   max_tokens: 4096 },
      memory_compact: { model: "claude-opus-5", effort: "low",    max_tokens: 1024 },
      pools:          { model: "claude-opus-5", effort: "low",    max_tokens: 4096 },
    },
  },

  content: {
    profanity: true,               // мат разрешён
    max_message_length: 256,
    blocklist: [],                 // стоп-слова для фильтра вывода
  },

  personas: {
    rick: { enabled: true, replies_per_minute: 4 },
    // остальные — по мере появления
  },

  visits: {
    min_minutes_between: 40,
    max_concurrent: 2,
  },

  storage: {
    path: "rickmod/rickmod.db",
    dialogue_retention_days: 14,
  },
}
```

`daily_budget_usd` и модели по маршрутам — значения-заглушки; реальные ставим после решения по бюджету ([вопрос 3](../open-questions.md)).

## Правила

- Любая новая фича получает флаг в `features` и по умолчанию выключена.
- Числа баланса (награды, цены, кулдауны) — в конфиге, а не в коде.
- Неизвестные ключи — предупреждение в лог, не ошибка.
- Невалидный конфиг при `/rickadmin reload` — старый остаётся в силе, ошибка в чат админу.
