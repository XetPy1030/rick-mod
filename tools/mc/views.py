"""Виды постройки и лист ракурсов: изометрия с четырёх углов, план, фасады, разрез, планы этажей, диф."""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from .assets import Assets
from .models import Models
from .raster import Camera
from .registry import default as default_registry
from .scene import Scene

BG = (43, 45, 48, 255)
PANEL = (32, 33, 36, 255)
INK = (225, 228, 232, 255)
DIM = (150, 155, 162, 255)
SKY = (8, 10, 18, 255)    # небо Цитадели — чёрное небо End со звёздами, туман #0b0f1a
MARK = {"rick": (120, 230, 90), "spawn": (90, 170, 255), "portal_back": (60, 255, 140)}

VIEWS = {
    "iso_se": ("изометрия с юго-востока", dict(azimuth=45, elevation=30)),
    "iso_sw": ("с юго-запада", dict(azimuth=135, elevation=30)),
    "iso_nw": ("с северо-запада", dict(azimuth=225, elevation=30)),
    "iso_ne": ("с северо-востока", dict(azimuth=315, elevation=30)),
    "top": ("план сверху, север вверху", dict(forward=(0, -1, 0))),
    "south": ("фасад с юга", dict(forward=(0, 0, -1))),
    "east": ("фасад с востока", dict(forward=(-1, 0, 0))),
    "north": ("фасад с севера", dict(forward=(0, 0, 1))),
    "west": ("фасад с запада", dict(forward=(1, 0, 0))),
}


