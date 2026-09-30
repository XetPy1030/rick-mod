"""Постройки из чужих миров: где в мире рукотворное, кластеры застройки, вырезка в объёмы.

Мир читается прямо из zip — локального или удалённого по HTTP Range (архив Hermitcraft на 2 ГБ
не качается целиком: берутся только регионы, без entities/ и poi/). Два прохода:

1. «Тепло»: для каждого чанка — сколько рукотворных блоков в каждой секции 16³. Секции без рукотворного
   в палитре не распаковываются, поэтому проход упирается в сеть, а не в процессор. Сгенерированные игрой
   постройки (деревни, шахты, крепости) вычитаются по рамкам их частей из NBT чанков.
   Регионы с застройкой кладутся в кеш на диск.
2. Кластеры застроенных чанков → рамки построек (крупное режется на плитки) → вырезка из кеша в .npz.

Что рукотворное — по правилу мода (kinds.py). Рельсы не считаются: это шахты и дороги.
"""
import json
import math
import shutil
import time
import zipfile
from collections import Counter, deque
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import numpy as np

from . import anvil, kinds, nbt
from .registry import default
from .remote import RangeFile, resolve
from .volume import Volume

HOT = 96            # рукотворных в чанке, чтобы он считался застроенным
MIN_BUILD = 1500    # рукотворных в постройке, меньше — мелочь
TILE = 6            # чанков: кластер крупнее режется на плитки TILE×TILE
MAX_H = 192         # предел высоты вырезки


def _is_art(name, dv, reg):
    if name in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"):
        return False
    new, _ = reg.upgrade(name, None, dv)
    k = kinds.kind(new)
    return k != kinds.NATURAL and k != kinds.RAIL


# ------------------------------------------------------------------------------------------ источник
class Source:
    """Мир в zip (файл или URL) или папке. Регионы — в порядке лежания в архиве, чтобы читать потоком."""

    def __init__(self, loc):
        self.loc = loc
        self.remote = None
        self.zip = None
        if loc.startswith(("http://", "https://", "mediafire:")):
            self.remote = RangeFile(resolve(loc))
            self.zip = zipfile.ZipFile(self.remote)
        elif str(loc).endswith(".zip"):
            self.zip = zipfile.ZipFile(loc)
        self.dir = None if self.zip else Path(loc)

    @property
    def fetched(self):
        return self.remote.fetched if self.remote else 0

    def regions(self):
        out = []
        if self.zip:
            for info in sorted(self.zip.infolist(), key=lambda i: i.header_offset):
                m = anvil.region_member(info.filename)
                if m and info.file_size > 8192:
                    out.append((info, *m, info.compress_size))
        else:
            for p in sorted(self.dir.rglob("r.*.mca")):
                m = anvil.region_member(p.relative_to(self.dir).as_posix())
                if m and p.stat().st_size > 8192:
                    out.append((p, *m, p.stat().st_size))
        return out

    def read(self, ref):
        if self.remote:
            # Заголовок члена (30 байт + имя + extra) и данные — одним запросом, без упреждения на соседей
            self.remote.prefetch(ref.header_offset, 30 + len(ref.filename.encode()) + 1024 + ref.compress_size)
        return self.zip.read(ref) if self.zip else Path(ref).read_bytes()

    def level(self):
        """level.dat главного мира: версия и имя."""
        try:
            if self.zip:
                names = sorted((n for n in self.zip.namelist() if n.endswith("level.dat")), key=len)
                data = self.zip.read(names[0]) if names else None
            else:
                p = next(iter(sorted(self.dir.rglob("level.dat"), key=lambda p: len(str(p)))), None)
                data = p.read_bytes() if p else None
            d = nbt.read_bytes(data)["Data"] if data else {}
            return {"name": d.get("LevelName"), "version": d.get("Version", {}).get("Name"),
                    "data_version": d.get("DataVersion")}
        except Exception as e:           # level.dat не обязателен
            return {"error": str(e)}


