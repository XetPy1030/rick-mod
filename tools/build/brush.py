"""Кисти — чем заливать форму. Кисть — функция ctx → массив id блоков (по одному на клетку).

ctx (см. Fill в scene.py): pos (N, 3) — блоки, d — расстояние SDF в центре, normal (N, 3) — наружу,
exposed (N,) — сколько граней смотрят из формы, edge (N,) — ребро (открыты грани по двум осям),
rand(k) — детерминированный шум [0, 1) от позиции, сида сцены и k.

    hull = brush.gradient(["white_concrete", "calcite", "smooth_quartz"], axis="y", noise=0.15)
    wall = brush.mix({"stone_bricks": 60, "cracked_stone_bricks": 25, "mossy_stone_bricks": 15})
    floor = brush.pattern([["polished_andesite", "andesite"], ["andesite", "polished_andesite"]])
    roof = brush.by_normal(top="deepslate_tiles", side="deepslate_bricks")
    metal = brush.edges(brush.solid("light_gray_concrete"), "iron_block")
    ramp = brush.ramp("#e8f4f8", "#7fa6b5", steps=5)     # ряд блоков по цвету из каталога
"""
import json

import numpy as np

from mc.registry import full_id


def _ids(names):
    return [full_id(n) for n in names]


def as_brush(b):
    """Строка → сплошная кисть; кисть — как есть."""
    return solid(b) if isinstance(b, str) else b


def solid(name):
    name = full_id(name)
    return lambda ctx: np.full(len(ctx.pos), name, dtype=object)


def mix(weights, clump=0.0):
    """Смесь по весам. clump > 0 — пятнами размером около clump блоков, а не солью."""
    names = _ids(weights)
    w = np.array(list(weights.values()), float)
    cum = np.cumsum(w / w.sum())

    def f(ctx):
        r = ctx.noise(clump, 1) if clump else ctx.rand(1)
        return np.array(names, dtype=object)[np.minimum(np.searchsorted(cum, r), len(names) - 1)]
    return f


def gradient(names, axis="y", noise=0.0, lo=None, hi=None, center=None):
    """Переход по оси (x, y, z), по расстоянию от центра («radial», center = (x, z)) или вглубь («depth»)."""
    names = _ids(names)

    def f(ctx):
        if axis == "radial":
            c = np.asarray(center if center is not None else ctx.center[[0, 2]], float)
            v = np.hypot(ctx.pos[:, 0] + .5 - c[0], ctx.pos[:, 2] + .5 - c[1])
        elif axis == "depth":
            v = -ctx.d
        else:
            v = ctx.pos[:, "xyz".index(axis)].astype(float)
        a = v.min() if lo is None else lo
        b = v.max() if hi is None else hi
        t = (v - a) / max(b - a, 1e-9)
        if noise:
            t = t + (ctx.rand(2) - 0.5) * 2 * noise
        k = np.clip((t * len(names)).astype(int), 0, len(names) - 1)
        return np.array(names, dtype=object)[k]
    return f


def noise(names, scale=8.0):
    """Полосы 3D-шума: плавные пятна материалов, как выветривание."""
    names = _ids(names)

    def f(ctx):
        v = ctx.noise(scale, 3)
        k = np.clip((v * len(names)).astype(int), 0, len(names) - 1)
        return np.array(names, dtype=object)[k]
    return f


def pattern(tile, axes="xz", offset=(0, 0)):
    """Узор плиткой: tile — строки по второй оси, в строке — по первой. «ёлочка», шахматка, мозаика."""
    tile = [[full_id(n) for n in row] for row in tile]
    h, w = len(tile), len(tile[0])
    a, b = "xyz".index(axes[0]), "xyz".index(axes[1])
    arr = np.array(tile, dtype=object)

    def f(ctx):
        i = (ctx.pos[:, b] + offset[1]) % h
        j = (ctx.pos[:, a] + offset[0]) % w
        return arr[i, j]
    return f


def checker(a, b, size=1, axes="xz"):
    n = size
    return pattern([[a] * n + [b] * n] * n + [[b] * n + [a] * n] * n, axes)


def by_normal(top, side, bottom=None):
    """Разные кисти по направлению поверхности: сверху плиты, по бокам полный блок."""
    top, side = as_brush(top), as_brush(side)
    bottom = as_brush(bottom) if bottom is not None else side

    def f(ctx):
        ny = ctx.normal[:, 1]
        out = side(ctx)
        m = ny > 0.7
        if m.any():
            out[m] = top(ctx)[m]
        m = ny < -0.7
        if m.any():
            out[m] = bottom(ctx)[m]
        return out
    return f


def edges(base, edge):
    """Обводка рёбер и углов: где открыты грани по двум осям."""
    base, edge = as_brush(base), as_brush(edge)

    def f(ctx):
        out = base(ctx)
        if ctx.edge.any():
            out[ctx.edge] = edge(ctx)[ctx.edge]
        return out
    return f


