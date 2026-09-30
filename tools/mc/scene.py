"""Сцена: грани блоков объёма, манекены на точках, заглушки — и их отрисовка камерой.

Свет: flat — только затенение граней, как в игре; game — блочный свет заливкой от светящихся блоков
плюс ambient измерения (в Цитадели неба нет — sky_light_factor 0).
"""
import math

import numpy as np

from .models import DIRS, Quad
from .raster import Camera, Canvas, clip_near

# Блоки, которые на рендере не рисуем: точки для кода показываются отметками
SKIP = {"minecraft:structure_block", "minecraft:structure_void", "minecraft:jigsaw"}
FACING_YAW = {"south": 0, "west": -90, "north": 180, "east": 90}


def diffuse(n):
    """Затенение грани по нормали, как у блоков в игре: верх 1, низ 0,5, север и юг 0,8, запад и восток 0,6."""
    x, y, z = n
    return min(x * x * 0.6 + y * y * ((3 + y) / 4) + z * z * 0.8, 1.0)


def light_curve(level, ambient):
    """Уровень света 0..15 → яркость, как в карте освещения игры (без учёта цвета)."""
    f = level / 15.0
    g = f / (4 - 3 * f)
    return ambient + (1 - ambient) * g


def _solid_mask(vol, registry):
    solid = np.zeros(len(vol.palette), dtype=bool)
    for i, (name, _) in enumerate(vol.palette):
        solid[i] = registry.solid(name) and name not in SKIP
    m = np.zeros(vol.size, dtype=bool)
    ok = vol.idx >= 0
    m[ok] = solid[vol.idx[ok]]
    return m


def block_light(vol, registry, solid):
    """Блочный свет заливкой: −1 за шаг, через непрозрачные блоки не идёт. Массив с рамкой в 1 блок."""
    src = np.zeros(len(vol.palette), dtype=np.int16)
    for i, (name, props) in enumerate(vol.palette):
        src[i] = registry.light(name, props)
    L = np.zeros(tuple(s + 2 for s in vol.size), dtype=np.int16)
    ok = vol.idx >= 0
    inner = L[1:-1, 1:-1, 1:-1]
    inner[ok] = src[vol.idx[ok]]
    passable = np.ones_like(L, dtype=bool)
    passable[1:-1, 1:-1, 1:-1] = ~solid
    for _ in range(15):
        nb = np.zeros_like(L)
        nb[1:] = np.maximum(nb[1:], L[:-1])
        nb[:-1] = np.maximum(nb[:-1], L[1:])
        nb[:, 1:] = np.maximum(nb[:, 1:], L[:, :-1])
        nb[:, :-1] = np.maximum(nb[:, :-1], L[:, 1:])
        nb[:, :, 1:] = np.maximum(nb[:, :, 1:], L[:, :, :-1])
        nb[:, :, :-1] = np.maximum(nb[:, :, :-1], L[:, :, 1:])
        new = np.maximum(L, np.where(passable, nb - 1, 0))
        if np.array_equal(new, L):
            break
        L = new
    return L


class Item:
    """Грань, готовая к отрисовке: вершины в мире, uv, текстура, множитель цвета."""
    __slots__ = ("v", "uv", "tex", "mul", "normal", "mode", "tag", "double")

    def __init__(self, v, uv, tex, mul, normal, mode, tag=-1, double=False):
        self.v, self.uv, self.tex, self.mul, self.normal, self.mode, self.tag, self.double = v, uv, tex, mul, normal, mode, tag, double


