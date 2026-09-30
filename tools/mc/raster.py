"""Программный растеризатор на numpy: текстурированные треугольники, буфер глубины, альфа как у игры.

Камеры — ортографическая (изометрия, план, фасады) и перспективная (вид глазами игрока). Мир: x — восток,
y — вверх, z — юг. Режимы текстур: opaque, cutout (альфа < 0,1 — дырка), translucent (смешивание,
рисуется после непрозрачного от дальнего к ближнему).
"""
import math

import numpy as np

UP = np.array([0.0, 1.0, 0.0])
NEAR = 0.05


def _basis(forward):
    f = forward / np.linalg.norm(forward)
    r = np.cross(f, UP)
    if np.linalg.norm(r) < 1e-6:          # взгляд строго вниз или вверх: «верх» картинки — север
        r = np.array([1.0, 0.0, 0.0])
    r = r / np.linalg.norm(r)
    u = np.cross(r, f)
    return f, r, u


class Camera:
    """Ортографическая: scale — пикселей на блок. Перспективная: fov по вертикали в градусах."""

    def __init__(self, forward, eye=None, ortho=True, scale=16.0, fov=70.0, width=0, height=0):
        self.f, self.r, self.u = _basis(np.asarray(forward, dtype=float))
        self.eye = np.zeros(3) if eye is None else np.asarray(eye, dtype=float)
        self.ortho = ortho
        self.scale = scale
        self.fov = fov
        self.width, self.height = width, height
        self.cx = self.cy = 0.0

    @classmethod
    def orbit(cls, azimuth, elevation, **kw):
        """Камера смотрит на сцену с азимута (0 — с востока, 90 — с юга) и возвышения в градусах."""
        a, e = math.radians(azimuth), math.radians(elevation)
        frm = np.array([math.cos(e) * math.cos(a), math.sin(e), math.cos(e) * math.sin(a)])
        return cls(-frm, **kw)

    def view(self, pts):
        """Мир → (вправо, вверх, глубина) относительно глаза."""
        d = pts - self.eye
        return np.stack([d @ self.r, d @ self.u, d @ self.f], axis=-1)

    def fit(self, lo, hi, margin=12):
        """Ортографическая: размер картинки по габаритам сцены lo..hi."""
        corners = np.array([[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])], dtype=float)
        v = self.view(corners)
        minx, maxx = v[:, 0].min(), v[:, 0].max()
        miny, maxy = v[:, 1].min(), v[:, 1].max()
        self.width = int(math.ceil((maxx - minx) * self.scale)) + 2 * margin
        self.height = int(math.ceil((maxy - miny) * self.scale)) + 2 * margin
        self.cx = -minx * self.scale + margin
        self.cy = maxy * self.scale + margin

    def project(self, v):
        """(вправо, вверх, глубина) → (x, y экрана, глубина, 1/w)."""
        if self.ortho:
            sx = v[..., 0] * self.scale + self.cx
            sy = -v[..., 1] * self.scale + self.cy
            return sx, sy, v[..., 2], np.ones_like(v[..., 2])
        focal = (self.height / 2) / math.tan(math.radians(self.fov) / 2)
        iw = 1.0 / v[..., 2]
        sx = v[..., 0] * iw * focal + self.width / 2
        sy = -v[..., 1] * iw * focal + self.height / 2
        return sx, sy, v[..., 2], iw


