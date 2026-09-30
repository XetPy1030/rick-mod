"""Лист скина: ракурсы манекена, развёртка с сеткой, вид с расстояния в экранном размере.

Вид с расстояния D блоков — такого роста, каким манекен будет на экране 1080p при FOV 70:
рост 1,875 блока занимает ≈ 1446 / D пикселей. Так видно, что читается издалека, а что сливается.
"""
import math

from PIL import Image, ImageDraw

from .raster import Camera
from .scene import Scene
from .views import PANEL, fit, font, sheet

HEIGHT = 2.0 * 0.9375          # рост манекена в блоках
SCREEN_K = 1080 / (2 * math.tan(math.radians(35)))


def _render(ctx, skin, cam_kw, scale, slim=False, facing="south"):
    sc = Scene(ctx.assets, ctx.registry, ctx.models)
    sc.add_player(skin, (0.0, 0.0, 0.0), facing, slim=slim)
    if "azimuth" in cam_kw:
        cam = Camera.orbit(cam_kw["azimuth"], cam_kw["elevation"], ortho=True, scale=scale)
    else:
        cam = Camera(cam_kw["forward"], ortho=True, scale=scale)
    cam.fit(sc.lo + [0.1, 0.0, 0.1], sc.hi - [0.1, 0.12, 0.1], margin=6)
    return sc.render(cam).image(PANEL)


def net(skin, k=8):
    """Развёртка 64×64 крупно, с сеткой по пикселю и рамками частей."""
    img = Image.new("RGBA", (64 * k, 64 * k), (60, 62, 66, 255))
    checker = Image.new("RGBA", (64 * k, 64 * k))
    d = ImageDraw.Draw(checker)
    for y in range(0, 64 * k, k):
        for x in range(0, 64 * k, k):
            if (x // k + y // k) % 2:
                d.rectangle((x, y, x + k - 1, y + k - 1), fill=(75, 78, 82, 255))
    img.alpha_composite(checker)
    img.alpha_composite(skin.convert("RGBA").resize((64 * k, 64 * k), Image.NEAREST))
    d = ImageDraw.Draw(img)
    for i in range(0, 65):
        c = (0, 0, 0, 60) if i % 4 else (0, 0, 0, 120)
        d.line((i * k, 0, i * k, 64 * k), fill=c)
        d.line((0, i * k, 64 * k, i * k), fill=c)
    return img


def skin_sheet(ctx, skin, title, slim=False, reference=None):
    big = 120.0
    views = [
        ("три четверти спереди", dict(azimuth=60, elevation=12)),
        ("фас", dict(forward=(0, 0, -1))),
        ("правый бок игрока", dict(forward=(1, 0, 0))),
        ("левый бок", dict(forward=(-1, 0, 0))),
        ("спина", dict(forward=(0, 0, 1))),
        ("три четверти сзади", dict(azimuth=240, elevation=12)),
    ]
    tiles = [(label, _render(ctx, skin, kw, big, slim)) for label, kw in views]
    tiles.append(("сверху, спиной вверх", _render(ctx, skin, dict(forward=(0, -1, 0)), big, slim)))
    tiles.append(("развёртка 64×64", net(skin)))
    # С расстояния — в экранном размере, ¾ спереди
    row = []
    for dist in (4, 8, 16, 32):
        px = SCREEN_K * HEIGHT / dist
        img = _render(ctx, skin, dict(azimuth=60, elevation=6), px / HEIGHT, slim)
        row.append((dist, img))
    w = sum(im.width for _, im in row) + 24 * (len(row) + 1)
    h = max(im.height for _, im in row) + 40
    strip = Image.new("RGBA", (w, h), PANEL)
    d = ImageDraw.Draw(strip)
    x = 24
    for dist, im in row:
        strip.alpha_composite(im, (x, h - im.height - 6))
        d.text((x, 6), f"{dist} блоков", font=font(14), fill=(170, 175, 182, 255))
        x += im.width + 24
    lower = [("с расстояния — в натуральную величину, как на экране 1080p при FOV 70", strip)]
    if reference is not None:
        lower.append(("образец", fit(reference.convert("RGBA"), 700, 700)))
    return stack(sheet(tiles, title, width=1600, cols=4), sheet(lower, "", width=1600, cols=1))


def stack(*imgs):
    w = max(i.width for i in imgs)
    out = Image.new("RGBA", (w, sum(i.height for i in imgs)), PANEL)
    y = 0
    for i in imgs:
        out.alpha_composite(i, (0, y))
        y += i.height
    return out