class Scene:
    def __init__(self, assets, registry, models):
        self.assets, self.registry, self.models = assets, registry, models
        self.items = []
        self.lo = np.array([np.inf] * 3)
        self.hi = np.array([-np.inf] * 3)
        self.notes = {}          # что нарисовано заглушкой: id → сколько

    def _grow(self, lo, hi):
        self.lo = np.minimum(self.lo, lo)
        self.hi = np.maximum(self.hi, hi)

    # ---------------------------------------------------------------- постройка
    def add_volume(self, vol, offset=(0, 0, 0), light="flat", ambient=0.35, below=None, fade=0.0):
        """below — рисовать только блоки с y ≤ below (разрез и планы этажей); fade — затемнение на блок глубины под срезом."""
        reg, models = self.registry, self.models
        off = np.array(offset, dtype=float)
        solid = _solid_mask(vol, reg)
        L = block_light(vol, reg, solid) if light == "game" else None
        sx, sy, sz = vol.size
        # Блок внутри сплошного массива не виден: все 6 соседей непрозрачны
        pad = np.zeros((sx + 2, sy + 2, sz + 2), dtype=bool)
        pad[1:-1, 1:-1, 1:-1] = solid
        enclosed = solid & pad[:-2, 1:-1, 1:-1] & pad[2:, 1:-1, 1:-1] & pad[1:-1, :-2, 1:-1] \
            & pad[1:-1, 2:, 1:-1] & pad[1:-1, 1:-1, :-2] & pad[1:-1, 1:-1, 2:]
        if below is not None:
            enclosed[:, below:, :] = False     # под срезом крыша не прикрывает
        names = [p[0] for p in vol.palette]
        for x, y, z in zip(*np.nonzero((vol.idx >= 0) & ~enclosed)):
            if below is not None and y > below:
                continue
            i = vol.idx[x, y, z]
            name, props = vol.palette[i]
            if name in SKIP or name == "minecraft:air":
                continue
            h = (x * 3129871) ^ (z * 116129781) ^ y
            quads = models.quads(name, props, h & 0x7FFFFFFF)
            if not quads:
                if reg.block(name) and reg.block(name).get("render") != "invisible" and name != "minecraft:water":
                    quads = self._fallback(name)
                elif name == "minecraft:water":
                    quads = self._water()
                else:
                    continue
            base = np.array([x, y, z], dtype=float)
            dim = 1.0 - fade * (below - y) if below is not None and fade else 1.0
            for q in quads:
                if q.cull:
                    dx, dy, dz = DIRS[q.cull]
                    nx, ny, nz = x + dx, y + dy, z + dz
                    if 0 <= nx < sx and 0 <= ny < sy and 0 <= nz < sz and not (below is not None and ny > below):
                        j = vol.idx[nx, ny, nz]
                        if j >= 0 and (solid[nx, ny, nz] or (names[j] == name and q.tex.mode != "opaque")):
                            continue
                mul = np.ones(3)
                t = models.tint(name, q.tint)
                if t is not None:
                    mul = mul * np.array(t)
                if q.shade:
                    mul = mul * diffuse(q.normal)
                if L is not None:
                    d = q.cull or _dir_of(q.normal)
                    lx, ly, lz = (x + 1, y + 1, z + 1)
                    if d:
                        ddx, ddy, ddz = DIRS[d]
                        lx, ly, lz = lx + ddx, ly + ddy, lz + ddz
                    lvl = max(L[lx, ly, lz], L[x + 1, y + 1, z + 1])
                    mul = mul * light_curve(lvl, ambient)
                if dim != 1.0:
                    mul = mul * max(dim, 0.25)
                self.items.append(Item(q.v + base + off, q.uv, q.tex, mul, q.normal, q.tex.mode, int(i)))
        self._grow(off, off + np.array(vol.size, dtype=float))

    def _fallback(self, name):
        """Блок-сущность без элементов модели: куб цвета карты."""
        self.notes[name] = self.notes.get(name, 0) + 1
        key = "fallback:" + name
        tex = self.assets._tex.get(key)
        if tex is None:
            from PIL import Image
            from .assets import Texture
            c = self.registry.map_color(name, 2)
            img = Image.new("RGBA", (16, 16), c + (255,))
            px = img.load()
            for k in range(16):
                px[k, 0] = px[k, 15] = px[0, k] = px[15, k] = tuple(int(v * 0.7) for v in c) + (255,)
            tex = Texture(key, img)
            self.assets._tex[key] = tex
        return box_quads((1, 0, 1), (15, 14, 15), tex, cull=False)

    def _water(self):
        tex = self.assets.texture("minecraft:block/water_still")
        quads = box_quads((0, 0, 0), (16, 14.2, 16), tex, cull=True)
        for q in quads:
            q.tint = 0
        return quads

    # ---------------------------------------------------------------- манекены
    def add_player(self, skin, feet, facing="south", slim=False, scale=0.9375, tag=-2):
        """Модель игрока со скином (PIL 64×64) — как манекен в игре. feet — середина под ногами."""
        from .assets import Texture
        tex = Texture("skin", skin.convert("RGBA"))
        base = Texture("skin_base", skin.convert("RGBA"))
        base.mode = "cutout"
        tex.mode = "cutout"
        aw = 3 if slim else 4
        parts = [
            ((-4, 24, -4), (8, 8, 8), (0, 0), (32, 0), 0.5),            # голова и волосы
            ((-4, 12, -2), (8, 12, 4), (16, 16), (16, 32), 0.25),       # торс и куртка
            ((-4 - aw, 12, -2), (aw, 12, 4), (40, 16), (40, 32), 0.25),  # правая рука (−x)
            ((4, 12, -2), (aw, 12, 4), (32, 48), (48, 48), 0.25),       # левая рука
            ((-4, 0, -2), (4, 12, 4), (0, 16), (0, 32), 0.25),          # правая нога
            ((0, 0, -2), (4, 12, 4), (16, 48), (0, 48), 0.25),          # левая нога
        ]
        yaw = math.radians(FACING_YAW.get(facing, 0))
        c, s = math.cos(yaw), math.sin(yaw)
        rot = np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
        feet = np.array(feet, dtype=float)
        for origin, dims, uv, uv2, inflate in parts:
            for layer, (u, v), inf, t, double in ((0, uv, 0.0, base, False), (1, uv2, inflate, tex, True)):
                for q in skin_box(origin, dims, u, v, inf, t):
                    pts = q.v * scale / 16.0
                    pts = pts @ rot.T + feet
                    n = rot @ q.normal
                    mul = np.ones(3) * diffuse(n)
                    self.items.append(Item(pts, q.uv, t, mul, n, t.mode, tag, double))
        self._grow(feet - 0.6, feet + np.array([0.6, 2.0, 0.6]))

    # ---------------------------------------------------------------- отрисовка
    def render(self, cam, bg=(0, 0, 0, 0)):
        if cam.ortho and not cam.width:
            cam.fit(self.lo, self.hi)
        canvas = Canvas(cam.width, cam.height)
        opaque, trans = [], []
        for it in self.items:
            if not it.double:
                if cam.ortho:
                    if float(np.dot(it.normal, cam.f)) > 1e-6:
                        continue
                elif float(np.dot(it.normal, it.v.mean(axis=0) - cam.eye)) > 1e-6:
                    continue
            (trans if it.mode == "translucent" else opaque).append(it)
        for it in opaque:
            self._draw(canvas, cam, it)
        # Полупрозрачное — от дальнего к ближнему
        trans.sort(key=lambda it: -float(np.mean(cam.view(it.v)[:, 2])))
        for it in trans:
            self._draw(canvas, cam, it)
        return canvas

    @staticmethod
    def _draw(canvas, cam, it):
        v = cam.view(it.v)
        uv = it.uv
        if not cam.ortho:
            if (v[:, 2] < 0.05).all():
                return
            if (v[:, 2] < 0.05).any():
                v, uv = clip_near(v, uv)
                if len(v) < 3:
                    return
        sx, sy, z, iw = cam.project(v)
        for k in range(1, len(v) - 1):
            ids = [0, k, k + 1]
            canvas.tri(sx[ids], sy[ids], z[ids], iw[ids], uv[ids], it.tex, it.mul, it.mode, it.tag)


