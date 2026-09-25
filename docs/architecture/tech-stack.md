# Технический стек

> **Статус:** проработка · **Этап:** 1 · **Версии проверены:** 2026-09-26

## Платформа

| Компонент | Версия | Примечание |
|---|---|---|
| Minecraft | **26.2** | 26.3 уже вышел, решение — в [ADR 0002](../adr/0002-minecraft-26-2.md) |
| Java | **25** | обязательна с 26.1, в том числе для JVM Gradle |
| Gradle | 9.5.1+ | |
| Fabric Loom | 1.17.21 | плагин `net.fabricmc.fabric-loom`, без ремапа |
| Fabric Loader | 0.19.5 | последний stable |
| Fabric API | 0.161.0+26.2 | |
| IDE | IntelliJ IDEA 2025.3+ | более старые версии не понимают миксины на 26.x |

## Что изменилось в 26.x для разработки

- **Игра не обфусцирована** (с 26.1). Используются официальные имена Mojang. Yarn больше не поддерживается Fabric.
- **Новый плагин Loom:** `net.fabricmc.fabric-loom` не ремапит ни игру, ни моды. Вместо `modImplementation` — обычный `implementation`, вместо `remapJar` — `jar`.
- **Fabric API переименован под Mojang-имена** (например, `ItemGroupEvents` → `CreativeModeTabEvents`). Туториалы до 26.1 читать с поправкой.
- **26.2:** id блоков и предметов хранятся отдельно (`BlockIds`, `BlockItemIds`, `ItemIds`), `valueLookupBuilder` удалён. Это касается регистрации контента, поэтому регистрируем по примерам Polymer под 26.2.
- **26.2:** в Loader 0.19 и Loom 1.17 появился API расширения enum'ов.
- Клиентский рендер (Vulkan, Blaze3D) нас не касается: мод серверный.

## Зависимости мода

Все зависимости серверные. Библиотеки, которых нет на сервере, вкладываются в jar мода (jar-in-jar через `include`).

| Библиотека | Версия под 26.2 | Зачем | Этап |
|---|---|---|---|
| Fabric API | 0.161.0+26.2 | события, команды, регистрация, лут-таблицы | 1 |
| Polymer (core, resource-pack, autohost, virtual-entity) | 0.17.5+26.2 | кастомные предметы, блоки, сущности; ресурспак | 4 |
| sgui | 2.1.0+26.2 | серверные GUI на сундуках и книгах: магазин, меню, голосование | 3–5 |
| Fantasy | 0.8.3+26.2 | runtime-измерения для арен и копий мира | 6–7 |
| Text Placeholder API | 3.1.0-beta.1+26.2 | плейсхолдеры для MOTD, таблиста, чата | 2 |
| SQLite JDBC (xerial) | 3.53.4.0 | хранилище ([ADR 0004](../adr/0004-storage-sqlite.md)); вложен в jar мода, отсюда его размер ~12 МБ — нативные библиотеки под все платформы | 1 |

ИИ-запросы к OpenRouter — через `java.net.http.HttpClient` из JDK и Gson, который уже есть в Minecraft: отдельных библиотек не нужно ([ADR 0003](../adr/0003-ai-provider-and-protocol.md)).

### API модов сервера (только компиляция)

Эти моды уже стоят на сервере, мы подключаем только их API и работаем, лишь если мод загружен ([server-integration](server-integration.md)).

| API | Зачем | Этап |
|---|---|---|
| `de.maxhenkel.voicechat:voicechat-api` | голоса персонажей, разговор голосом | 3, 5 |
| `me.lucko:spark-api` | текущий MSPT для адаптивной нагрузки | 1 |
| Origins: Legacy, FTB Quests, EasyAuth | раса игрока, мост квестов, событие входа — способ подключения уточнить по исходникам | 1–3 |

Репозитории Maven: `maven.fabricmc.net` для Fabric, `maven.nucleoid.xyz` для Polymer, sgui, Fantasy и Placeholder API, `maven.maxhenkel.de/repository/public` для Voice Chat.

Text Placeholder API под 26.2 пока в бете. Если это станет проблемой — MOTD и таблист можно собрать напрямую через ванильные пакеты.

## Серверные утилиты

Не зависимости мода, а просто стоят на сервере рядом ([server-setup](../ops/server-setup.md)).

| Мод | Версия | Зачем |
|---|---|---|
| Chunky | 1.5.3 | прегенерация основного мира и арен |
| spark | 1.10.187 | профилирование CPU, памяти, TPS |

Остальные моды сервера и что мы с ними делаем — в [server-integration](server-integration.md).

## Риски

- **Размер jar.** SQLite тянет нативные библиотеки под все платформы, это несколько МБ. Проверить на этапе 1; при желании оставить только Linux x64 — платформу хостинга.
- **Бета Placeholder API** — см. выше.
- **Переход на 26.3** — все библиотеки уже вышли под 26.3, порт должен быть механическим, но проверяется отдельно.

## Источники

- [Fabric for Minecraft 26.1](https://fabricmc.net/2026/03/14/261.html) — деобфускация, новый Loom, Java 25
- [Fabric for Minecraft 26.2](https://fabricmc.net/2026/06/15/262.html) — Loom 1.17, Gradle 9.5.1, изменения регистрации
- [Polymer](https://github.com/Patbox/polymer), документация: [polymer.pb4.eu](https://polymer.pb4.eu)
- Версии библиотек — Modrinth API и `maven.nucleoid.xyz`, на 2026-09-26
