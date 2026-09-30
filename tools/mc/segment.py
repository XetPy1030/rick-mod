"""Сегментация: плитки мира → отдельные постройки.

Скан режет застройку на плитки-колонны 96×(до 192)×96: в одну попадают дом, шахта под ним и ферма
на глубине. Здесь соседние плитки снова собираются в кластер, рукотворное считается на грубой сетке
CELL³, и каждая связная масса становится своей постройкой. Слишком широкие массы режутся заново с порогом
плотности выше и 6-соседством: первыми рвутся редкие связи — дороги, мосты, туннели, — а плотные
постройки остаются целыми. Чужие массы в рамке постройки скрываются (−1), рельеф остаётся.

Плитки читаются потоком (плотность — по одной, вырезка — с небольшим кешем): город Greenfield —
тысячи плиток, целиком в память он не влезет. Связность — scipy.ndimage.label.
"""
import json
from collections import OrderedDict
from pathlib import Path

import numpy as np
from scipy import ndimage

from . import kinds
from .registry import default
from .volume import Volume
from .worlds import describe

CELL = 4
MIN_CELL = 4          # рукотворных в клетке CELL³, чтобы она считалась застроенной
MIN_ART = 600         # рукотворных в постройке
MARGIN = 2
MAX_SIDE = 128        # шире — режется по плотности, потом квадратами
MAX_THR = 16          # 16 из 64 — однослойная стена через клетку: выше порог разрезал бы полые здания на столбики
FLAT = 4              # колонна рукотворного не выше — дорога, тротуар, площадь: в вырезку идёт, в связность нет
CONN26 = np.ones((3, 3, 3), bool)
CONN6 = ndimage.generate_binary_structure(3, 1)


def _art_lut(vol):
    lut = np.zeros(len(vol.palette) + 1, bool)
    for i, (name, _) in enumerate(vol.palette):
        k = kinds.kind(name)
        lut[i] = k not in (kinds.NATURAL, kinds.RAIL) and name not in ("minecraft:air", "minecraft:cave_air")
    return lut


