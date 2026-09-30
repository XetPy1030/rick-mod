"""Миры: регионы Anvil (.mca) с 1.13 — чанки, секции, блоки.

Два формата секций:
- с 1.18 (DataVersion ≥ 2844): chunk["sections"][i]["block_states"] = {palette, data};
- 1.13–1.17: chunk["Level"]["Sections"][i] = {Palette, BlockStates}; до 20w17a (DataVersion < 2527)
  значения идут сплошным потоком битов через границы long.
До 1.13 (DataVersion < 1451) — числовые id: Blocks (+ Add) и Data по полбайта; они переводятся в состояния 1.13
таблицей data/legacy-1.12.json (PrismarineJS/minecraft-data, MIT). Дальше старые id и свойства приводятся к 26.2
через Registry.upgrade.
"""
import json
import re
import struct
from functools import lru_cache
from pathlib import Path

import numpy as np

from . import nbt
from .volume import parse_state

REGION_RE = re.compile(r"(?:^|/)((?:DIM-?1/)?|dimensions/[^/]+/[^/]+/)region/r\.(-?\d+)\.(-?\d+)\.mca$")
DIMS = {"": "overworld", "DIM-1/": "nether", "DIM1/": "end"}


def region_member(path):
    """Путь внутри мира → (корень мира, измерение, rx, rz) или None."""
    m = REGION_RE.search(path)
    if not m:
        return None
    root = path[:m.start(1)] if m.start(1) > 0 else path[:m.start()]
    sub = m.group(1)
    dim = DIMS.get(sub) or sub.split("/")[1] + ":" + sub.split("/")[2]
    return root.rstrip("/"), dim, int(m.group(2)), int(m.group(3))


def chunks(data):
    """Все чанки региона: (cx, cz, nbt). Внешние .mcc и LZ4 пропускаются."""
    for i in range(1024):
        loc = int.from_bytes(data[i * 4:i * 4 + 3], "big")
        if loc == 0:
            continue
        off = loc * 4096
        if off + 5 > len(data):
            continue
        length, comp = struct.unpack_from(">IB", data, off)
        if comp not in (1, 2, 3) or length < 2:
            continue
        try:
            root = nbt.read_bytes(data[off + 5:off + 4 + length])
        except Exception:           # обрезанный чанк не должен ронять весь регион
            continue
        yield root


def chunk_pos(root):
    lvl = root.get("Level", root)
    return int(lvl["xPos"]), int(lvl["zPos"])


def _bits(n):
    return max(4, (n - 1).bit_length())


def _width(n_longs, n_pal, spanning):
    """Ширина индекса — по длине массива, а не по палитре: игра бывает пишет шире, чем нужно палитре
    (в мирах 1.16 палитра на 28 записей упакована по 6 бит)."""
    if spanning:
        return max(_bits(n_pal), n_longs * 64 // 4096)
    for b in range(_bits(n_pal), 33):
        if -(-4096 // (64 // b)) == n_longs:
            return b
    return _bits(n_pal)


def _indices(data, pal, spanning=False):
    idx = unpack(data, _width(len(data), len(pal), spanning), spanning)
    if idx.max(initial=0) >= len(pal):             # битая секция — лишнее в первый блок палитры (обычно воздух)
        idx = np.where(idx < len(pal), idx, 0)
    return idx


def unpack(longs, bits, spanning=False, n=4096):
    """Упакованные индексы палитры → int32[n] в порядке y, z, x."""
    a = np.asarray(longs).view(np.uint64) if np.asarray(longs).dtype == np.int64 else np.asarray(longs, np.uint64)
    mask = np.uint64((1 << bits) - 1)
    if not spanning:
        per = 64 // bits
        shifts = np.arange(per, dtype=np.uint64) * np.uint64(bits)
        vals = (a[:, None] >> shifts[None, :]) & mask
        return vals.reshape(-1)[:n].astype(np.int32)
    i = np.arange(n, dtype=np.uint64) * np.uint64(bits)
    li = (i >> np.uint64(6)).astype(np.int64)
    off = i & np.uint64(63)
    lo = a[li] >> off
    nxt = a[np.minimum(li + 1, len(a) - 1)]
    cross = (off + np.uint64(bits)) > np.uint64(64)
    sh = np.where(cross, np.uint64(64) - off, np.uint64(0))
    hi = np.where(cross, nxt << sh, np.uint64(0))
    return ((lo | hi) & mask).astype(np.int32)


def sections(root):
    """Секции чанка: (sy, палитра [(id, свойства)], индексы int32[4096] или None — вся секция palette[0])."""
    dv = int(root.get("DataVersion", 0))
    if "sections" in root:
        for s in root["sections"]:
            bs = s.get("block_states")
            if not bs or "palette" not in bs:
                continue
            pal = [(p["Name"], p.get("Properties", {})) for p in bs["palette"]]
            data = bs.get("data")
            idx = None if data is None or len(pal) == 1 else _indices(data, pal)
            yield int(s["Y"]), pal, idx
    else:
        for s in root.get("Level", {}).get("Sections", []):
            if "Blocks" in s and "Palette" not in s:
                yield (int(s["Y"]), *_legacy(s))
                continue
            if "Palette" not in s:
                continue
            pal = [(p["Name"], p.get("Properties", {})) for p in s["Palette"]]
            data = s.get("BlockStates")
            idx = None if data is None or len(pal) == 1 else _indices(data, pal, spanning=dv < 2527)
            yield int(s["Y"]), pal, idx


@lru_cache(maxsize=1)
def _legacy_map():
    d = json.loads((Path(__file__).resolve().parent / "data" / "legacy-1.12.json").read_text())["blocks"]
    return d


def _nibbles(b):
    a = np.frombuffer(bytes(b), np.uint8)
    out = np.empty(len(a) * 2, np.int32)
    out[0::2] = a & 0x0F                           # чётный индекс — младшие полбайта
    out[1::2] = a >> 4
    return out


def _legacy(s):
    """Секция до 1.13 → (палитра, индексы): Blocks (+ Add << 8) и Data по полбайта."""
    blocks = np.frombuffer(bytes(s["Blocks"]), np.uint8).astype(np.int32)
    if "Add" in s:
        blocks |= _nibbles(s["Add"])[:4096] << 8
    data = _nibbles(s["Data"])[:4096] if "Data" in s else np.zeros(4096, np.int32)
    key = blocks * 16 + data
    uniq, idx = np.unique(key, return_inverse=True)
    table = _legacy_map()
    pal = []
    for k in uniq.tolist():
        state = table.get(f"{k >> 4}:{k & 15}") or table.get(f"{k >> 4}:0") or f"legacy:id_{k >> 4}"
        name, props = parse_state(state)
        pal.append((name, props))
    return pal, idx.astype(np.int32)