def font(size):
    for path in ("/System/Library/Fonts/Supplemental/Arial Unicode.ttf", "/System/Library/Fonts/SFNS.ttf",
                 "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size)


class Ctx:
    """Ассеты, каталог и модели — грузятся один раз на запуск."""

    def __init__(self, extra_jars=()):
        self.registry = default_registry()
        self.assets = Assets(extra_jars=extra_jars)
        self.models = Models(self.assets, self.registry)

    def skin(self, name):
        return self.assets.image(f"assets/rikoshet/textures/entity/npc/{name}.png")


def camera(view, scale):
    _, spec = VIEWS[view]
    if "azimuth" in spec:
        return Camera.orbit(spec["azimuth"], spec["elevation"], ortho=True, scale=scale)
    return Camera(spec["forward"], ortho=True, scale=scale)


def scene_for(ctx, vol, light="flat", below=None, fade=0.0, npcs=True):
    sc = Scene(ctx.assets, ctx.registry, ctx.models)
    sc.add_volume(vol, light=light, below=below, fade=fade)
    if npcs:
        for m in vol.markers():
            skin = ctx.skin(m.name)
            if skin is not None and (below is None or m.pos[1] <= below):
                sc.add_player(skin, (m.pos[0] + 0.5, m.pos[1], m.pos[2] + 0.5), m.facing)
    return sc


FACING_VEC = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
EYE = 1.62


def eye_camera(vol, marker, width=1280, height=720, fov=70.0, pitch=-6.0):
    """Глаза игрока на точке: высота 1,62, смотрит по стороне точки, чуть вниз."""
    m = next((m for m in vol.markers() if m.name == marker), None)
    if m is None:
        raise KeyError(f"нет точки {marker}")
    fx, _, fz = FACING_VEC.get(m.facing, (0, 0, 1))
    p = math.radians(pitch)
    forward = (fx * math.cos(p), math.sin(p), fz * math.cos(p))
    eye = (m.pos[0] + 0.5, m.pos[1] + EYE, m.pos[2] + 0.5)
    return Camera(forward, eye=eye, ortho=False, fov=fov, width=width, height=height)


def render(ctx, vol, view, scale=16.0, light="flat", below=None, fade=0.0, markers=True):
    """view — имя из VIEWS или «eye:<точка>» (перспектива глазами игрока)."""
    sc = scene_for(ctx, vol, light, below, fade)
    if view.startswith("eye:"):
        cam = eye_camera(vol, view[4:])
        canvas = sc.render(cam)
        return canvas.image(SKY), sc
    cam = camera(view, scale)
    cam.fit(sc.lo, sc.hi)
    canvas = sc.render(cam)
    img = canvas.image(PANEL)
    if markers:
        draw_markers(img, cam, vol, below, canvas)
    return img, sc


def draw_markers(img, cam, vol, below=None, canvas=None):
    """Точки для кода. Закрытая постройкой точка — пустой кружок."""
    d = ImageDraw.Draw(img)
    f = font(max(11, int(cam.scale * 0.7)))
    for m in vol.markers():
        if below is not None and m.pos[1] > below:
            continue
        p = np.array([m.pos[0] + 0.5, m.pos[1] + (2.2 if m.name == "rick" else 0.1), m.pos[2] + 0.5])
        sx, sy, depth, _ = cam.project(cam.view(p[None, :]))
        x, y = float(sx[0]), float(sy[0])
        hidden = False
        if canvas is not None and 0 <= int(x) < canvas.w and 0 <= int(y) < canvas.h:
            hidden = float(depth[0]) > float(canvas.z[int(y), int(x)]) + 0.3
        c = MARK.get(m.name, (255, 210, 80))
        r = max(3, cam.scale * 0.22)
        if hidden:
            d.ellipse((x - r, y - r, x + r, y + r), outline=c + (255,), width=2)
        else:
            d.ellipse((x - r, y - r, x + r, y + r), fill=c + (255,), outline=(0, 0, 0, 255))
        label = f"{m.name}:{m.facing}"
        d.text((x + r + 3, y - r - 2), label, font=f, fill=c + ((150,) if hidden else (255,)), stroke_width=2, stroke_fill=(0, 0, 0, 255))


def fit(img, w, h):
    k = min(w / img.width, h / img.height, 1.0)
    if k < 1.0:
        img = img.resize((max(1, int(img.width * k)), max(1, int(img.height * k))), Image.LANCZOS)
    return img


def sheet(tiles, title, width=1600, cols=2, notes=()):
    """Лист: заголовок и плитки (подпись, картинка) в сетку. notes — строки внизу."""
    pad, head = 12, 44
    tw = (width - pad * (cols + 1)) // cols
    rows = [tiles[i:i + cols] for i in range(0, len(tiles), cols)]
    fitted = [[(t, fit(im, tw, int(tw * 0.9))) for t, im in row] for row in rows]
    lab_h = [20 * max(t.count("\n") + 1 for t, _ in row) + 6 for row in fitted]   # подписи в несколько строк
    heights = [max(im.height for _, im in row) + lh for row, lh in zip(fitted, lab_h)]
    nf = font(15)
    foot = 22 * len(notes) + (pad if notes else 0)
    out = Image.new("RGBA", (width, head + sum(heights) + pad * (len(rows) + 1) + foot), BG)
    d = ImageDraw.Draw(out)
    d.text((pad, 10), title, font=font(22), fill=INK)
    y = head + pad
    lf = font(15)
    for row, hgt, lh in zip(fitted, heights, lab_h):
        x = pad
        for label, im in row:
            d.rectangle((x, y, x + tw, y + hgt), fill=PANEL)
            d.multiline_text((x + 8, y + 4), label, font=lf, fill=DIM, spacing=4)
            out.alpha_composite(im, (x + (tw - im.width) // 2, y + lh))
            x += tw + pad
        y += hgt + pad
    for line in notes:
        d.text((pad, y), line, font=nf, fill=DIM)
        y += 22
    return out


def auto_scale(vol, target=760):
    """Пикселей на блок, чтобы изометрия влезла в плитку: 16 для небольших построек, меньше для больших."""
    sx, sy, sz = vol.size
    span = (sx + sz) * math.cos(math.radians(45))
    return float(max(4, min(16, int(target / max(span, 1)))))