def layers(spec, axis="y"):
    """Ярусы: [(до какой высоты, кисть)] — цоколь, стены, карниз."""
    spec = [(h, as_brush(b)) for h, b in spec]

    def f(ctx):
        v = ctx.pos[:, "xyz".index(axis)]
        out = spec[-1][1](ctx)
        for h, b in reversed(spec[:-1]):
            m = v <= h
            if m.any():
                out[m] = b(ctx)[m]
        return out
    return f


def greeble(base, details, chance=0.04):
    """«Грибли» — редкие детали по открытым граням большой плоскости: люки, кнопки, решётки."""
    base = as_brush(base)
    det = mix(details)

    def f(ctx):
        out = base(ctx)
        m = (ctx.exposed > 0) & (ctx.rand(7) < chance)
        if m.any():
            out[m] = det(ctx)[m]
        return out
    return f


def glass_tube(color="lime"):
    """Трубка: стекло снаружи, светящаяся середина."""
    return lambda ctx: np.where(ctx.d < -0.9, full_id("sea_lantern"), full_id(f"{color}_stained_glass")).astype(object)


# ------------------------------------------------------------------------------------------------ по цвету
_COLORS = None


def block_colors():
    """Средний цвет боковой грани у полных непрозрачных блоков: {id: (r, g, b)}. Кешируется в run/visual/cache."""
    global _COLORS
    if _COLORS is not None:
        return _COLORS
    from mc import OUT
    path = OUT / "cache" / "block-colors.json"
    if path.exists():
        _COLORS = {k: tuple(v) for k, v in json.loads(path.read_text()).items()}
        return _COLORS
    from mc.views import Ctx
    ctx = Ctx()
    out = {}
    for name, b in ctx.registry.blocks.items():
        if not (b.get("solid") and b.get("full") and b.get("item")) or b.get("block_entity") or b.get("falling"):
            continue
        if any(w in name for w in ("_ore", "command_block", "structure_block", "jigsaw", "spawner", "infested",
                                   "shulker", "_leaves", "grass_block", "_log", "barrier")):
            continue
        quads = ctx.models.quads(name, {})
        sides = [q for q in quads if abs(q.normal[1]) < 0.5] or quads
        if not sides or ctx.models.tint(name, sides[0].tint) is not None:
            continue
        t = sides[0].tex.rgba
        if (t[..., 3] < 0.99).any():
            continue
        out[name] = tuple(float(v) for v in (t[..., :3].reshape(-1, 3).mean(0) * 255))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(out))
    _COLORS = out
    return out


def _hex(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float)


def _lab(rgb):
    """sRGB → CIELAB: подбор по цвету на глаз, а не по сумме каналов."""
    c = np.asarray(rgb, float) / 255
    c = np.where(c > 0.04045, ((c + 0.055) / 1.055) ** 2.4, c / 12.92)
    m = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = c @ m.T / np.array([0.9505, 1.0, 1.089])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)


def nearest(color, exclude=(), only=None):
    """Ближайший по цвету полный блок из каталога."""
    cols = block_colors()
    names = [n for n in cols if n not in exclude and (only is None or only(n))]
    lab = _lab(np.array([cols[n] for n in names]))
    d = np.linalg.norm(lab - _lab(_hex(color) if isinstance(color, str) else np.asarray(color, float)), axis=1)
    return names[int(np.argmin(d))]


def ramp_blocks(c0, c1, steps=5, only=None):
    """Ряд блоков от цвета к цвету: разные блоки на каждом шаге."""
    a, b = _hex(c0), _hex(c1)
    out = []
    for k in range(steps):
        c = a + (b - a) * k / max(steps - 1, 1)
        out.append(nearest(c, exclude=out, only=only))
    return out


def ramp(c0, c1, steps=5, axis="y", noise=0.1, only=None):
    return gradient(ramp_blocks(c0, c1, steps, only), axis=axis, noise=noise)


def from_palette(zone, part="middle", top=8):
    """Смесь из палитры доски зоны (tools/build/palettes/<зона>.json, refs.py board --export):
    part — «bottom», «middle», «top» (ярусы образцов)."""
    from pathlib import Path
    p = Path(__file__).resolve().parent / "palettes" / f"{zone}.json"
    d = json.loads(p.read_text(encoding="utf-8"))
    layer = d["layers"][{"bottom": 0, "middle": 1, "top": 2}[part]]
    from mc.kinds import family_block
    from mc.registry import default
    reg = default()
    weights = {}
    for fam, share in layer[:top]:
        b = family_block(fam, full=True)
        if reg.block(b) and reg.block(b).get("full"):
            weights[b] = weights.get(b, 0) + share
    return mix(weights, clump=3)
