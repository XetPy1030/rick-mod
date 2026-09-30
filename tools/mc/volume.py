"""Постройка как объём: массив индексов палитры (numpy), палитра, блок-сущности, сущности, точки.

Читает структуры NBT (свои и из jar), схематики Sponge .schem v2/v3, .litematic и .npz — постройки,
вырезанные из миров (worlds.py). Индекс −1 — «не задано» (structure_void): при установке блок мира там
не меняется.
"""
import json
from pathlib import Path

import numpy as np

from . import nbt
from .registry import full_id


def parse_state(s):
    """«minecraft:oak_stairs[facing=east,half=bottom]» → (id, свойства)."""
    name, _, rest = s.partition("[")
    props = {}
    if rest:
        for kv in rest.rstrip("]").split(","):
            if "=" in kv:
                k, v = kv.split("=", 1)
                props[k] = v
    return full_id(name), props


class Marker:
    """Точка для кода: структурный блок DATA с metadata «имя:сторона»."""
    __slots__ = ("name", "facing", "pos")

    def __init__(self, name, facing, pos):
        self.name, self.facing, self.pos = name, facing, pos

    def __repr__(self):
        return f"{self.name}:{self.facing}@{self.pos}"


class Volume:
    def __init__(self, size, palette, idx, block_nbt=None, entities=None, name="", data_version=None):
        self.size = tuple(int(v) for v in size)
        self.palette = palette            # [(id, {свойства})]
        self.idx = idx                    # int32 [x, y, z], −1 — не задано
        self.block_nbt = block_nbt or {}  # (x, y, z) → nbt блок-сущности
        self.entities = entities or []    # [{"pos": (x, y, z), "nbt": {...}}]
        self.name = name
        self.data_version = data_version

    # ---------------------------------------------------------------- чтение
    @classmethod
    def load(cls, path):
        path = Path(path)
        return cls.from_bytes(path.read_bytes(), path.stem, path.suffix)

    @classmethod
    def from_bytes(cls, data, name="", suffix=".nbt"):
        if suffix == ".npz":
            return cls._from_npz(data, name)
        root = nbt.read_bytes(data)
        if suffix == ".schem":
            return cls._from_schem(root, name)
        if suffix == ".litematic":
            return cls._from_litematic(root, name)
        return cls.from_structure(root, name)

    @classmethod
    def _from_npz(cls, data, name=""):
        import io
        z = np.load(io.BytesIO(data))
        palette = [(n, p) for n, p in json.loads(str(z["palette"]))]
        return cls(z["idx"].shape, palette, z["idx"].astype(np.int32), name=name)

    def save_npz(self, path):
        """Компактно и быстро — для вырезанных из миров построек; в игру этот формат не идёт."""
        idx = self.idx.astype(np.int16 if len(self.palette) < 32000 else np.int32)
        np.savez_compressed(path, idx=idx, palette=np.array(json.dumps(self.palette)))

    @classmethod
    def _from_litematic(cls, root, name=""):
        """Litematica: несколько регионов со своими палитрами → один объём. Индексы — поток битов
        (как в регионах до 1.16), не меньше 2 бит, порядок x + z·X + y·X·Z; размер бывает отрицательным."""
        from .anvil import unpack
        regs = []
        for r in root["Regions"].values():
            pos = [int(r["Position"][k]) for k in "xyz"]
            size = [int(r["Size"][k]) for k in "xyz"]
            lo = [p + min(0, s + 1) for p, s in zip(pos, size)]
            dims = [abs(s) for s in size]
            pal = [(p["Name"], {k: str(v) for k, v in p.get("Properties", {}).items()}) for p in r["BlockStatePalette"]]
            bits = max(2, (len(pal) - 1).bit_length())
            n = dims[0] * dims[1] * dims[2]
            flat = unpack(r["BlockStates"], bits, spanning=True, n=n)
            regs.append((lo, dims, pal, flat.reshape(dims[1], dims[2], dims[0]).transpose(2, 0, 1)))
        lo = [min(r[0][k] for r in regs) for k in range(3)]
        hi = [max(r[0][k] + r[1][k] for r in regs) for k in range(3)]
        size = [h - l for h, l in zip(hi, lo)]
        palette, keys = [], {}
        idx = np.full(size, -1, np.int32)
        for (rl, dims, pal, a) in regs:
            remap = np.array([keys.setdefault((full_id(n), tuple(sorted(p.items()))), len(keys)) for n, p in pal])
            sl = tuple(slice(rl[k] - lo[k], rl[k] - lo[k] + dims[k]) for k in range(3))
            idx[sl] = remap[a]
        palette = [(n, dict(p)) for (n, p), _ in sorted(keys.items(), key=lambda kv: kv[1])]
        return cls(size, palette, idx, name=name, data_version=root.get("MinecraftDataVersion"))

    @classmethod
    def from_structure(cls, root, name=""):
        size = [int(v) for v in root["size"]]
        pal = root["palette"] if "palette" in root else root["palettes"][0]
        palette = [(full_id(p["Name"]), {k: str(v) for k, v in p.get("Properties", {}).items()}) for p in pal]
        idx = np.full(size, -1, dtype=np.int32)
        block_nbt = {}
        for b in root.get("blocks", []):
            x, y, z = (int(v) for v in b["pos"])
            if not 0 <= b["state"] < len(palette):      # бывают кривые файлы (CTOV computer_lab) — пропускаем блок
                continue
            idx[x, y, z] = b["state"]
            if "nbt" in b:
                block_nbt[(x, y, z)] = b["nbt"]
        ents = []
        for e in root.get("entities", []):
            ents.append({"pos": tuple(float(v) for v in e["pos"]), "nbt": e.get("nbt", {})})
        return cls(size, palette, idx, block_nbt, ents, name, root.get("DataVersion"))

    @classmethod
    def _from_schem(cls, root, name=""):
        s = root.get("Schematic", root)
        w, h, l = int(s["Width"]), int(s["Height"]), int(s["Length"])
        blocks = s.get("Blocks", s)       # v3 — внутри Blocks, v2 — в корне
        pal_map = blocks.get("Palette") or s.get("Palette")
        data = blocks.get("Data") if "Data" in blocks else s.get("BlockData")
        palette = [None] * (max(pal_map.values()) + 1)
        for state, i in pal_map.items():
            palette[i] = parse_state(state)
        palette = [p or ("minecraft:air", {}) for p in palette]
        # Индексы — varint подряд, порядок x + z·W + y·W·L
        vals = []
        cur = shift = 0
        for byte in data:
            cur |= (byte & 0x7F) << shift
            if byte & 0x80:
                shift += 7
            else:
                vals.append(cur)
                cur = shift = 0
        flat = np.array(vals[:w * h * l], dtype=np.int32).reshape(h, l, w)
        idx = np.ascontiguousarray(flat.transpose(2, 0, 1))
        block_nbt = {}
        for be in blocks.get("BlockEntities", s.get("BlockEntities", [])):
            x, y, z = (int(v) for v in be["Pos"])
            block_nbt[(x, y, z)] = be.get("Data", be)
        return cls((w, h, l), palette, idx, block_nbt, [], name, s.get("DataVersion"))

    # ---------------------------------------------------------------- доступ
    def state(self, x, y, z):
        i = self.idx[x, y, z]
        return self.palette[i] if i >= 0 else None

    def markers(self):
        out = []
        for (x, y, z), data in self.block_nbt.items():
            st = self.state(x, y, z)
            if st and st[0] == "minecraft:structure_block" and str(data.get("mode", "")).upper() == "DATA":
                name, _, facing = str(data.get("metadata", "")).partition(":")
                out.append(Marker(name, facing or "south", (x, y, z)))
        return sorted(out, key=lambda m: m.name)

    def counts(self):
        """Сколько блоков каждого id (без воздуха и пустоты)."""
        ids, n = np.unique(self.idx[self.idx >= 0], return_counts=True)
        out = {}
        for i, c in zip(ids, n):
            name = self.palette[i][0]
            if name != "minecraft:air":
                out[name] = out.get(name, 0) + int(c)
        return dict(sorted(out.items(), key=lambda kv: -kv[1]))
