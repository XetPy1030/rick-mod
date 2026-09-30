#!/usr/bin/env python3
"""Линтер постройки: id и свойства по каталогу, опоры, точки, проходимость, свет, скучные стены, бюджеты.

    python3 tools/lint.py src/main/resources/data/rikoshet/structure/citadel/lab.nbt
    python3 tools/lint.py build.nbt --markers ""      # не Цитадель — точки не нужны

Отчёт — в консоль, план с отметками — run/visual/<имя>/lint.png. Код выхода 1, если есть ошибки.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from PIL import ImageDraw  # noqa: E402

from mc import OUT  # noqa: E402
from mc.lint import CITADEL_MARKERS, ERROR, WARN, Linter  # noqa: E402
from mc.views import Ctx, camera, font, scene_for, PANEL  # noqa: E402
from mc.volume import Volume  # noqa: E402

COLOR = {ERROR: (255, 70, 70, 255), WARN: (255, 200, 60, 255)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--markers", default=",".join(CITADEL_MARKERS), help="обязательные точки через запятую")
    ap.add_argument("--wall", type=int, default=6, help="сплошной квадрат больше N×N на стене — «скучная стена»")
    args = ap.parse_args()

    vol = Volume.load(args.file)
    ctx = Ctx()
    required = tuple(m for m in args.markers.split(",") if m)
    found = Linter(ctx.registry, required, args.wall).run(vol)
    for f in found:
        print(f)

    # План сверху с отметками ошибок и предупреждений
    sc = scene_for(ctx, vol)
    cam = camera("top", 16.0)
    cam.fit(sc.lo, sc.hi)
    img = sc.render(cam).image(PANEL)
    d = ImageDraw.Draw(img)
    fnt = font(12)
    n = 0
    for f in found:
        if f.pos is None or f.level not in COLOR:
            continue
        n += 1
        x, y, z = f.pos
        import numpy as np
        sx, sy, _, _ = cam.project(cam.view(np.array([[x + 0.5, vol.size[1], z + 0.5]])))
        cx, cy = float(sx[0]), float(sy[0])
        d.ellipse((cx - 7, cy - 7, cx + 7, cy + 7), outline=COLOR[f.level], width=3)
        d.text((cx + 9, cy - 8), str(n), font=fnt, fill=COLOR[f.level], stroke_width=2, stroke_fill=(0, 0, 0, 255))
    out = OUT / vol.name
    out.mkdir(parents=True, exist_ok=True)
    img.save(out / "lint.png")
    errors = sum(f.level == ERROR for f in found)
    warns = sum(f.level == WARN for f in found)
    print(f"ошибок {errors}, предупреждений {warns}; план — {out / 'lint.png'}")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
