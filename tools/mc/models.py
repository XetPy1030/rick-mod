"""Модели блоков Java: blockstate → модель → грани-четырёхугольники в координатах блока (0..1).

Поддержано: variants и multipart с when/OR/AND, взвешенные списки (выбор по позиции), цепочка parent,
переменные текстур, элементы с rotation/rescale, грани с uv/rotation/cullface/tintindex, shade,
поворот модели x/y и uvlock. Блоки-сущности без элементов (сундуки, таблички, головы) — заглушкой
цвета карты: рендер помечает их в отчёте.
"""
import math

import numpy as np

from .registry import full_id

DIRS = {
    "down": (0, -1, 0), "up": (0, 1, 0),
    "north": (0, 0, -1), "south": (0, 0, 1),
    "west": (-1, 0, 0), "east": (1, 0, 0),
}
DIR_BY_VEC = {v: k for k, v in DIRS.items()}

# Блоки, у которых нечего рисовать
INVISIBLE = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:light", "minecraft:barrier",
             "minecraft:structure_void", "minecraft:moving_piston"}

# Тонировка по tintindex: вода, листва, трава — цвета равнин; у Цитадели своих нет
WATER = (0x3F / 255, 0x76 / 255, 0xE4 / 255)
GRASS = (0x91 / 255, 0xBD / 255, 0x59 / 255)
FOLIAGE = (0x77 / 255, 0xAB / 255, 0x2F / 255)
FIXED_TINT = {
    "minecraft:birch_leaves": (0x80 / 255, 0xA7 / 255, 0x55 / 255),
    "minecraft:spruce_leaves": (0x61 / 255, 0x99 / 255, 0x61 / 255),
    "minecraft:lily_pad": (0x20 / 255, 0x80 / 255, 0x30 / 255),
    "minecraft:redstone_wire": (0.6, 0.05, 0.02),
    "minecraft:water_cauldron": WATER,
}


class Quad:
    """Грань: v — 4×3 вершины в блоках, uv — 4×2 в пикселях текстуры, normal — нормаль после поворотов."""
    __slots__ = ("v", "uv", "tex", "normal", "cull", "tint", "shade")

    def __init__(self, v, uv, tex, normal, cull, tint, shade):
        self.v, self.uv, self.tex, self.normal, self.cull, self.tint, self.shade = v, uv, tex, normal, cull, tint, shade


def _auto_uv(d, p):
    x, y, z = p
    if d == "down":
        return x, 16 - z
    if d == "up":
        return x, z
    if d == "north":
        return 16 - x, 16 - y
    if d == "south":
        return x, 16 - y
    if d == "west":
        return z, 16 - y
    return 16 - z, 16 - y  # east


def _corners(d, f, t):
    """Углы грани в порядке ЛВ, ПВ, ПН, ЛН — как лежит текстура без поворота."""
    x0, y0, z0 = f
    x1, y1, z1 = t
    return {
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
    }[d]


def _rot_matrix(axis, deg):
    """Правый поворот вокруг оси на deg градусов."""
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def _model_matrix(rx, ry):
    """Поворот модели из blockstate: сначала x, потом y; «y: 90» переводит восток в юг."""
    return _rot_matrix("y", -ry) @ _rot_matrix("x", -rx)


def _rot_dir(m, d):
    v = m @ np.array(DIRS[d], dtype=float)
    return DIR_BY_VEC[tuple(int(round(c)) for c in v)]