def _dir_of(n):
    ax = int(np.argmax(np.abs(n)))
    vec = [0, 0, 0]
    vec[ax] = 1 if n[ax] > 0 else -1
    for k, d in DIRS.items():
        if d == tuple(vec):
            return k
    return None


def box_quads(f, t, tex, cull=True):
    """Куб от f до t (в 1/16 блока) с авто-UV — для заглушек и воды."""
    from .models import _auto_uv, _corners
    out = []
    for d, n in DIRS.items():
        corners = np.array(_corners(d, np.array(f, dtype=float), np.array(t, dtype=float)))
        uv = np.array([_auto_uv(d, p) for p in corners]) * np.array([tex.w / 16.0, tex.h / 16.0])
        out.append(Quad(corners / 16.0, uv, tex, np.array(n, dtype=float), d if cull else None, -1, True))
    return out


# Грани коробки модели игрока: углы ЛВ, ПВ, ПН, ЛН и прямоугольник развёртки (du, dv, ширина, высота)
def skin_box(origin, dims, u, v, inflate, tex):
    """Коробка ModelPart с box-UV скина. Мир: лицом на юг (+z), правая сторона игрока — запад (−x)."""
    x0, y0, z0 = (origin[i] - inflate for i in range(3))
    w, h, d = dims
    x1, y1, z1 = origin[0] + w + inflate, origin[1] + h + inflate, origin[2] + d + inflate
    faces = {
        "up": ([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (u + d, v, w, d)),
        "down": ([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (u + d + w, v, w, d)),
        "south": ([(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)], (u + d, v + d, w, h)),
        "north": ([(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)], (u + 2 * d + w, v + d, w, h)),
        "west": ([(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)], (u, v + d, d, h)),
        "east": ([(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)], (u + d + w, v + d, d, h)),
    }
    k = tex.w / 64.0
    out = []
    for dname, (corners, (tu, tv, tw, th)) in faces.items():
        uv = np.array([(tu, tv), (tu + tw, tv), (tu + tw, tv + th), (tu, tv + th)], dtype=float) * k
        out.append(Quad(np.array(corners, dtype=float), uv, tex, np.array(DIRS[dname], dtype=float), None, -1, True))
    return out


def orbit(azimuth, elevation, scale=16.0):
    return Camera.orbit(azimuth, elevation, ortho=True, scale=scale)
