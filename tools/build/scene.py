"""Сцена генератора: формы, кисти, префабы, точки, декор → структура NBT, layout.json, лист рендера, отчёт линтера.

    s = Scene("citadel/lab", size=(27, 12, 31), seed=26)
    s.box(5, 1, 2, 21, 1, 13, "smooth_stone")
    s.fill(shape.sphere((48, 10, 48), 20).cut(y0=10).inner(1), brush.solid("white_concrete"), smooth=True)
    s.prefab(lamp_post, at=(5, 2, 16), facing="east")
    s.marker("rick", at=(13, 2, 6), facing="south")
    s.save()

Сетка сцены — индексы палитры (numpy), −1 — «не задано»: при установке блок мира там не меняется.
Воздух — обычный блок: им вырезают проёмы; в NBT он не пишется, как и у генератора этапа 3.
Детерминизм: шум — от seed и позиции, блоки пишутся по (y, z, x), палитра — в порядке первого применения.
"""
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np

from mc import OUT, ROOT, TOOLS, nbt
from mc.registry import default, full_id
from mc.volume import Volume

from . import states as st
from .brush import as_brush

DATA_VERSION = 4903          # world_version Minecraft 26.2
STRUCTURES = ROOT / "src/main/resources/data/rikoshet/structure"
PREFABS = Path(__file__).resolve().parent / "prefabs"
FACING_K = {"south": 0, "west": 1, "north": 2, "east": 3}   # префаб рисуется «лицом на юг», поворот по часовой
AIR = "minecraft:air"


def _hash(pos, seed, k):
    """Детерминированный шум [0, 1) от целых координат (N, 3)."""
    p = pos.astype(np.uint64)
    h = (p[:, 0] * np.uint64(0x9E3779B97F4A7C15) ^ p[:, 1] * np.uint64(0xC2B2AE3D27D4EB4F)
         ^ p[:, 2] * np.uint64(0x165667B19E3779F9) ^ np.uint64((seed * 1000003 + k * 7919) & 0xFFFFFFFFFFFF))
    h ^= h >> np.uint64(31)
    h *= np.uint64(0xBF58476D1CE4E5B9)
    h ^= h >> np.uint64(29)
    return (h >> np.uint64(11)).astype(np.float64) / float(1 << 53)


class Fill:
    """Контекст кисти: клетки формы и их свойства."""

    def __init__(self, scene, pos, d, normal, exposed, edge, k):
        self.scene, self.pos, self.d, self.normal = scene, pos, d, normal
        self.exposed, self.edge, self.k = exposed, edge, k
        self.center = np.array(scene.size, float) / 2

    def rand(self, k=0):
        return _hash(self.pos, self.scene.seed, self.k * 31 + k)

    def noise(self, scale, k=0):
        """Плавный 3D-шум значений [0, 1) с ячейкой scale блоков."""
        p = self.pos / max(scale, 1e-6)
        i = np.floor(p).astype(np.int64)
        f = p - i
        f = f * f * (3 - 2 * f)
        out = np.zeros(len(p))
        for dx in (0, 1):
            for dy in (0, 1):
                for dz in (0, 1):
                    w = (f[:, 0] if dx else 1 - f[:, 0]) * (f[:, 1] if dy else 1 - f[:, 1]) * (f[:, 2] if dz else 1 - f[:, 2])
                    out += w * _hash(i + [dx, dy, dz], self.scene.seed, self.k * 31 + k)
        return out


class Frame:
    """Локальная система координат префаба: сдвиг, поворот по часовой k×90°, отражение. Префаб-функция
    рисует «лицом на юг» от своего начала, а рамка переводит координаты и состояния в сцену."""

    def __init__(self, scene, origin, k=0, mirror=None):
        self.scene, self.origin, self.k, self.mirror = scene, tuple(origin), k % 4, mirror

    def to_scene(self, x, y, z):
        if self.mirror == "left_right":
            z = -z
        elif self.mirror == "front_back":
            x = -x
        for _ in range(self.k):
            x, z = -z, x
        return self.origin[0] + x, self.origin[1] + y, self.origin[2] + z

    def facing(self, d):
        return st.rot_dir(st.mirror_dir(d, self.mirror) if self.mirror else d, self.k)

    def set(self, x, y, z, name, nbt_data=None, **props):
        props = st.rotate({k: str(v).lower() for k, v in props.items()}, self.k, self.mirror)
        self.scene.set(*self.to_scene(x, y, z), name, nbt_data, **props)

    put = set

    def box(self, x0, y0, z0, x1, y1, z1, name, **props):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, name, **props)

    def get(self, x, y, z):
        return self.scene.get(self.to_scene(x, y, z))

    def marker(self, name, at, facing="south"):
        self.scene.marker(name, self.to_scene(*at), self.facing(facing))

    def prefab(self, src, at, facing="south", mirror=None):
        x, y, z = self.to_scene(*at)
        k = (FACING_K[facing] + self.k) % 4
        self.scene._prefab(src, (x, y, z), k, mirror or self.mirror)


