# tools/citadel

Постройки Цитадели — NBT-структуры в `src/main/resources/data/rikoshet/structure/citadel/` ([worlds](../../docs/architecture/worlds.md#цитадель)).

```sh
python3 tools/citadel/build.py   # → lab.nbt; без внешних библиотек
```

- `nbt.py` — запись и чтение NBT (gzip, big-endian).
- `build.py` — лаборатория Рика и площадь, простая версия этапа 3. Точки для кода — структурные блоки DATA с `metadata` «имя:сторона»: `rick`, `spawn`, `portal_back`.

`DATA_VERSION` — `world_version` из `version.json` jar-а Minecraft (26.2 — 4903). Блоки модов (Voxelized Furniture) — только по списку из jar сборки: чего нет в игре, станет воздухом.

Лабораторию, перестроенную в игре, мод сохраняет сам — `/rickadmin citadel save`, этот скрипт для этого не нужен.
