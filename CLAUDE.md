# CLAUDE.md

Контекст для ИИ-ассистентов, работающих в этом репозитории.

- Проект: «Рикошет» (`rikoshet`, пакет `ru.xetpy.rikoshet`) — серверный Fabric-мод по «Рику и Морти» для сервера друзей «Vanilla+ 26.2», 18+. Minecraft 26.2, Java 25. Весь кастомный контент — через Polymer, клиентскую сборку не трогаем ([ADR 0001](docs/adr/0001-server-side-only.md)).
- Этап 1 готов и выложен, идёт этап 2: летопись, память, газета ([docs/roadmap.md](docs/roadmap.md)). Перед кодом сверяйся с дорожной картой и [docs/open-questions.md](docs/open-questions.md).
- Проверка на полной копии сервера — [tools/testserver](tools/testserver/README.md); сборка — `./gradlew build` с Java 25.
- Документация на русском. Имена файлов — английский `kebab-case`, идентификаторы контента в коде и конфиге — английский `snake_case`.
- Правила ведения доков — в [docs/README.md](docs/README.md): фича по шаблону из `docs/templates/`, техническое решение с альтернативами — ADR в `docs/adr/`, сырые идеи — в `docs/ideas/backlog.md`.
- Minecraft 26.x не обфусцирован: официальные имена Mojang, плагин `net.fabricmc.fabric-loom` без ремапа, Yarn не используется ([ADR 0002](docs/adr/0002-minecraft-26-2.md)).
- Мир трогаем только из главного потока; ИИ-запросы, аудио и БД — только асинхронно ([overview](docs/architecture/overview.md)).
- ИИ — OpenRouter, модели по маршрутам ([ai-integration](docs/architecture/ai-integration.md)). Всё, что ИИ может сделать в мире, — только через белый список в [ai-actions.md](docs/architecture/ai-actions.md). Новое действие — сначала контракт в доке, потом код.
- Интеграции с модами сервера (Simple Voice Chat, FTB Quests, Origins, EasyAuth, spark) — мягкие, через `FabricLoader.isModLoaded` ([server-integration](docs/architecture/server-integration.md)).

## Сервер и соседний проект

- Сборка сервера лежит в `~/common/projects/mc-fabric-26.2/`: README, `manifest.json` с версиями и sha1, `server/` — локальная копия для тестов, `skins/` — генератор скинов.
- Идентификаторы модового контента (биомы Terralith, предметы Farmer's Delight, команды Origins и т. п.) не пишем по памяти — проверяем запуском копии сервера.
- В `~/common/projects` лежат файлы с учётными данными (`*apikey*`, `*sftp*`). Их не читать, не цитировать и не копировать.

## Секреты

- Ключ OpenRouter — только через переменную окружения `OPENROUTER_API_KEY` или `rikoshet/secrets.json5` на сервере ([server-setup](docs/ops/server-setup.md#ключ-openrouter)). Никогда в git, в конфиге или в архивах сборки.

Поменял поведение в коде — поправь соответствующий документ в том же коммите.

Перед выкладкой — поднять `mod_version` (минорная — этап, патч — исправления) и записать изменения в [CHANGELOG](CHANGELOG.md) ([выкладка](docs/ops/server-setup.md#выкладка-и-обновление-мода)).
