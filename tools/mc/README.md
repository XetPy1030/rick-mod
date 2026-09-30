# tools/mc

Общая библиотека визуального конвейера ([visual-pipeline](../../docs/architecture/visual-pipeline.md)): NBT, каталог блоков, ассеты, модели блоков, растеризатор, линтер, миры и разбор образцов. Нужны Python 3.11, Pillow и numpy; для сегментации образцов — scipy.

```sh
python3 tools/render.py src/main/resources/data/rikoshet/structure/citadel/lab.nbt   # лист ракурсов + вид с точки spawn
python3 tools/render.py lab.nbt --views cut,eye:spawn --light game --scale 24
python3 tools/render.py lab.nbt --slices                                           # планы этажей
python3 tools/render.py src/main/resources/assets/rikoshet/textures/entity/npc/rick.png   # лист скина
python3 tools/lint.py lab.nbt                                                      # линтер, код выхода 1 при ошибках
python3 tools/refs.py index && python3 tools/refs.py gallery minecraft:trial_chambers    # образцы из jar сборки
python3 tools/refs.py scan all && python3 tools/refs.py review greenfield: --todo        # миры, разметка
```

Всё, что рисуется, — в `run/visual/` (вне git).

## Откуда ассеты

Слоями, как у клиента: наш пак `src/main/resources/assets` → jar модов сборки (`~/common/projects/mc-fabric-26.2/server/mods`, в том числе вложенные) → клиент 26.2 из кеша Loom (`~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar`, появляется после `./gradlew build`). Нет кеша Loom — клиент качается по манифесту Mojang со сверкой sha1 в `run/visual/cache/`. Пути меняются переменными `RIKOSHET_MODS` и `RIKOSHET_MC_CLIENT`. Для образцов (`refs.py`) после модов сборки идут скачанные jar из `run/refs/jars/`.

## Каталог блоков

`data/registry-26.2.json` — дамп с копии сервера: 3158 блоков со свойствами, тегами, светом, цветом карты; id предметов, частиц, звуков, сущностей, биомов и 1628 структур. Пересобрать, когда меняется `manifest.json` сборки:

```sh
tools/testserver/testserver.sh start
tools/testserver/testserver.sh cmd "rickdev dump registry"
cp run/full-server/rikoshet/dump/registry.json tools/mc/data/registry-26.2.json
```

## Модули

| Модуль | Что |
|---|---|
| `nbt.py` | чтение и запись NBT: gzip, zlib, без сжатия; большие массивы — numpy |
| `registry.py` | каталог: проверка id и свойств, значения по умолчанию, свет, цвет карты, теги; перевод старых id и свойств в 26.2 |
| `assets.py` | ассеты слоями, текстуры (первый кадр анимации), режим альфы: opaque, cutout, translucent |
| `models.py` | blockstate → модель → грани: variants, multipart, parent, повороты элементов и модели, uvlock, `force_translucent` |
| `volume.py` | постройка объёмом: структура NBT, `.schem`, `.litematic`, `.npz` (вырезанное из миров); точки из структурных блоков DATA |
| `raster.py` | растеризатор на numpy: ортографическая и перспективная камера, буфер глубины, полупрозрачность |
| `scene.py` | грани построек с отсечением скрытых, свет `flat` и `game`, манекены со скином по box-UV |
| `views.py` | виды, лист ракурсов, отметки точек, вид глазами игрока |
| `skinview.py` | лист скина: ракурсы, развёртка, вид с расстояния в экранном размере |
| `lint.py` | линтер построек |
| `kinds.py` | что рукотворное — порт `BlockKinds` мода; семейство материала и форма блока |
| `anvil.py` | регионы `.mca`: палитры с 1.13 (и сплошной поток битов до 1.16, ширина индекса — по длине данных), числовые id до 1.13 — по `data/legacy-1.12.json` (PrismarineJS/minecraft-data, MIT) |
| `remote.py` | файл по HTTP Range как локальный — zip мира без скачивания целиком; MediaFire |
| `worlds.py` | скан мира: застройка по чанкам, вычитание сгенерированного игрой, кластеры, вырезка, оценка |
| `segment.py` | плитки мира → отдельные постройки по связности на сетке 4³ (scipy); дороги не связывают; городской режим; перенос ручных меток при пересегментации |
| `sources.py` | реестр источников `tools/refs/sources.json`, бюджет трафика, Modrinth |
| `classify.py` | разметка: тип, стиль, масштаб, среда, зоны; ручные метки; модель стиля |
| `patterns.py` | стили (k-means), сочетания материалов, ярусы, мотивы 3×3×3 |

## Чего пока нет

Сундуки, кровати, таблички и головы рисуются кубом цвета карты — у них нет модели, их рисует код игры. Нет мягкого освещения (AO), частиц, облёта GIF, дифа версий — по плану [visual-pipeline](../../docs/architecture/visual-pipeline.md). Миры Bedrock (`.mcworld`, LevelDB) не читаются.
