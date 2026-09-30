"""Каталог блоков — дамп реестров с копии сервера (/rickdev dump registry → data/registry-26.2.json).

Источник правды для id и свойств: что есть в дампе, то есть в игре с модами сборки.
Пересобрать: tools/testserver/testserver.sh start, cmd "rickdev dump registry",
cp run/full-server/rikoshet/dump/registry.json tools/mc/data/registry-26.2.json.
"""
import json
from functools import lru_cache
from pathlib import Path

DEFAULT = Path(__file__).resolve().parent / "data" / "registry-26.2.json"

# Множители оттенков карты: LOW, NORMAL, HIGH, LOWEST (MapColor.Brightness)
MAP_SHADES = (180, 220, 255, 135)


# Старые id → 26.2 для миров и схематик прошлых версий: то, что игра делает DataFixer'ом при обновлении мира,
# в объёме, нужном рендеру и разбору. Что не нашлось, `refs.py scan` пишет в отчёт — оттуда и пополнять.
RENAMES = {"minecraft:grass": "minecraft:short_grass", "minecraft:grass_path": "minecraft:dirt_path",
           "minecraft:chain": "minecraft:iron_chain"}
# До 1.14 (DataVersion < 1901): таблички были одного дерева, «stone_slab» — нынешняя гладкая плита
RENAMES_1_13 = {"minecraft:sign": "minecraft:oak_sign", "minecraft:wall_sign": "minecraft:oak_wall_sign",
                "minecraft:stone_slab": "minecraft:smooth_stone_slab"}
# До 1.16 стены соединялись true/false, теперь none/low/tall
_WALL_SIDE = {"true": "low", "false": "none"}


def split_id(name):
    """«stone» → («minecraft», «stone»)."""
    ns, _, path = name.rpartition(":")
    return (ns or "minecraft"), path


def full_id(name):
    ns, path = split_id(name)
    return f"{ns}:{path}"


class Registry:
    def __init__(self, path=DEFAULT):
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        self.meta = d["meta"]
        self.data_version = d["meta"]["data_version"]
        self.blocks = d["blocks"]
        self.items = d["items"]
        self.particles = set(d["particles"])
        self.sounds = set(d["sounds"])
        self.entities = set(d["entities"])
        self.biomes = set(d["biomes"])
        self.structures = set(d["structures"])
        self.map_colors = [tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) for c in d["map_colors"]]

    def block(self, name):
        return self.blocks.get(full_id(name))

    def props(self, name, props=None):
        """Все свойства блока: заданные поверх значений по умолчанию. Неизвестный блок — как есть."""
        b = self.block(name)
        out = dict(b.get("default", {})) if b else {}
        if props:
            out.update({k: str(v).lower() for k, v in props.items()})
        return out

    def check(self, name, props=None):
        """Ошибки состояния: неизвестный блок, свойство или значение. Пустой список — всё верно."""
        b = self.block(name)
        if b is None:
            return [f"нет блока {full_id(name)}"]
        errors = []
        known = b.get("props", {})
        for k, v in (props or {}).items():
            if k not in known:
                errors.append(f"{full_id(name)}: нет свойства {k}")
            elif str(v).lower() not in known[k]:
                errors.append(f"{full_id(name)}: {k}={v}, можно {'|'.join(known[k])}")
        return errors

    def upgrade(self, name, props, data_version=None):
        """Состояние из старой версии → (id, свойства) 26.2. Неизвестные свойства и значения отбрасываются —
        блок встанет со значением по умолчанию. Неизвестный блок остаётся как есть."""
        name = full_id(name)
        if data_version is not None and data_version < 1901:
            name = RENAMES_1_13.get(name, name)
        name = RENAMES.get(name, name)
        props = {k: str(v).lower() for k, v in (props or {}).items()}
        if name == "minecraft:cauldron" and props.get("level", "0") != "0":
            name = "minecraft:water_cauldron"
        b = self.block(name)
        if b is None:
            return name, props
        known = b.get("props", {})
        out = {}
        for k, v in props.items():
            if k in known:
                if v not in known[k] and v in _WALL_SIDE and _WALL_SIDE[v] in known[k]:
                    v = _WALL_SIDE[v]
                if v in known[k]:
                    out[k] = v
        return name, out

    def light(self, name, props=None):
        """Свет состояния: по умолчанию, а у горящих (lit=true) — максимум блока."""
        b = self.block(name)
        if not b or "light" not in b:
            return 0
        if props and str(props.get("lit", "")).lower() == "true":
            return b["light_max"]
        return b["light"]

    def solid(self, name):
        """Полный непрозрачный куб: закрывает соседние грани и свет."""
        b = self.block(name)
        return bool(b and b.get("solid"))

    def map_color(self, name, shade=1):
        b = self.block(name)
        base = self.map_colors[b["map"]] if b else (255, 0, 255)
        m = MAP_SHADES[shade]
        return tuple(c * m // 255 for c in base)

    def has_tag(self, name, tag):
        b = self.block(name)
        return bool(b and tag in b.get("tags", ()))


@lru_cache(maxsize=None)
def default():
    return Registry()