def _groups(builds):
    """Плитки одного кластера касаются друг друга — объединяем (union-find по рамкам)."""
    parent = list(range(len(builds)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    boxes = []
    for b in builds:
        x0, _, z0 = b["pos"]
        sx, _, sz = b["size"]
        boxes.append((b["dim"], x0, z0, x0 + sx, z0 + sz))
    order = sorted(range(len(builds)), key=lambda i: boxes[i][1])
    for a_i, a in enumerate(order):
        da, ax0, az0, ax1, az1 = boxes[a]
        for b in order[a_i + 1:]:
            db, bx0, bz0, bx1, bz1 = boxes[b]
            if bx0 > ax1 + 4:
                break
            if da == db and bz0 <= az1 + 4 and az0 <= bz1 + 4:
                parent[find(a)] = find(b)
    out = {}
    for i in range(len(builds)):
        out.setdefault(find(i), []).append(i)
    return list(out.values())


def _split(dens, mask, thr, conn, off=(0, 0, 0)):
    """Массы в mask по порогу плотности → [(смещение рамки в клетках, маска в рамке)].
    Широкая масса — заново с порогом выше (рвутся мостики и переходы), а если и так широкая — квадратами."""
    lab, n = ndimage.label(mask & (dens >= thr), structure=conn)
    out = []
    for i, sl in enumerate(ndimage.find_objects(lab), 1):
        if sl is None:
            continue
        m = lab[sl] == i
        o = tuple(off[a] + sl[a].start for a in range(3))
        ext = [(sl[a].stop - sl[a].start) * CELL for a in range(3)]
        if max(ext[0], ext[2]) <= MAX_SIDE:
            out.append((o, m))
        elif thr < MAX_THR:
            out += _split(dens[sl], m, min(MAX_THR, int(thr * 1.6) + 1), CONN6, o)
        else:
            step = MAX_SIDE // CELL
            for x in range(0, m.shape[0], step):
                for z in range(0, m.shape[2], step):
                    sub = m[x:x + step, :, z:z + step]
                    if sub.any():
                        out.append(((o[0] + x, o[1], o[2] + z), sub))
    return out


class _Tiles:
    """Плитки с кешем на несколько десятков штук."""

    def __init__(self, world_dir, size=48):
        self.dir, self.size, self.cache = world_dir, size, OrderedDict()

    def get(self, tid):
        if tid in self.cache:
            self.cache.move_to_end(tid)
            return self.cache[tid]
        v = Volume.load(self.dir / f"{tid}.npz")
        self.cache[tid] = v
        if len(self.cache) > self.size:
            self.cache.popitem(last=False)
        return v


def _above_ground(v):
    """Городской режим: поверхность колонки — первый воздух над её основанием; здание — выше неё.
    Под городскими картами лежит искусственная «земля» из бетона и шерсти, дороги — её верх, и без этого
    весь город связан в одну массу."""
    air = np.zeros(len(v.palette) + 1, bool)
    for i, (name, _) in enumerate(v.palette):
        air[i] = name.endswith("air") or name == "minecraft:structure_void"
    air[-1] = True
    a = air[v.idx]
    seen = np.maximum.accumulate(~a, axis=1)                 # снизу уже был блок
    first = np.argmax(seen & a, axis=1)                      # первый воздух над основанием
    ground = np.where((seen & a).any(axis=1), first, a.shape[1])
    ys = np.arange(a.shape[1])[None, :, None]
    return ys > ground[:, None, :] + 1


def segment(world_dir, log=print, city=False):
    world_dir = Path(world_dir)
    data = json.loads((world_dir / "builds.json").read_text())
    builds = data["builds"]
    out_dir = world_dir / "seg"
    out_dir.mkdir(exist_ok=True)
    old_index = json.loads((out_dir / "builds.json").read_text())["builds"] if (out_dir / "builds.json").exists() else []
    for old in out_dir.glob("*.npz"):
        old.unlink()
    reg = default()
    tiles = _Tiles(world_dir)
    index = []
    for gi, group in enumerate(_groups(builds)):
        tl = [builds[i] for i in group]
        dim = tl[0]["dim"]
        g0 = np.array([min(t["pos"][a] for t in tl) for a in range(3)])
        g1 = np.array([max(t["pos"][a] + t["size"][a] for t in tl) for a in range(3)])
        cshape = tuple(int(v) for v in (g1 - g0 + CELL - 1) // CELL)
        dens = np.zeros(cshape, np.uint8)             # в клетке 4³ не больше 64
        for t in tl:                                   # плотность — плитка за плиткой
            v = tiles.get(t["id"])
            art = _art_lut(v)[v.idx]
            # Плоские колонны (дороги, тротуары) в связность не идут: иначе город — одна масса
            ext = np.where(art.any(axis=1), art.shape[1] - np.argmax(art[:, ::-1, :], axis=1) - np.argmax(art, axis=1), 0)
            art &= (ext > FLAT)[:, None, :]
            if city:
                art &= _above_ground(v)
            xs, ys, zs = np.nonzero(art)
            if not len(xs):
                continue
            p = np.array(t["pos"]) - g0
            cx, cy, cz = (xs + p[0]) // CELL, (ys + p[1]) // CELL, (zs + p[2]) // CELL
            b0 = np.array([cx.min(), cy.min(), cz.min()])
            ext = np.array([cx.max(), cy.max(), cz.max()]) - b0 + 1
            lin = ((cx - b0[0]) * ext[1] + (cy - b0[1])) * ext[2] + (cz - b0[2])
            cnt = np.bincount(lin, minlength=int(ext.prod())).reshape(ext)
            view = dens[b0[0]:b0[0] + ext[0], b0[1]:b0[1] + ext[1], b0[2]:b0[2] + ext[2]]
            view += np.minimum(cnt, 255 - view).astype(np.uint8)
        parts = _split(dens, dens >= MIN_CELL, MIN_CELL, CONN26)
        lab = np.zeros(cshape, np.int32)
        for c, (o, m) in enumerate(parts, 1):
            view = lab[o[0]:o[0] + m.shape[0], o[1]:o[1] + m.shape[1], o[2]:o[2] + m.shape[2]]
            view[m] = c
        # Вырезка: части по порядку положения — соседние плитки остаются в кеше
        order = sorted(range(len(parts)), key=lambda c: (parts[c][0][0] // 24, parts[c][0][2]))
        for ci in order:
            o, m = parts[ci]
            c = ci + 1
            if int(dens[o[0]:o[0] + m.shape[0], o[1]:o[1] + m.shape[1], o[2]:o[2] + m.shape[2]][m].sum()) < MIN_ART:
                continue
            lo = np.maximum(np.array(o) * CELL + g0 - MARGIN, g0)
            hi = np.minimum((np.array(o) + m.shape) * CELL + g0 + MARGIN, g1)
            size = tuple(int(v) for v in hi - lo)
            idx = np.full(size, -1, np.int32)
            keys = {}
            for t in tl:
                tlo = np.array(t["pos"])
                thi = tlo + np.array(t["size"])
                a0, a1 = np.maximum(lo, tlo), np.minimum(hi, thi)
                if (a1 <= a0).any():
                    continue
                v = tiles.get(t["id"])
                remap = np.array([keys.setdefault((n_, tuple(sorted(p.items()))), len(keys)) for n_, p in v.palette]
                                 + [-1], np.int32)
                s0, s1 = a0 - tlo, a1 - tlo
                d0, d1 = a0 - lo, a1 - lo
                idx[d0[0]:d1[0], d0[1]:d1[1], d0[2]:d1[2]] = remap[v.idx[s0[0]:s1[0], s0[1]:s1[1], s0[2]:s1[2]]]
            palette = [(n_, dict(p)) for (n_, p), _ in sorted(keys.items(), key=lambda kv: kv[1])]
            # Клетки других масс в этой рамке — скрыть
            c0 = (lo - g0) // CELL
            c1 = (hi - g0 + CELL - 1) // CELL
            sub = lab[c0[0]:c1[0], c0[1]:c1[1], c0[2]:c1[2]]
            other = (sub != c) & (sub > 0)
            if other.any():
                big = other.repeat(CELL, 0).repeat(CELL, 1).repeat(CELL, 2)
                off = lo - (c0 * CELL + g0)
                big = big[off[0]:off[0] + size[0], off[1]:off[1] + size[1], off[2]:off[2] + size[2]]
                idx[:big.shape[0], :big.shape[1], :big.shape[2]][big] = -1
            seg = Volume(size, palette, idx)
            stats = describe(seg, reg)
            if stats.get("artificial", 0) < MIN_ART:
                continue
            name = f"{dim.replace(':', '_')}_{int(lo[0])}_{int(lo[1])}_{int(lo[2])}"
            seg.save_npz(out_dir / f"{name}.npz")
            index.append({"id": name, "dim": dim, "pos": [int(v) for v in lo], "size": list(size),
                          "cluster": gi, **stats})
    index.sort(key=lambda e: -e["score"])
    moved = _migrate_labels(world_dir.name, old_index, index)
    (out_dir / "builds.json").write_text(json.dumps({"summary": {**data["summary"], "segments": len(index)},
                                                     "builds": index}, ensure_ascii=False, indent=1))
    log(f"{world_dir.name}: {len(builds)} плиток → {len(index)} построек" + (f", меток перенесено {moved}" if moved else ""))
    return index


def _migrate_labels(world, old, new):
    """Ручные метки старой нарезки → сегмент новой с наибольшим перекрытием рамок (от половины)."""
    from .classify import labels, save_labels
    lab = labels()
    olds = {f"{world}:{e['id']}": e for e in old}
    todo = [sid for sid in lab if sid in olds and sid not in {f"{world}:{e['id']}" for e in new}]
    moved = 0
    for sid in todo:
        a = olds[sid]
        a0, a1 = np.array(a["pos"]), np.array(a["pos"]) + a["size"]
        best, score = None, 0.0
        for e in new:
            if e["dim"] != a["dim"]:
                continue
            b0, b1 = np.array(e["pos"]), np.array(e["pos"]) + e["size"]
            inter = np.prod(np.clip(np.minimum(a1, b1) - np.maximum(a0, b0), 0, None))
            union = np.prod(a1 - a0) + np.prod(b1 - b0) - inter
            if union and inter / union > score:
                best, score = e, inter / union
        if best is not None and score >= 0.5:
            lab.setdefault(f"{world}:{best['id']}", lab[sid])
            del lab[sid]
            moved += 1
    if moved:
        save_labels(lab)
    return moved