# ------------------------------------------------------------------------------------------ проход 1
def _heat(job):
    dim, rx, rz, data = job
    reg = default()
    cache = {}
    chunks, pieces, unknown, dvs = [], [], Counter(), Counter()
    for root in anvil.chunks(data):
        try:
            cx, cz = anvil.chunk_pos(root)
        except KeyError:
            continue
        dv = int(root.get("DataVersion", 0))
        dvs[dv] += 1
        lvl = root.get("Level", root)
        starts = (root.get("structures") or {}).get("starts") or (lvl.get("Structures") or {}).get("Starts") or {}
        for s in starts.values():
            for c in s.get("Children", []) or []:
                if "BB" in c:
                    pieces.append([int(v) for v in c["BB"]])
        counts = {}
        for sy, pal, idx in anvil.sections(root):
            key = tuple(p[0] for p in pal)
            flags = cache.get((key, dv < 1901))
            if flags is None:
                flags = np.array([_is_art(n, dv, reg) for n in key])
                for n in key:
                    up = reg.upgrade(n, None, dv)[0]
                    if reg.block(up) is None:
                        unknown[up] += 1
                cache[(key, dv < 1901)] = flags
            if not flags.any():
                continue
            n = (4096 if flags[0] else 0) if idx is None else int(flags[idx].sum())
            if n:
                counts[sy] = n
        if counts:
            chunks.append((cx, cz, counts))
    return dim, rx, rz, chunks, pieces, unknown, dvs


def _merge(heat, pieces, unknown, dvs, dim, chunks, pcs, unk, dv):
    h = heat.setdefault(dim, {})
    for cx, cz, counts in chunks:
        h[(cx, cz)] = counts
    pieces.setdefault(dim, []).extend(pcs)
    unknown.update(unk)
    dvs.update(dv)


def _subtract_pieces(heat, pieces):
    """Сгенерированное игрой не в счёт: доля секции под рамкой части постройки вычитается."""
    for x0, y0, z0, x1, y1, z1 in pieces:
        for cx in range(x0 >> 4, (x1 >> 4) + 1):
            for cz in range(z0 >> 4, (z1 >> 4) + 1):
                c = heat.get((cx, cz))
                if not c:
                    continue
                ox = min(x1, cx * 16 + 15) - max(x0, cx * 16) + 1
                oz = min(z1, cz * 16 + 15) - max(z0, cz * 16) + 1
                for sy in list(c):
                    oy = min(y1, sy * 16 + 15) - max(y0, sy * 16) + 1
                    if oy > 0:
                        c[sy] = int(c[sy] * (1 - ox * oy * oz / 4096))
                        if c[sy] <= 0:
                            del c[sy]


def clusters(heat):
    """Застроенные чанки → рамки построек: [(cx0, cz0, cx1, cz1, sy0, sy1, рукотворных)]."""
    tot = {k: sum(v.values()) for k, v in heat.items()}
    hot = {k for k, n in tot.items() if n >= HOT}
    seen, out = set(), []
    for start in sorted(hot):
        if start in seen:
            continue
        comp, q = [], deque([start])
        seen.add(start)
        while q:
            cx, cz = q.popleft()
            comp.append((cx, cz))
            for dx in (-2, -1, 0, 1, 2):          # разрыв в один чанк — всё ещё одна застройка
                for dz in (-2, -1, 0, 1, 2):
                    n = (cx + dx, cz + dz)
                    if n in hot and n not in seen:
                        seen.add(n)
                        q.append(n)
        xs = [c[0] for c in comp]
        zs = [c[1] for c in comp]
        x0, x1, z0, z1 = min(xs), max(xs), min(zs), max(zs)
        # Крупное — плитками; плитки по сетке от угла кластера
        for tx in range(x0, x1 + 1, TILE):
            for tz in range(z0, z1 + 1, TILE):
                cells = [c for c in comp if tx <= c[0] < tx + TILE and tz <= c[1] < tz + TILE]
                n = sum(tot[c] for c in cells)
                if n < MIN_BUILD:
                    continue
                ys = Counter()
                for c in cells:
                    for sy, v in heat[c].items():
                        ys[sy] += v
                peak = max(ys.values())
                sy_keep = sorted(sy for sy, v in ys.items() if v >= peak * 0.03)
                bx = [c[0] for c in cells]
                bz = [c[1] for c in cells]
                out.append((min(bx), min(bz), max(bx), max(bz), sy_keep[0], sy_keep[-1], n))
    return out