class Scene:
    def __init__(self, name, size, seed=26, out=None, keep_air=False):
        self.name = name
        self.size = tuple(int(v) for v in size)
        self.seed = seed
        self.idx = np.full(self.size, -1, np.int32)
        self.palette = []                 # [(id, {свойства})] — как запишется в NBT
        self._pal = {}
        self.block_nbt = {}               # (x, y, z) → nbt блок-сущности
        self.fixed = set()                # клетки, которые соединения не трогают
        self.markers = []                 # (имя, (x, y, z), сторона)
        self.displays = []                # display-декор → layout.json
        self.structures = []              # чужие структуры по id → layout.json
        self.notes = []
        self.out = Path(out) if out else STRUCTURES / f"{name}.nbt"
        self.keep_air = keep_air
        self.reg = default()
        self._fills = 0

    # ------------------------------------------------------------------------------------ блоки
    def state(self, name, props=None):
        name = full_id(name)
        props = {k: str(v).lower() for k, v in (props or {}).items()}
        key = (name, tuple(sorted(props.items())))
        if key not in self._pal:
            self._pal[key] = len(self.palette)
            self.palette.append((name, props))
        return self._pal[key]

    def inside(self, x, y, z):
        return 0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]

    def set(self, x, y, z, name, nbt_data=None, keep=False, **props):
        if not self.inside(x, y, z):
            raise ValueError(f"{self.name}: блок вне сцены {(x, y, z)} при размере {self.size}")
        self.idx[x, y, z] = self.state(name, props)
        if nbt_data:
            self.block_nbt[(x, y, z)] = nbt_data
        else:
            self.block_nbt.pop((x, y, z), None)
        if keep:
            self.fixed.add((x, y, z))

    put = set

    def get(self, pos):
        x, y, z = pos
        if not self.inside(x, y, z):
            return None
        i = self.idx[x, y, z]
        return self.palette[i] if i >= 0 else None

    def set_state(self, pos, name, props):
        """Замена состояния соединениями: свойства, равные умолчанию и не заданные раньше, не пишутся."""
        if pos in self.fixed:
            return
        cur = self.palette[self.idx[pos]][1]
        dflt = self.reg.props(name)
        props = {k: v for k, v in props.items() if k in cur or dflt.get(k) != v}
        self.idx[pos] = self.state(name, props)

    def states(self):
        for x, y, z in np.argwhere(self.idx >= 0):
            yield (int(x), int(y), int(z)), self.palette[self.idx[x, y, z]]

    def box(self, x0, y0, z0, x1, y1, z1, name, **props):
        """Прямоугольник блоков включительно; быстро — одним срезом."""
        xs, ys, zs = sorted((x0, x1)), sorted((y0, y1)), sorted((z0, z1))
        for a, (lo, hi) in enumerate((xs, ys, zs)):
            if lo < 0 or hi >= self.size[a]:
                raise ValueError(f"{self.name}: коробка вне сцены {(x0, y0, z0, x1, y1, z1)}")
        self.idx[xs[0]:xs[1] + 1, ys[0]:ys[1] + 1, zs[0]:zs[1] + 1] = self.state(name, props)

    # ------------------------------------------------------------------------------------ формы
    def fill(self, shape, brush, smooth=False, replace=None, only_empty=False, hollow=0):
        """Залить форму кистью. smooth — края ступенями и плитами по доле заполнения клетки.
        hollow=N — только N слоёв у поверхности: оболочка без дыр при любой кривизне (в отличие от
        тонкой .shell(), которая может не задеть центр клетки). replace — заливать только поверх этих id;
        only_empty — только в незаданные и воздух."""
        brush = as_brush(brush)
        self._fills += 1
        lo = np.maximum(np.floor(shape.lo).astype(int) - 1, 0)
        hi = np.minimum(np.ceil(shape.hi).astype(int) + 1, self.size)
        if (hi <= lo).any():
            return 0
        g = np.stack(np.meshgrid(*[np.arange(lo[a], hi[a]) for a in range(3)], indexing="ij"), -1)
        d = shape(g + 0.5)
        inside = d <= 0
        if hollow:
            core = inside.copy()
            for _ in range(hollow):                       # снимаем слой за слоем по 6-соседству
                pad = np.pad(core, 1)
                core = core & pad[:-2, 1:-1, 1:-1] & pad[2:, 1:-1, 1:-1] & pad[1:-1, :-2, 1:-1] \
                    & pad[1:-1, 2:, 1:-1] & pad[1:-1, 1:-1, :-2] & pad[1:-1, 1:-1, 2:]
            inside = inside & ~core
        band = (d > 0) & (d < 0.9) if smooth else np.zeros_like(inside)
        occ8 = None
        rowmap = np.full(inside.shape, -1, np.int64)
        if smooth:
            offs = np.array([[dx, dy, dz] for dx in (.25, .75) for dy in (.25, .75) for dz in (.25, .75)])
            cand = (inside & (d > -0.9)) | band            # у поверхности; глубже — всегда полный блок
            cp = g[cand]
            occ8 = np.stack([shape(cp + o) <= 0 for o in offs], -1)       # (n, 8): x-старший, потом y, потом z
            rowmap[cand] = np.arange(len(cp))
        cells = inside | band
        if replace is not None or only_empty:
            cur = self.idx[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]]
            ok = np.zeros_like(cells)
            if only_empty:
                air = self._pal.get((AIR, ()), -2)
                ok |= (cur < 0) | (cur == air)
            if replace is not None:
                ids = [self._pal[k] for k in self._pal if k[0] in {full_id(r) for r in replace}]
                ok |= np.isin(cur, ids)
            cells &= ok
        pos = g[cells]
        if not len(pos):
            return 0
        dd = d[cells]
        rows = rowmap[cells]
        eps = 0.5
        fp = np.stack([shape(pos + 0.5 + e) for e in np.eye(3) * eps], -1)
        fm = np.stack([shape(pos + 0.5 - e) for e in np.eye(3) * eps], -1)
        grad = fp - fm
        normal = grad / (np.linalg.norm(grad, axis=1, keepdims=True) + 1e-9)
        # Ребро — где резко меняется нормаль: лапласиан SDF. На плоскости 0, на сфере радиуса r — 2/r,
        # у ребра коробки — порядка 1. Ступенчатость вокселей кривой поверхности сюда не попадает.
        lap = (fp + fm - 2 * dd[:, None]).sum(1) / eps ** 2
        # Открытые грани: сосед вне формы
        full = np.zeros(inside.shape, bool)
        full |= inside
        pad = np.pad(full, 1)
        rel = pos - lo + 1
        exposed = np.zeros(len(pos), int)
        axes_open = np.zeros((len(pos), 3), bool)
        for a in range(3):
            for s in (-1, 1):
                q = rel.copy()
                q[:, a] += s
                o = ~pad[q[:, 0], q[:, 1], q[:, 2]]
                exposed += o
                axes_open[:, a] |= o
        edge = (axes_open.sum(1) >= 2) & (np.abs(lap) > 0.5)
        ctx = Fill(self, pos, dd, normal, exposed, edge, self._fills)
        names = brush(ctx)
        shapes_ = np.full(len(pos), "full", dtype=object)
        props_ = [None] * len(pos)
        if smooth:
            # Внутри формы — всегда полный блок (иначе в оболочке в один слой будут дыры);
            # ступени и плиты только добавляются снаружи, в клетки, которые форма задевает
            for i in np.nonzero((rows >= 0) & (dd > 0))[0]:
                shapes_[i], props_[i] = _smooth_shape(occ8[rows[i]])
        n = 0
        for i, (x, y, z) in enumerate(pos):
            shp = shapes_[i]
            if shp is None:
                continue
            name = names[i]
            if shp == "full":
                self.set(int(x), int(y), int(z), name)
                n += 1
                continue
            v = self.variant(name, shp)
            if v:
                self.set(int(x), int(y), int(z), v, **props_[i])
                n += 1
            elif shp in ("slab", "stairs") and props_[i].get("half", props_[i].get("type")) in ("bottom", "top"):
                if shp == "stairs" or dd[i] <= 0:
                    self.set(int(x), int(y), int(z), name)     # нет ступеней у материала — полный блок
                    n += 1
        return n

    def variant(self, name, shape):
        """Ступени или плита того же материала: stone_bricks → stone_brick_stairs; нет — None."""
        name = full_id(name)
        ns, path = name.split(":", 1)
        bases = [path]
        for suf, rep in (("_bricks", "_brick"), ("_tiles", "_tile"), ("_planks", ""), ("_block", ""),
                         ("s", "")):
            if path.endswith(suf):
                bases.append(path[: -len(suf)] + rep)
        if path.startswith("smooth_") or path.startswith("polished_"):
            bases.append(path)
        suffix = {"stairs": "_stairs", "slab": "_slab", "wall": "_wall"}[shape]
        for b in bases:
            cand = f"{ns}:{b}{suffix}"
            if self.reg.block(cand):
                return cand
        return None

    # ------------------------------------------------------------------------------------ префабы и точки
    def _prefab(self, src, at, k, mirror):
        if callable(src):
            src(Frame(self, at, k, mirror))
            return
        if ":" in src and not src.startswith("rikoshet:"):
            # Чужая структура в NBT не копируется — «поставь здесь» в layout.json; рендер её подгрузит
            self.structures.append({"id": src, "pos": list(at), "rotation": k * 90, "mirror": mirror})
            return
        path = PREFABS / f"{src.split(':')[-1]}.nbt"
        vol = Volume.load(path)
        fr = Frame(self, at, k, mirror)
        for x, y, z in np.argwhere(vol.idx >= 0):
            name, props = vol.palette[vol.idx[x, y, z]]
            fr.set(int(x), int(y), int(z), name, vol.block_nbt.get((int(x), int(y), int(z))), **props)

    def prefab(self, src, at, facing="south", mirror=None):
        """Префаб: функция f(frame), имя NBT из tools/build/prefabs/ или id чужой структуры."""
        self._prefab(src, tuple(at), FACING_K[facing], mirror)

    def radial(self, n, fn, center, start=0.0):
        """n раз по кругу вокруг center = (x, z): fn(frame) получает рамку, повёрнутую к центру спиной.
        Координаты рамки поворачиваются на любой угол и округляются, а состояния — к ближайшим 90°."""
        for i in range(n):
            ang = start + 360.0 * i / n
            fn(_Radial(self, center, ang))

    def marker(self, name, at, facing="south"):
        """Точка для кода: структурный блок DATA «имя:сторона» (мод заменяет его воздухом)."""
        x, y, z = at
        self.set(x, y, z, "structure_block", {"id": "minecraft:structure_block", "mode": "DATA",
                                              "metadata": f"{name}:{facing}"}, keep=True, mode="data")
        self.markers.append((name, (x, y, z), facing))

    def display(self, model, at, scale=1.0, yaw=0.0, pitch=0.0, brightness=None, kind="item"):
        """Display-декор: в NBT не пишется, мод покажет его виртуальной сущностью по layout.json."""
        e = {"kind": kind, "model": model, "pos": list(at), "scale": scale, "yaw": yaw, "pitch": pitch}
        if brightness is not None:
            e["brightness"] = brightness
        self.displays.append(e)

    # ------------------------------------------------------------------------------------ свет
    def lights(self, target=11, level=15, limit=64, height=2):
        """Невидимые блоки light там, где на полу темнее target (свет — как в рендере `game`)."""
        from mc.scene import _solid_mask, block_light
        placed = 0
        for _ in range(limit):
            vol = self.volume()
            solid = _solid_mask(vol, self.reg)
            lvl = block_light(vol, self.reg, solid)[1:-1, 1:-1, 1:-1]     # без рамки
            air = ~solid & (vol.idx >= 0) | (vol.idx < 0)
            floor = np.zeros_like(solid)
            floor[:, 1:, :] = solid[:, :-1, :] & air[:, 1:, :]
            floor[:, :-1, :] &= air[:, 1:, :]
            dark = floor & (lvl < target)
            if not dark.any():
                break
            # Самая тёмная клетка с местом над ней
            cand = np.argwhere(dark)
            best = min(cand.tolist(), key=lambda p: (lvl[tuple(p)], p[1], p[2], p[0]))
            x, y, z = best
            ly = min(y + height, self.size[1] - 1)
            while ly > y and not air[x, ly, z]:
                ly -= 1
            self.set(x, ly, z, "light", level=level)
            placed += 1
        if placed:
            self.notes.append(f"невидимых источников света: {placed}")
        return placed

    # ------------------------------------------------------------------------------------ запись
    def volume(self):
        v = Volume(self.size, list(self.palette), self.idx.copy(), dict(self.block_nbt), name=Path(self.name).name)
        return v

    def connect(self):
        st.Connect(self.reg).run(self)

    def to_nbt(self):
        air_ids = {i for i, (n, _) in enumerate(self.palette) if n == AIR}
        pal = []
        for name, props in self.palette:
            e = {"Name": name}
            if props:
                e["Properties"] = dict(props)
            pal.append(e)
        root = {"DataVersion": nbt.Int(DATA_VERSION), "size": [nbt.Int(v) for v in self.size],
                "palette": pal, "blocks": [], "entities": []}
        pos = np.argwhere(self.idx >= 0)
        order = np.lexsort((pos[:, 0], pos[:, 2], pos[:, 1]))
        for x, y, z in pos[order]:
            s = int(self.idx[x, y, z])
            if s in air_ids and not self.keep_air:
                continue
            b = {"pos": [nbt.Int(int(x)), nbt.Int(int(y)), nbt.Int(int(z))], "state": nbt.Int(s)}
            data = self.block_nbt.get((int(x), int(y), int(z)))
            if data:
                b["nbt"] = data
            root["blocks"].append(b)
        return root

    def layout(self):
        return {"name": self.name, "size": list(self.size), "pieces": [{"structure": f"rikoshet:{self.name}",
                                                                        "pos": [0, 0, 0]}],
                "structures": self.structures, "displays": self.displays,
                "markers": [{"name": n, "pos": list(p), "facing": f} for n, p, f in self.markers]}

    def save(self, render=True, lint=True, connect=True):
        """NBT, layout.json (если есть что в него писать), лист рендера и отчёт линтера."""
        if connect:
            self.connect()
        self.out.parent.mkdir(parents=True, exist_ok=True)
        root = self.to_nbt()
        nbt.write(self.out, root)
        msg = [f"{self.out.relative_to(ROOT) if self.out.is_relative_to(ROOT) else self.out}: "
               f"{len(root['blocks'])} блоков, палитра {len(self.palette)}"]
        if self.structures or self.displays:
            lp = self.out.with_suffix(".layout.json")
            lp.write_text(json.dumps(self.layout(), ensure_ascii=False, indent=1) + "\n")
            msg.append(f"{lp.name}: структур {len(self.structures)}, декора {len(self.displays)}")
        msg += self.notes
        print("\n".join(msg))
        if render:
            subprocess.run([sys.executable, str(TOOLS / "render.py"), str(self.out), "--out",
                            str(OUT / Path(self.name).name)], check=False)
        if lint:
            subprocess.run([sys.executable, str(TOOLS / "lint.py"), str(self.out)], check=False)
        return self.out


