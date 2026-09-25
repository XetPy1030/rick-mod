# tools/testserver

Полная копия сервера Vanilla+ 26.2 с модом «Рикошет» — для проверки перед выкладкой: интеграции (EasyAuth, моды сборки), id модового контента, нагрузка.

Нужны: Java 25, `rsync`, `python3` (для `profile`), сборка сервера в `~/common/projects/mc-fabric-26.2/server/`.

## Команды

```sh
tools/testserver/testserver.sh prepare        # копия сборки в run/full-server + свежий jar мода
tools/testserver/testserver.sh start          # запуск с -Drikoshet.dev=true, ждёт «Done»
tools/testserver/testserver.sh cmd "<команда>"   # команда в консоль, выводит новые строки лога
tools/testserver/testserver.sh log [regex] [строк]  # поиск по логу; по умолчанию строки мода и ошибки
tools/testserver/testserver.sh profile [секунд]     # вклад мода в тик (JFR), по умолчанию 60 с
tools/testserver/testserver.sh stop
```

`prepare` берёт всё из исходной копии, кроме `EasyAuth/` (хеши паролей), `logs/`, `crash-reports/` и `rikoshet/`. Исходник не меняется: его заливают на хостинг. БД мода в `run/full-server/rikoshet/` переживает повторный `prepare`.

## Переменные

| Переменная | По умолчанию | Что |
|---|---|---|
| `OPENROUTER_API_KEY` | — | ключ для живых запросов; без него — только заготовки. Для `start` |
| `WAIT` | 2 | сколько секунд `cmd` ждёт вывод |
| `MEM` | 4G | `-Xms` и `-Xmx` |
| `PAUSE_EMPTY` | как в сборке (60) | `pause-when-empty-seconds` копии; `0` — для `profile`. Для `prepare` |
| `RIKOSHET_SERVER_SRC`, `RIKOSHET_TEST_DIR` | см. скрипт | откуда копировать и куда |

Ключ передаём только через окружение, в файлы копии не пишем:

```sh
OPENROUTER_API_KEY=… tools/testserver/testserver.sh start
env -u OPENROUTER_API_KEY tools/testserver/testserver.sh start   # проверить путь без ключа
```

## Сценарий без клиента

`/rickdev` работает от фейковых игроков ([commands](../../docs/ops/commands.md#отладка)):

```sh
T=tools/testserver/testserver.sh
WAIT=4 $T cmd "rickdev join dimon_228"
WAIT=4 $T cmd "rickdev death dimon_228 minecraft:explosion minecraft:creeper"
WAIT=20 $T cmd "rickdev leave dimon_228"   # прощание через leave_delay_seconds
$T cmd "rickadmin ai status"
```

Фейковые игроки не входят в список игроков, поэтому через минуту пустой сервер встаёт на паузу. Для событий это не мешает — прощания и ответы ИИ приходят и на паузе.

## Замер нагрузки

```sh
PAUSE_EMPTY=0 $T prepare && $T start
# в другом терминале — поток событий через $T cmd …
$T profile 60
```

`profile` пишет JFR с выборкой раз в 1 мс (`run/full-server/logs/profile.jfr`) и через `jfr-share.py` считает, сколько выборок главного потока пришлось на код мода, отдельно — на сам `/rickdev`. `spark profiler` для этого не используем: он выгружает отчёт на внешний сайт. Результаты замеров — в [performance](../../docs/architecture/performance.md#замер-этапа-1).

Живой клиент (вход с паролем EasyAuth, вид чата) проверяется подключением к `localhost:25565`.