# ------------------------------------------------------------------------------------------ проход 2
def _extract(job):
    """Регион из кеша → куски построек, которые в него попадают: {номер: (палитра, массив, смещение)}."""
    path, boxes = job
    reg = default()
    data = Path(path).read_bytes()
    out = {}
    for root in anvil.chunks(data):
        try:
            cx, cz = anvil.chunk_pos(root)
        except KeyError:
            continue
        dv = int(root.get("DataVersion", 0))
        for bid, (x0, y0, z0, x1, y1, z1) in boxes.items():
            if not (x0 >> 4 <= cx <= x1 >> 4 and z0 >> 4 <= cz <= z1 >> 4):
                continue
            part = out.setdefault(bid, {"pal": {}, "blocks": []})
            for sy, pal, idx in anvil.sections(root):
                if (sy + 1) * 16 <= y0 or sy * 16 > y1:
                    continue
                ups = [reg.upgrade(n, p, dv) for n, p in pal]
                ids = np.array([part["pal"].setdefault(n + _props_key(p), len(part["pal"])) for n, p in ups],
                               dtype=np.int32)
                sec = ids[idx].reshape(16, 16, 16) if idx is not None else np.full((16, 16, 16), ids[0], np.int32)
                part["blocks"].append((cx * 16, sy * 16, cz * 16, sec.transpose(2, 0, 1).copy()))   # → x, y, z
    return out


def _props_key(props):
    return "[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else ""


