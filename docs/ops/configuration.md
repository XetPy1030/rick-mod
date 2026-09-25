# Конфигурация мода

> **Статус:** проработка · **Этап:** 1

Файл: `config/rikoshet.json5`. Перечитывается командой `/rickadmin reload` без перезапуска там, где это безопасно. Ключ OpenRouter в конфиге не хранится: он в переменной окружения или в `rikoshet/secrets.json5` ([server-setup](server-setup.md#ключ-openrouter)).

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
    roles_in_tab: false,           // «Ник · Роль» в таблисте
    voice: false,                  // озвучка и голосовой ввод, нужен Simple Voice Chat
    visits: false,
    mini_events: false,
    gadgets: false,
    events: false,
  },

  ai: {
    enabled: true,
    language: "ru",
    base_url: "https://openrouter.ai/api/v1",
    daily_budget_usd: 2.0,         // выше — живые запросы выключаются до полуночи
    max_concurrent: 4,
    timeout_seconds: 15,
    requests_per_minute: 20,
    batch_hour: 4,                 // ночной батч, час по времени сервера
    // models: первая — основная, дальше запасные; OpenRouter переключается сам
    routes: {
      dialogue:       { models: ["anthropic/claude-sonnet-5", "x-ai/grok-4.7", "google/gemini-3.8-flash"], max_tokens: 1024 },
      visit:          { models: ["google/gemini-3.8-flash", "deepseek/deepseek-v4.1-flash"], max_tokens: 512 },
      death_special:  { models: ["google/gemini-3.8-flash", "deepseek/deepseek-v4.1-flash"], max_tokens: 512 },
      newspaper:      { models: ["anthropic/claude-opus-5", "anthropic/claude-sonnet-5"], max_tokens: 4096 },
      judge:          { models: ["anthropic/claude-opus-5", "anthropic/claude-sonnet-5"], max_tokens: 4096 },
      pools:          { models: ["google/gemini-3.8-flash:batch", "deepseek/deepseek-v4.1-flash:batch"], max_tokens: 4096 },
      memory_compact: { models: ["google/gemini-3.8-flash:batch", "deepseek/deepseek-v4.1-flash:batch"], max_tokens: 1024 },
      voice_in:       { models: ["google/gemini-3.8-flash"], max_tokens: 1024 },
      voice_out:      { models: ["openai/gpt-audio-mini"] },
    },
    reasoning_effort: "low",       // для всех маршрутов, если не задано в маршруте
  },

  voice: {
    radius_blocks: 16,             // сколько слышно голос NPC
    max_utterance_seconds: 15,
    silence_end_ms: 700,           // пауза, после которой фраза считается законченной
    tts_timeout_seconds: 5,        // не успели озвучить — остаётся текст
    voices: {
      rick: { voice: "ash", style: "хриплый, пьяный, говорит быстро, рыгает посреди фраз" },
      // голоса остальных — после кастинга
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

  performance: {
    mspt_soft: 35,                 // выше — новые мини-события и визиты не начинаются
    mspt_hard: 45,                 // выше — пауза фоновых фич
    recover_seconds: 60,
  },

  storage: {
    path: "rikoshet/rikoshet.db",
    dialogue_retention_days: 14,
  },
}
```

Модели по маршрутам — гипотеза до [кастинга](../architecture/ai-integration.md#кастинг-моделей), `daily_budget_usd: 2.0` — принятый дневной бюджет ([№ 16](../open-questions.md)). Имя голоса `ash` — пример: список голосов берём из документации модели на кастинге.

## Правила

- Любая новая фича получает флаг в `features` и по умолчанию выключена.
- Числа баланса (награды, цены, кулдауны) — в конфиге, а не в коде.
- Неизвестные ключи — предупреждение в лог, не ошибка.
- Невалидный конфиг при `/rickadmin reload` — старый остаётся в силе, ошибка в чат админу.