class Models:
    def __init__(self, assets, registry):
        self.assets = assets
        self.registry = registry
        self._resolved = {}
        self._baked = {}
        self._cache = {}
        self.missing = set()     # блоки без blockstate или без модели

    # ---------------------------------------------------------------- модели
    def resolve(self, model_id):
        """Модель с учётом parent: (элементы, текстуры). None — нет файла."""
        model_id = full_id(model_id)
        if model_id in self._resolved:
            return self._resolved[model_id]
        chain = []
        cur = model_id
        while cur and len(chain) < 32:
            if cur.startswith("minecraft:builtin/") or cur.startswith("builtin/"):
                break
            m = self.assets.model(cur)
            if m is None:
                if not chain:
                    self._resolved[model_id] = None
                    return None
                break
            chain.append(m)
            cur = full_id(m["parent"]) if "parent" in m else None
        textures = {}
        elements = None
        for m in reversed(chain):
            textures.update(m.get("textures", {}))
            if "elements" in m:
                elements = m["elements"]
        res = (elements or [], textures)
        self._resolved[model_id] = res
        return res

    def _texture(self, ref, textures):
        """Ссылка «#имя» по цепочке; в 26.x значение бывает объектом {sprite, force_translucent}."""
        translucent = False
        seen = 0
        while seen < 16:
            if isinstance(ref, dict):
                translucent = translucent or ref.get("force_translucent", False)
                ref = ref.get("sprite", "")
            if not (isinstance(ref, str) and ref.startswith("#")):
                break
            ref = textures.get(ref[1:], "")
            seen += 1
        if not ref or not isinstance(ref, str) or ref.startswith("#"):
            return self.assets.texture("rikoshet:missing")
        tex = self.assets.texture(full_id(ref))
        if translucent and tex.mode != "translucent":
            key = tex.id + "#translucent"
            if key not in self.assets._tex:
                import copy
                t2 = copy.copy(tex)
                t2.mode = "translucent"
                self.assets._tex[key] = t2
            tex = self.assets._tex[key]
        return tex

    def bake(self, model_id, rx=0, ry=0, uvlock=False):
        """Грани модели с поворотом из blockstate."""
        key = (full_id(model_id), rx, ry, uvlock)
        if key in self._baked:
            return self._baked[key]
        resolved = self.resolve(model_id)
        if resolved is None:
            self.missing.add(full_id(model_id))
            self._baked[key] = []
            return []
        elements, textures = resolved
        mm = _model_matrix(rx, ry)
        rotated = rx % 360 or ry % 360
        center = np.array([8.0, 8.0, 8.0])
        quads = []
        for el in elements:
            f = np.array(el["from"], dtype=float)
            t = np.array(el["to"], dtype=float)
            er = el.get("rotation")
            if er and "angle" in er and er.get("axis") in ("x", "y", "z"):
                em = _rot_matrix(er["axis"], er["angle"])
                origin = np.array(er.get("origin", [8, 8, 8]), dtype=float)
                scale = np.ones(3)
                if er.get("rescale") and er["angle"] % 90:
                    k = 1 / math.cos(math.radians(abs(er["angle"])))
                    scale = np.array([1.0 if a == er["axis"] else k for a in "xyz"])
            else:
                em = None
            shade = el.get("shade", True)
            for d, face in el.get("faces", {}).items():
                if d not in DIRS or "texture" not in face:
                    continue
                corners = np.array(_corners(d, f, t), dtype=float)
                auto = np.array([_auto_uv(d, p) for p in corners])
                tex = self._texture(face["texture"], textures)
                # UV грани: доля по авто-UV → поворот текстуры → явный прямоугольник uv
                umin, vmin = auto.min(axis=0)
                umax, vmax = auto.max(axis=0)
                s = (auto[:, 0] - umin) / (umax - umin) if umax > umin else np.zeros(4)
                q = (auto[:, 1] - vmin) / (vmax - vmin) if vmax > vmin else np.zeros(4)
                r = (face.get("rotation", 0) // 90) % 4
                for _ in range(r):
                    s, q = q, 1 - s
                if "uv" in face:
                    u1, v1, u2, v2 = face["uv"]
                else:
                    u1, v1, u2, v2 = umin, vmin, umax, vmax
                uv = np.stack([u1 + s * (u2 - u1), v1 + q * (v2 - v1)], axis=1)
                normal = np.array(DIRS[d], dtype=float)
                pts = corners
                if em is not None:
                    pts = (pts - origin) @ em.T * scale + origin
                    normal = em @ normal
                pts = (pts - center) @ mm.T + center
                normal = mm @ normal
                cull = face.get("cullface")
                if cull == "bottom":
                    cull = "down"
                if cull in DIRS and rotated:
                    cull = _rot_dir(mm, cull)
                elif cull not in DIRS:
                    cull = None
                if uvlock and rotated and em is None:
                    nd = _rot_dir(mm, d)
                    uv = np.array([_auto_uv(nd, p) for p in pts])
                uv = uv * np.array([tex.w / 16.0, tex.h / 16.0])
                quads.append(Quad(pts / 16.0, uv, tex, normal, cull, face.get("tintindex", -1), shade))
        self._baked[key] = quads
        return quads

    # ---------------------------------------------------------------- blockstates
    @staticmethod
    def _when(when, props):
        if "OR" in when:
            return any(Models._when(w, props) for w in when["OR"])
        if "AND" in when:
            return all(Models._when(w, props) for w in when["AND"])
        for k, v in when.items():
            if props.get(k) not in str(v).lower().split("|"):
                return False
        return True

    @staticmethod
    def _pick(entry, h):
        if isinstance(entry, list):
            total = sum(e.get("weight", 1) for e in entry)
            k = h % max(total, 1)
            for e in entry:
                k -= e.get("weight", 1)
                if k < 0:
                    return e
            return entry[0]
        return entry

    def _apply(self, entry, h):
        e = self._pick(entry, h)
        return self.bake(e["model"], e.get("x", 0), e.get("y", 0), e.get("uvlock", False))

    def quads(self, name, props=None, h=0):
        """Грани блока в состоянии props (недостающие свойства — по умолчанию из каталога)."""
        name = full_id(name)
        if name in INVISIBLE:
            return []
        full = self.registry.props(name, props)
        key = (name, tuple(sorted(full.items())), h)
        if key in self._cache:
            return self._cache[key]
        bs = self.assets.blockstate(name)
        out = []
        if bs is None:
            self.missing.add(name)
        elif "variants" in bs:
            for k, entry in bs["variants"].items():
                cond = dict(p.split("=", 1) for p in k.split(",") if "=" in p)
                if all(full.get(a) == b for a, b in cond.items()):
                    out = self._apply(entry, h)
                    break
        elif "multipart" in bs:
            for part in bs["multipart"]:
                if "when" not in part or self._when(part["when"], full):
                    out = out + self._apply(part["apply"], h)
        self._cache[key] = out
        return out

    def tint(self, name, index):
        if index < 0:
            return None
        name = full_id(name)
        if name in FIXED_TINT:
            return FIXED_TINT[name]
        if not name.startswith("minecraft:"):
            return None
        if name in ("minecraft:water", "minecraft:bubble_column") or "cauldron" in name:
            return WATER
        if self.registry.has_tag(name, "minecraft:leaves") or name == "minecraft:vine":
            return FOLIAGE
        return GRASS
