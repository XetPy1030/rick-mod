# tools/build

Генератор построек ([visual-pipeline](../../docs/architecture/visual-pipeline.md#генератор-построек-toolsbuild)): сцена из форм, кистей и префабов → структура NBT, `layout.json`, лист рендера, отчёт линтера. Нужны Python 3.11, numpy, Pillow.

```sh
python3 tools/build citadel/lab            # → src/main/resources/data/rikoshet/structure/citadel/lab.nbt
python3 tools/build demo/showcase          # демо: купол со сглаживанием и без → run/visual/build/
python3 tools/build citadel/lab --no-render --no-lint
```

Сцена — модуль в `scenes/` с функцией `build()`, которая возвращает `Scene`. Запись, рендер (`tools/render.py`) и линтер (`tools/lint.py`) запускает `__main__`.

```python
from build import Scene, brush, shape

def build():
    s = Scene("citadel/plaza", size=(96, 40, 96), seed=26)
    hull = brush.gradient(["white_concrete", "calcite", "quartz_block"], axis="y", noise=0.15)
    s.fill(shape.cylinder((48, 0, 48), r=40, h=2) - shape.cylinder((48, 0, 48), r=6, h=2), hull, smooth=True)
    s.fill(shape.sphere((48, 2, 48), 14).cut(y0=2), brush.edges(hull, "iron_block"), smooth=True, hollow=1)
    s.fill(shape.arch((48, 2, 62), width=5, height=6, depth=4), "air")
    s.radial(8, lambda f: f.prefab(lamp_post, at=(0, 2, 34), facing="north"), center=(48, 48))
    s.marker("rick", (48, 2, 44), "south")
    s.display("rikoshet:citadel/space_cruiser", (40, 26, 20), scale=3, yaw=30)   # → layout.json
    s.lights(target=11)
    return s
```

| Модуль | Что |
|---|---|
| `scene.py` | `Scene`: блоки и коробки, `fill` форм кистью (`smooth` — края ступенями и плитами, `hollow` — оболочка без дыр), префабы (функция, NBT из `prefabs/`, чужая структура по id → `layout.json`), `radial`, точки, display-декор, невидимый свет `lights`, запись |
| `shape.py` | формы SDF: `box`, `sphere`, `ellipsoid`, `cylinder`, `cone`, `torus`, `capsule`, `pipe`, `arch`, `prism`, `regular`; `\|`, `-`, `&`, `.smooth`, `.shell`, `.inner`, `.cut`, `.move`, `.rot_y` |
| `brush.py` | кисти: `solid`, `mix` (пятнами — `clump`), `gradient` (ось, радиус, глубина), `noise`, `pattern`, `checker`, `by_normal`, `edges`, `layers`, `greeble`, `glass_tube`; по цвету — `ramp`, `nearest`; из доски зоны — `from_palette` |
| `states.py` | поворот и отражение состояний (как `StructureTemplate`), соединения ступеней, заборов, стен, панелей (как `updateShape`) |
| `scenes/` | сцены: `citadel/lab` — лаборатория этапа 3 (перенесена из `tools/citadel/build.py`, файл совпадает байт в байт), `demo/showcase` |
| `prefabs/` | свои префабы NBT |
| `palettes/` | палитры зон, выведенные из образцов: `python3 tools/refs.py board lab --export` |

**Точки для кода** — структурные блоки DATA с `metadata` «имя:сторона» (`s.marker`): `rick`, `spawn`, `portal_back`. Мод находит их после установки и заменяет воздухом. Лабораторию, перестроенную в игре, мод сохраняет сам (`/rickadmin citadel save`).

**Детерминизм.** Шум — от `seed` и позиции, блоки пишутся по (y, z, x), палитра — в порядке первого применения, gzip без времени. Тот же код — тот же файл байт в байт.

**Правила.** `DATA_VERSION` — `world_version` из `version.json` jar Minecraft (26.2 — 4903). Блоки модов — только из каталога `tools/mc/data/registry-26.2.json`: чего нет в игре, станет воздухом, линтер это ловит. Чужие постройки (образцы из `tools/refs.py`) в префабы не попадают — только их статистика через `palettes/`.

**Пока нет:** оверлеи ручных правок (`build pull`), нарезка больших сцен на куски, чтение `layout.json` модом — по плану [visual-pipeline](../../docs/architecture/visual-pipeline.md).