class Canvas:
    def __init__(self, width, height):
        self.w, self.h = width, height
        self.rgb = np.zeros((height, width, 3), np.float32)
        self.a = np.zeros((height, width), np.float32)
        self.z = np.full((height, width), np.inf, np.float32)
        self.id = np.full((height, width), -1, np.int32)    # что нарисовано в пикселе — для отметок и отладки

    def tri(self, sx, sy, z, iw, uv, tex, mul, mode, tag=-1):
        """Треугольник: sx, sy, z, iw — по 3 значения, uv — 3×2 в пикселях текстуры, mul — множитель цвета RGB."""
        x0 = max(int(math.floor(min(sx))), 0)
        x1 = min(int(math.ceil(max(sx))), self.w)
        y0 = max(int(math.floor(min(sy))), 0)
        y1 = min(int(math.ceil(max(sy))), self.h)
        if x0 >= x1 or y0 >= y1:
            return
        area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0])
        if abs(area) < 1e-9:
            return
        X, Y = np.meshgrid(np.arange(x0, x1, dtype=np.float32) + 0.5, np.arange(y0, y1, dtype=np.float32) + 0.5)
        w0 = ((sx[1] - X) * (sy[2] - Y) - (sx[2] - X) * (sy[1] - Y)) / area
        w1 = ((sx[2] - X) * (sy[0] - Y) - (sx[0] - X) * (sy[2] - Y)) / area
        w2 = 1.0 - w0 - w1
        eps = -1e-5
        inside = (w0 >= eps) & (w1 >= eps) & (w2 >= eps)
        if not inside.any():
            return
        # Перспективно-корректная интерполяция: всё делим на w, потом делим на интерполированное 1/w
        b0, b1, b2 = w0 * iw[0], w1 * iw[1], w2 * iw[2]
        bs = b0 + b1 + b2
        depth = (b0 * z[0] + b1 * z[1] + b2 * z[2]) / bs
        zr = self.z[y0:y1, x0:x1]
        m = inside & (depth < zr)
        if not m.any():
            return
        u = (b0 * uv[0][0] + b1 * uv[1][0] + b2 * uv[2][0]) / bs
        v = (b0 * uv[0][1] + b1 * uv[1][1] + b2 * uv[2][1]) / bs
        tu = np.floor(u[m]).astype(np.int32) % tex.w
        tv = np.floor(v[m]).astype(np.int32) % tex.h
        texel = tex.rgba[tv, tu]
        alpha = texel[:, 3]
        col = texel[:, :3] * mul
        rgb = self.rgb[y0:y1, x0:x1]
        aa = self.a[y0:y1, x0:x1]
        ids = self.id[y0:y1, x0:x1]
        if mode == "translucent":
            keep = alpha > 0.004
            if not keep.any():
                return
            ys, xs = np.nonzero(m)
            ys, xs, col, alpha = ys[keep], xs[keep], col[keep], alpha[keep][:, None]
            rgb[ys, xs] = col * alpha + rgb[ys, xs] * (1 - alpha)
            aa[ys, xs] = alpha[:, 0] + aa[ys, xs] * (1 - alpha[:, 0])
            return
        keep = alpha >= 0.1 if mode == "cutout" else np.ones_like(alpha, dtype=bool)
        if not keep.any():
            return
        ys, xs = np.nonzero(m)
        ys, xs = ys[keep], xs[keep]
        rgb[ys, xs] = col[keep]
        aa[ys, xs] = 1.0
        zr[ys, xs] = depth[ys, xs]
        ids[ys, xs] = tag

    def image(self, bg=(0, 0, 0, 0)):
        from PIL import Image
        bg = np.array(bg, dtype=np.float32) / 255.0
        a = self.a[..., None]
        rgb = self.rgb * a + bg[:3] * (1 - a) if bg[3] > 0 else self.rgb
        alpha = np.maximum(self.a, bg[3])
        out = np.concatenate([np.clip(rgb, 0, 1), alpha[..., None]], axis=-1)
        return Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGBA")


def clip_near(v, uv):
    """Отсечение многоугольника ближней плоскостью (глубина ≥ NEAR). v — n×3 в виде камеры, uv — n×2."""
    out_v, out_uv = [], []
    n = len(v)
    for i in range(n):
        a, b = v[i], v[(i + 1) % n]
        ta, tb = uv[i], uv[(i + 1) % n]
        ina, inb = a[2] >= NEAR, b[2] >= NEAR
        if ina:
            out_v.append(a)
            out_uv.append(ta)
        if ina != inb:
            t = (NEAR - a[2]) / (b[2] - a[2])
            out_v.append(a + (b - a) * t)
            out_uv.append(ta + (tb - ta) * t)
    return np.array(out_v), np.array(out_uv)
