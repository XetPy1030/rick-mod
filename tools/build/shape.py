"""Формы — функции расстояния (SDF) на numpy: отрицательно внутри, ноль на границе, единица — блок.

Координаты непрерывные: блок (x, y, z) занимает [x, x+1) × [y, y+1) × [z, z+1), его центр — (x+.5, y+.5, z+.5).
Центр на целых — чётный поперечник, на половинках — нечётный.

    dome = shape.sphere((48, 10, 48), 20).cut(y0=10).shell(1)
    ring = shape.cylinder((48, 2, 48), r=40, h=2) - shape.cylinder((48, 2, 48), r=6, h=2)
    tube = shape.pipe([(10, 3, 10), (20, 9, 14), (30, 9, 30)], r=1)

Формы складываются (|), вычитаются (-), пересекаются (&), сливаются мягко (.smooth(other, k)),
становятся стенкой (.shell(t)), сдвигаются (.move) и поворачиваются вокруг вертикали (.rot_y).
"""
import math

import numpy as np


class Shape:
    def __init__(self, f, lo, hi):
        self.f = f
        self.lo = np.asarray(lo, float)
        self.hi = np.asarray(hi, float)

    def __call__(self, p):
        return self.f(np.asarray(p, float))

    def __or__(self, o):
        return Shape(lambda p: np.minimum(self.f(p), o.f(p)), np.minimum(self.lo, o.lo), np.maximum(self.hi, o.hi))

    def __sub__(self, o):
        return Shape(lambda p: np.maximum(self.f(p), -o.f(p)), self.lo, self.hi)

    def __and__(self, o):
        return Shape(lambda p: np.maximum(self.f(p), o.f(p)), np.maximum(self.lo, o.lo), np.minimum(self.hi, o.hi))

    def smooth(self, o, k=2.0):
        """Мягкое слияние: шов скругляется радиусом около k."""
        def f(p):
            a, b = self.f(p), o.f(p)
            h = np.clip(0.5 + 0.5 * (b - a) / k, 0, 1)
            return b * (1 - h) + a * h - k * h * (1 - h)
        return Shape(f, np.minimum(self.lo, o.lo) - k, np.maximum(self.hi, o.hi) + k)

    def shell(self, t=1.0):
        """Стенка толщиной t по границе формы (внутрь и наружу поровну)."""
        return Shape(lambda p: np.abs(self.f(p)) - t / 2, self.lo - t, self.hi + t)

    def inner(self, t=1.0):
        """Стенка толщиной t внутрь: купол, который не толще снаружи."""
        return self - Shape(lambda p: self.f(p) + t, self.lo, self.hi)

    def move(self, dx=0, dy=0, dz=0):
        d = np.array([dx, dy, dz], float)
        return Shape(lambda p: self.f(p - d), self.lo + d, self.hi + d)

    def rot_y(self, deg, center=None):
        """Поворот вокруг вертикали через center (по умолчанию — центр рамки), по часовой при взгляде сверху."""
        c = (self.lo + self.hi) / 2 if center is None else np.asarray(center, float)
        a = math.radians(deg)
        ca, sa = math.cos(a), math.sin(a)

        def f(p):
            q = p - c
            x = q[..., 0] * ca + q[..., 2] * sa
            z = -q[..., 0] * sa + q[..., 2] * ca
            return self.f(np.stack([x, q[..., 1], z], -1) + c)
        r = np.linalg.norm((self.hi - self.lo)[[0, 2]]) / 2
        return Shape(f, [c[0] - r, self.lo[1], c[2] - r], [c[0] + r, self.hi[1], c[2] + r])

    def cut(self, x0=None, x1=None, y0=None, y1=None, z0=None, z1=None):
        """Оставить часть между плоскостями: dome = sphere.cut(y0=10)."""
        s = self
        for axis, v, sign in ((0, x0, -1), (0, x1, 1), (1, y0, -1), (1, y1, 1), (2, z0, -1), (2, z1, 1)):
            if v is None:
                continue
            lo, hi = s.lo.copy(), s.hi.copy()
            if sign < 0:
                lo[axis] = max(lo[axis], v)
            else:
                hi[axis] = min(hi[axis], v)
            s = s & Shape(lambda p, a=axis, v=v, sg=sign: sg * (p[..., a] - v), lo, hi)
        return s


# ------------------------------------------------------------------------------------------------- тела
def box(lo, hi):
    """Коробка по блокам включительно: box((0, 0, 0), (4, 2, 4)) — 5×3×5 блоков."""
    lo = np.asarray(lo, float)
    hi = np.asarray(hi, float) + 1
    c, half = (lo + hi) / 2, (hi - lo) / 2

    def f(p):
        q = np.abs(p - c) - half
        return np.linalg.norm(np.maximum(q, 0), axis=-1) + np.minimum(q.max(-1), 0)
    return Shape(f, lo, hi)


def sphere(c, r):
    c = np.asarray(c, float)
    return Shape(lambda p: np.linalg.norm(p - c, axis=-1) - r, c - r, c + r)


def ellipsoid(c, r):
    """Эллипсоид с полуосями r = (rx, ry, rz); расстояние приближённое, для заливки хватает."""
    c, r = np.asarray(c, float), np.asarray(r, float)

    def f(p):
        q = (p - c) / r
        k0 = np.linalg.norm(q, axis=-1)
        k1 = np.linalg.norm(q / r, axis=-1)
        return k0 * (k0 - 1) / np.maximum(k1, 1e-9)
    return Shape(f, c - r, c + r)