class _Radial(Frame):
    def __init__(self, scene, center, ang):
        k = int(round(ang / 90.0)) % 4
        super().__init__(scene, (0, 0, 0), k)
        self.center, self.ang = center, math.radians(ang)

    def to_scene(self, x, y, z):
        ca, sa = math.cos(self.ang), math.sin(self.ang)
        rx = x * ca - z * sa
        rz = x * sa + z * ca
        return int(round(self.center[0] + rx)), y, int(round(self.center[1] + rz))


_SUB = np.array([[dx, dy, dz] for dx in (-.5, .5) for dy in (-.5, .5) for dz in (-.5, .5)])


def _smooth_shape(bits):
    """8 подвыборок клетки снаружи формы → («full» | «slab» | «stairs» | None, свойства).
    По центроиду занятых подвыборок: вниз — плита, вбок и вниз — ступень высокой частью в гору,
    вбок и вверх — перевёрнутая ступень, строго вбок (вертикальная стена) — ничего."""
    b = np.asarray(bits, bool)
    n = int(b.sum())
    if n <= 1:
        return None, {}
    if n >= 6:
        return "full", {}
    m = _SUB[b].mean(0)
    h = math.hypot(m[0], m[2])
    if h < 0.15:
        if m[1] < -0.3:
            return "slab", {"type": "bottom"}
        if m[1] > 0.3:
            return "slab", {"type": "top"}
        return None, {}
    if abs(m[1]) <= 0.05:
        return None, {}
    facing = ("east" if m[0] > 0 else "west") if abs(m[0]) >= abs(m[2]) else ("south" if m[2] > 0 else "north")
    return "stairs", {"half": "bottom" if m[1] < 0 else "top", "facing": facing}


def _toward(layer):
    """Куда смещена заполненная часть слоя 2×2 [x][z] — туда смотрят ступени (там их высокая часть)."""
    xs, zs = np.nonzero(layer)
    dx, dz = xs.mean() - 0.5, zs.mean() - 0.5
    if abs(dx) >= abs(dz):
        return "east" if dx > 0 else "west"
    return "south" if dz > 0 else "north"