# ------------------------------------------------------------------------------------------ скан целиком
def scan(loc, out_dir, cache_dir, budget=None, workers=3, log=print, keep_cache=False, only_dims=None):
    """Весь конвейер для одного мира. Возвращает сводку; постройки — в out_dir/*.npz и builds.json."""
    out_dir, cache_dir = Path(out_dir), Path(cache_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    cache_dir.mkdir(parents=True, exist_ok=True)
    src = Source(loc)
    level = src.level()
    regions = [r for r in src.regions() if not only_dims or r[2] in only_dims]
    log(f"мир {level.get('name')} {level.get('version')}: {len(regions)} регионов, "
        f"{sum(r[5] for r in regions) / 1e9:.2f} ГБ")
    heat, pieces, unknown, dvs = {}, {}, Counter(), Counter()
    cached, done = {}, set()
    # Продолжение после обрыва: итоги прохода 1 по регионам — в heat.jsonl, застроенные регионы — в кеше
    progress = cache_dir / "heat.jsonl"
    if progress.exists():
        for line in progress.read_text().splitlines():
            try:
                r = json.loads(line)
            except ValueError:
                continue                           # недописанная последняя строка
            _merge(heat, pieces, unknown, dvs, r["dim"], [(cx, cz, {int(k): v for k, v in c.items()})
                                                          for cx, cz, c in r["chunks"]], r["pieces"],
                   Counter(r["unknown"]), Counter({int(k): v for k, v in r["dvs"].items()}))
            done.add((r["dim"], r["rx"], r["rz"]))
    for p in cache_dir.glob("*/r.*.mca"):
        _, rx, rz, _ = p.name.split(".")
        cached[(p.parent.name.replace("_", ":", 1) if p.parent.name not in ("overworld", "nether", "end")
                else p.parent.name, int(rx), int(rz))] = p
    if done:
        log(f"продолжение: {len(done)} регионов уже разобраны, {len(cached)} в кеше")
    t0 = time.time()
    stopped = None
    with ProcessPoolExecutor(workers) as pool:
        pending = deque()

        def take():
            data, fut = pending.popleft()
            dim, rx, rz, chunks, pcs, unk, dv = fut.result()
            _merge(heat, pieces, unknown, dvs, dim, chunks, pcs, unk, dv)
            if any(sum(c.values()) >= HOT // 2 for _, _, c in chunks) and (dim, rx, rz) not in cached:
                p = cache_dir / dim.replace(":", "_") / f"r.{rx}.{rz}.mca"
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(data)
                cached[(dim, rx, rz)] = p
            with progress.open("a") as f:
                f.write(json.dumps({"dim": dim, "rx": rx, "rz": rz, "chunks": chunks, "pieces": pcs,
                                    "unknown": unk, "dvs": dv}) + "\n")

        for k, (ref, root, dim, rx, rz, size) in enumerate(regions):
            if (dim, rx, rz) in done:
                continue
            if budget and src.fetched > budget:
                stopped = f"упёрлись в бюджет трафика {budget / 1e9:.1f} ГБ на регионе {k} из {len(regions)}"
                log(stopped)
                break
            local = cached.get((dim, rx, rz))
            data = local.read_bytes() if local else src.read(ref)
            pending.append((data, pool.submit(_heat, (dim, rx, rz, data))))
            while len(pending) > workers * 2 or pending and pending[0][1].done():
                take()
            if k % 20 == 0:
                log(f"  {k}/{len(regions)} регионов, {src.fetched / 1e6:.0f} МБ, {time.time() - t0:.0f} с, "
                    f"застроенных регионов {len(cached)}")
        while pending:
            take()
        log(f"проход 1: {time.time() - t0:.0f} с, скачано {src.fetched / 1e6:.0f} МБ")
        if stopped:
            # Прогресс и кеш остаются — `refs.py scan` с бюджетом побольше продолжит с этого места
            return {"source": loc, "stopped": stopped, "fetched": src.fetched, "builds": 0, "unknown": {}}

        builds = []
        for dim, h in heat.items():
            _subtract_pieces(h, pieces.get(dim, []))
            for cx0, cz0, cx1, cz1, sy0, sy1, n in clusters(h):
                y0 = sy0 * 16
                y1 = min(sy1 * 16 + 15, y0 + MAX_H - 1)
                builds.append({"dim": dim, "box": [cx0 * 16, y0, cz0 * 16, cx1 * 16 + 15, y1, cz1 * 16 + 15],
                               "heat": n})
        log(f"построек-кандидатов: {len(builds)}")

        # Проход 2: регион → какие рамки в него попадают; постройка собирается, как только готовы все её регионы
        jobs, need = {}, Counter()
        for bid, b in enumerate(builds):
            x0, y0, z0, x1, y1, z1 = b["box"]
            for rx in range(x0 >> 9, (x1 >> 9) + 1):
                for rz in range(z0 >> 9, (z1 >> 9) + 1):
                    p = cached.get((b["dim"], rx, rz))
                    if p:
                        jobs.setdefault(str(p), {})[bid] = b["box"]
                        need[bid] += 1
        reg = default()
        index, parts = [], {}
        futs = [pool.submit(_extract, job) for job in jobs.items()]
        for fut in as_completed(futs):
            for bid, part in fut.result().items():
                parts.setdefault(bid, []).append(part)
            for bid in [b for b in parts if len(parts[b]) == need[b]]:
                entry = _save(builds[bid], parts.pop(bid), out_dir, reg)
                if entry:
                    index.append(entry)
        log(f"проход 2: {time.time() - t0:.0f} с")

    index.sort(key=lambda e: -e["score"])
    summary = {"source": loc, "level": level, "regions": len(regions), "fetched": src.fetched,
               "seconds": round(time.time() - t0), "stopped": stopped, "builds": len(index),
               "data_versions": dict(dvs.most_common(5)), "unknown": dict(unknown.most_common(40))}
    (out_dir / "builds.json").write_text(json.dumps({"summary": summary, "builds": index}, ensure_ascii=False,
                                                    indent=1))
    if not keep_cache:
        shutil.rmtree(cache_dir, ignore_errors=True)
    log(f"готово: {len(index)} построек, {summary['seconds']} с, скачано {src.fetched / 1e9:.2f} ГБ")
    return summary


def _save(b, parts, out_dir, reg):
    vol = _assemble(b, parts)
    if vol is None:
        return None
    stats = describe(vol, reg)
    if stats["artificial"] < MIN_BUILD // 2:
        return None
    x0, y0, z0 = b["box"][:3]
    pos = [x0 + vol.offset[0], y0 + vol.offset[1], z0 + vol.offset[2]]
    name = f"{b['dim'].replace(':', '_')}_{pos[0]}_{pos[2]}"
    vol.save_npz(out_dir / f"{name}.npz")
    return {"id": name, "dim": b["dim"], "pos": pos, "size": list(vol.size), **stats}


def _assemble(b, parts):
    """Куски из разных регионов → один объём, обрезанный по высоте до рукотворного."""
    if not parts:
        return None
    x0, y0, z0, x1, y1, z1 = b["box"]
    size = (x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1)
    palette, remap_all = {}, []
    idx = np.full(size, -1, np.int32)
    for part in parts:
        order = sorted(part["pal"].items(), key=lambda kv: kv[1])
        remap = np.array([palette.setdefault(key, len(palette)) for key, _ in order], np.int32)
        for bx, by, bz, sec in part["blocks"]:
            lx, ly, lz = bx - x0, by - y0, bz - z0
            sx = slice(max(0, lx), min(size[0], lx + 16))
            sy = slice(max(0, ly), min(size[1], ly + 16))
            sz = slice(max(0, lz), min(size[2], lz + 16))
            if sx.start >= sx.stop or sy.start >= sy.stop or sz.start >= sz.stop:
                continue
            idx[sx, sy, sz] = remap[sec[sx.start - lx:sx.stop - lx, sy.start - ly:sy.stop - ly,
                                        sz.start - lz:sz.stop - lz]]
        remap_all.append(remap)
    pal = [_parse_key(k) for k, _ in sorted(palette.items(), key=lambda kv: kv[1])]
    art = np.array([_is_art(n, None, default()) for n, _ in pal] + [False])
    mask = art[idx]                               # −1 → последний элемент, False
    rows = np.nonzero(mask.sum(axis=(0, 2)))[0]
    cols_x = np.nonzero(mask.sum(axis=(1, 2)))[0]
    cols_z = np.nonzero(mask.sum(axis=(0, 1)))[0]
    if len(rows) == 0:
        return None
    ya, yb = max(0, rows[0] - 3), min(size[1], rows[-1] + 3)
    xa, xb = max(0, cols_x[0] - 2), min(size[0], cols_x[-1] + 3)
    za, zb = max(0, cols_z[0] - 2), min(size[2], cols_z[-1] + 3)
    vol = Volume((xb - xa, yb - ya, zb - za), pal, np.ascontiguousarray(idx[xa:xb, ya:yb, za:zb]))
    vol.offset = (int(xa), int(ya), int(za))
    return vol


def _parse_key(key):
    name, _, rest = key.partition("[")
    props = dict(kv.split("=", 1) for kv in rest.rstrip("]").split(",") if "=" in kv) if rest else {}
    return name, props


# ------------------------------------------------------------------------------------------ оценка
def describe(vol, reg=None):
    """Сводка постройки для индекса и оценки: рукотворное, материалы, детальность, высота, свет."""
    reg = reg or default()
    counts = np.bincount(vol.idx[vol.idx >= 0].ravel(), minlength=len(vol.palette))
    art, fam, shapes, light, kinds_c = 0, Counter(), Counter(), 0, Counter()
    for i, (name, props) in enumerate(vol.palette):
        c = int(counts[i])
        if not c:
            continue
        k = kinds.kind(name)
        if k == kinds.NATURAL or k == kinds.RAIL:
            continue
        art += c
        fam[kinds.family(name)] += c
        shapes[kinds.shape(name)] += c
        kinds_c[k] += c
        if reg.light(name, props) >= 10:
            light += c
    if not art:
        return {"artificial": 0, "score": 0}
    detail = 1 - shapes["full"] / art
    distinct = sum(1 for v in fam.values() if v >= max(8, art * 0.005))
    ys = np.nonzero((vol.idx >= 0).any(axis=(0, 2)))[0]
    score = (math.log10(art) * (0.4 + min(detail, 0.5)) * min(distinct, 16) / 16
             * (1 + min(light / art * 20, 0.3)))
    return {"artificial": art, "families": [[k, v] for k, v in fam.most_common(12)],
            "detail": round(detail, 3), "distinct": distinct, "light": light,
            "kinds": dict(kinds_c.most_common()), "height": int(len(ys)), "score": round(score, 3)}