def cylinder(c, r, h, axis="y"):
    """Цилиндр: c — центр основания, h — длина вдоль оси."""
    c = np.asarray(c, float)
    a = "xyz".index(axis)
    o = [i for i in range(3) if i != a]

    def f(p):
        q = p - c
        d_r = np.sqrt(q[..., o[0]] ** 2 + q[..., o[1]] ** 2) - r
        d_h = np.maximum(-q[..., a], q[..., a] - h)
        return np.minimum(np.maximum(d_r, d_h), 0) + np.sqrt(np.maximum(d_r, 0) ** 2 + np.maximum(d_h, 0) ** 2)
    lo, hi = c - r, c + r
    lo[a], hi[a] = c[a], c[a] + h
    return Shape(f, lo, hi)


def disc(c, r, h=1):
    return cylinder(c, r, h)


def cone(c, r0, r1, h):
    """Усечённый конус вдоль y: радиус r0 внизу, r1 наверху. Расстояние приближённое."""
    c = np.asarray(c, float)

    def f(p):
        q = p - c
        t = np.clip(q[..., 1] / h, 0, 1)
        rr = r0 + (r1 - r0) * t
        k = math.cos(math.atan2(abs(r0 - r1), h))
        d_r = (np.sqrt(q[..., 0] ** 2 + q[..., 2] ** 2) - rr) * k
        d_h = np.maximum(-q[..., 1], q[..., 1] - h)
        return np.minimum(np.maximum(d_r, d_h), 0) + np.sqrt(np.maximum(d_r, 0) ** 2 + np.maximum(d_h, 0) ** 2)
    m = max(r0, r1)
    return Shape(f, c - [m, 0, m], c + [m, h, m])


def torus(c, R, r):
    """Тор в горизонтальной плоскости: R — радиус кольца, r — толщина."""
    c = np.asarray(c, float)

    def f(p):
        q = p - c
        xz = np.sqrt(q[..., 0] ** 2 + q[..., 2] ** 2) - R
        return np.sqrt(xz ** 2 + q[..., 1] ** 2) - r
    return Shape(f, c - [R + r, r, R + r], c + [R + r, r, R + r])


def capsule(a, b, r):
    a, b = np.asarray(a, float), np.asarray(b, float)
    ab = b - a
    L = max(float(ab @ ab), 1e-9)

    def f(p):
        t = np.clip(((p - a) @ ab) / L, 0, 1)
        return np.linalg.norm(p - a - t[..., None] * ab, axis=-1) - r
    return Shape(f, np.minimum(a, b) - r, np.maximum(a, b) + r)


def pipe(points, r):
    """Труба по ломаной; концы скруглены."""
    pts = [np.asarray(p, float) + 0.5 for p in points]
    s = capsule(pts[0], pts[1], r)
    for a, b in zip(pts[1:], pts[2:]):
        s = s | capsule(a, b, r)
    return s


def arch(c, width, height, depth, axis="z"):
    """Проём с полукруглым верхом (чтобы вычитать из стены): c — середина низа, axis — сквозь что смотрим."""
    c = np.asarray(c, float)
    r = width / 2
    along = "x" if axis == "z" else "z"
    a = "xyz".index(along)
    lo = c.copy()
    lo[a] -= r
    hi = c.copy()
    hi[a] += r
    hi[1] += height - r
    d = "xyz".index(axis)
    lo[d] -= depth / 2
    hi[d] += depth / 2
    rect = Shape(box(lo, hi - 1).f, lo, hi)
    top_c = c.copy()
    top_c[1] += height - r
    top_c[d] -= depth / 2
    return rect | cylinder(top_c, r, depth, axis=axis)


def prism(poly, y0, y1):
    """Многоугольник в плане [(x, z), …], выдавленный от y0 до y1 (блоки включительно)."""
    P = np.asarray(poly, float)

    def f(p):
        x, z = p[..., 0], p[..., 2]
        d = np.full(x.shape, np.inf)
        inside = np.zeros(x.shape, bool)
        for i in range(len(P)):
            a, b = P[i], P[(i + 1) % len(P)]
            e = b - a
            t = np.clip(((x - a[0]) * e[0] + (z - a[1]) * e[1]) / max(e @ e, 1e-9), 0, 1)
            dx, dz = x - a[0] - t * e[0], z - a[1] - t * e[1]
            d = np.minimum(d, np.sqrt(dx * dx + dz * dz))
            cond = (a[1] > z) != (b[1] > z)
            xi = a[0] + (z - a[1]) * e[0] / np.where(e[1] == 0, 1e-9, e[1])
            inside ^= cond & (x < xi)
        d2 = np.where(inside, -d, d)
        dy = np.maximum(y0 - p[..., 1], p[..., 1] - (y1 + 1))
        return np.minimum(np.maximum(d2, dy), 0) + np.sqrt(np.maximum(d2, 0) ** 2 + np.maximum(dy, 0) ** 2)
    lo = [P[:, 0].min(), y0, P[:, 1].min()]
    hi = [P[:, 0].max(), y1 + 1, P[:, 1].max()]
    return Shape(f, lo, hi)


def regular(c, r, n, y0, y1, rot=0.0):
    """Правильный n-угольник в плане: центр c = (x, z), радиус до вершин r."""
    pts = [(c[0] + r * math.cos(math.radians(rot + 360 * k / n)), c[1] + r * math.sin(math.radians(rot + 360 * k / n)))
           for k in range(n)]
    return prism(pts, y0, y1)
