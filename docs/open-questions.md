# Вопросы и решения

Когда вопрос решён: пишем ответ, ставим статус «решено» и переносим суть в нужный документ или ADR.

## Открытые

| # | Вопрос | Варианты и рекомендация | Нужно до | Статус |
|---|---|---|---|---|
| 17 | Голос персонажей: хватит ли качества `gpt-audio-mini` на русском? | Прослушать на кастинге моделей. Запасной вариант — отдельный TTS-сервис ([voice](design/voice.md)). | этапа 3 | открыт |
| 19 | Роль «Даен»: кто это? | Не нашёл в каноне. Нужно описание в 1–2 фразы — пойдёт в заметку роли ([player-roles](design/player-roles.md)). | — | открыт, не блокирует |

## Решённые (2026-09-26)

| # | Вопрос | Решение | Где отражено |
|---|---|---|---|
| 1 | Игроки и онлайн | Пиковый онлайн — 8 человек. | [vision](vision.md), [ai-integration](architecture/ai-integration.md#стоимость) |
| 2 | Железо и хостинг | Хостинг Millida: 3 ядра (модель CPU неизвестна), 8 ГБ RAM, диск 120 ГБ (занято ~4 ГБ). | [server-integration](architecture/server-integration.md#сервер) |
| 3 | ИИ-провайдер | OpenRouter, несколько моделей по маршрутам. | [ADR 0003](adr/0003-ai-provider-and-protocol.md), [ai-integration](architecture/ai-integration.md) |
| 4 | Хранилище | SQLite. | [ADR 0004](adr/0004-storage-sqlite.md) |
| 5 | Версия игры | 26.2. | [ADR 0002](adr/0002-minecraft-26-2.md) |
| 6 | Название и `mod_id` | **«Рикошет»**, `rikoshet`: «Рик» внутри, отскакивает во все стороны. Автор — XetPy, пакет `ru.xetpy.rikoshet`. | [README](../README.md) |
| 7 | Предупреждать игроков о внешнем ИИ | Нет. | [content-policy](ops/content-policy.md) |
| 8 | Язык ИИ | Русский, имена и термины — как в русском дубляже. | [ai-integration](architecture/ai-integration.md) |
| 9 | Как говорить с NPC | ПКМ по NPC начинает разговор на 60 с, реплики из чата идут ему. Голосом — через Simple Voice Chat. | [characters](design/characters/README.md#разговор), [voice](design/voice.md) |
| 10 | Внешний вид NPC | Человекоподобные — манекены со сгенерированными скинами из ресурспака; необычные существа — модели из display-сущностей. | [characters](design/characters/README.md#внешний-вид), [content-delivery](architecture/content-delivery.md) |
| 11 | Имена персонажей | Канонические. | [characters](design/characters/README.md) |
| 12 | Шмекели | Монета-предмет плюс журнал в БД. | [economy](design/economy.md#шмекели) |
| 13 | Запуск ивентов | Админ назначает дату, анонс за сутки. | [events](design/events/README.md) |
| 14 | Копия мира для апокалипсиса | Сначала спайк с копией файлов регионов; если не взлетит — мир с тем же сидом (генерация с Terralith детерминирована, рельеф совпадёт). | [worlds](architecture/worlds.md#копия-мира) |
| 15 | Права | Оп только у админа (XetPy), у игроков прав нет. Хватает уровней op, LuckPerms не нужен. | [commands](ops/commands.md#права) |
| 16 | Потолок расходов на ИИ | Устраивает оценка ~$35 в месяц. Дневной бюджет мода — $2, выше живые запросы выключаются до полуночи. Сверху — лимит на ключ в кабинете OpenRouter. | [ai-integration](architecture/ai-integration.md#лимиты-и-бюджет), [server-setup](ops/server-setup.md#ключ-openrouter) |
| 18 | Лаунчеры игроков | На Prism не переходят: у всех разные лаунчеры, `tlskincape` ни у кого не будет. NPC всё равно делаем манекенами — они не зависят от лаунчера. | [server-integration](architecture/server-integration.md#скины) |
