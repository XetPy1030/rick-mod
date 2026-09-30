#!/usr/bin/env python3
"""Рендер постройки: лист ракурсов, отдельные виды, планы этажей.

    python3 tools/render.py src/main/resources/data/rikoshet/structure/citadel/lab.nbt
    python3 tools/render.py lab.nbt --views iso_se,top --light game --scale 24
    python3 tools/render.py lab.nbt --slices

Выход — run/visual/<имя>/: sheet.png (все ракурсы на одном листе), <вид>.png, slices.png.
Ассеты — клиент 26.2 из кеша Loom, jar модов сборки, наш пак (docs/architecture/visual-pipeline.md#рендер).
"""
import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from mc import OUT  # noqa: E402
from mc.views import VIEWS, Ctx, auto_scale, render, sheet  # noqa: E402
from mc.volume import Volume  # noqa: E402

SHEET = ["iso_se", "iso_sw", "iso_nw", "iso_ne", "top", "south", "east", "cut"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file", help="структура .nbt, схематика .schem или скин .png")
    ap.add_argument("--views", help="виды через запятую: " + ",".join(list(VIEWS) + ["cut", "eye:<точка>"]))
    ap.add_argument("--light", default="flat", choices=["flat", "game"], help="flat — только затенение граней; game — блочный свет")
    ap.add_argument("--ambient", type=float, default=0.35, help="яркость без источников света для --light game")
    ap.add_argument("--scale", type=float, help="пикселей на блок (по умолчанию — по размеру постройки)")
    ap.add_argument("--cut", type=int, help="высота разреза: блоки выше не рисуются")
    ap.add_argument("--slices", action="store_true", help="планы всех этажей отдельным листом")
    ap.add_argument("--out", help="папка вывода (по умолчанию run/visual/<имя>)")
    ap.add_argument("--slim", action="store_true", help="скин: тонкие руки (3 px)")
    ap.add_argument("--reference", help="скин: картинка-образец рядом с листом")
    args = ap.parse_args()

    t0 = time.time()
    ctx = Ctx()
    if args.file.endswith(".png"):
        skin_main(ctx, args, t0)
        return
    vol = Volume.load(args.file)
    out = Path(args.out) if args.out else OUT / vol.name
    out.mkdir(parents=True, exist_ok=True)
    scale = args.scale or auto_scale(vol)
    cut = args.cut if args.cut is not None else max(1, int(vol.size[1] * 0.45))
    fallbacks = {}

    def one(view, **kw):
        if view.startswith("eye:"):
            img, sc = render(ctx, vol, view, light=args.light)
            label = f"глазами игрока с точки {view[4:]}"
        elif view == "cut":
            img, sc = render(ctx, vol, "iso_se", scale, args.light, below=cut)
            label = f"разрез по y = {cut}: крыша и верх стен сняты"
        else:
            img, sc = render(ctx, vol, view, scale, args.light, **kw)
            label = VIEWS[view][0]
        for k, v in sc.notes.items():
            fallbacks[k] = max(fallbacks.get(k, 0), v)
        return label, img

    views = args.views.split(",") if args.views else SHEET + [f"eye:{m.name}" for m in vol.markers() if m.name == "spawn"]
    tiles = []
    for v in views:
        label, img = one(v)
        img.save(out / f"{v.replace(':', '_')}.png")
        tiles.append((label, img))
        print(f"  {v}: {img.width}×{img.height}")

    sx, sy, sz = vol.size
    blocks = sum(vol.counts().values())
    notes = [f"{sx}×{sy}×{sz}, блоков {blocks}, палитра {len(vol.palette)}, свет {args.light}, {scale:g} px на блок"]
    marks = vol.markers()
    if marks:
        notes.append("точки: " + ", ".join(f"{m.name}:{m.facing} {m.pos}" for m in marks))
    unknown = sorted({e for name, props in vol.palette for e in ctx.registry.check(name, props)})
    if unknown:
        notes.append("НЕТ В ИГРЕ (станет воздухом или состоянием по умолчанию): " + "; ".join(unknown))
    missing = sorted(ctx.models.missing)
    if missing:
        notes.append("нет модели: " + ", ".join(missing[:8]) + (" …" if len(missing) > 8 else ""))
    if fallbacks:
        notes.append("заглушки (блок-сущности без модели): " + ", ".join(sorted(fallbacks)))
    if not args.views:
        img = sheet(tiles, f"{vol.name} — лист ракурсов", notes=notes)
        img.save(out / "sheet.png")
        print(out / "sheet.png")

    if args.slices:
        levels = [y for y in range(sy) if (vol.idx[:, y, :] >= 0).any()]
        tiles = []
        for y in levels:
            img, _ = render(ctx, vol, "top", max(scale, 12), args.light, below=y, fade=0.08)
            tiles.append((f"этаж y = {y}", img))
        img = sheet(tiles, f"{vol.name} — планы по этажам (север вверху)", cols=3)
        img.save(out / "slices.png")
        print(out / "slices.png")
    for n in notes:
        print(" ", n)
    print(f"  за {time.time() - t0:.1f} с")


def skin_main(ctx, args, t0):
    """Скин 64×64: лист ракурсов манекена."""
    from PIL import Image
    from mc.skinview import skin_sheet

    path = Path(args.file)
    skin = Image.open(path).convert("RGBA")
    ref = Image.open(args.reference) if args.reference else None
    out = Path(args.out) if args.out else OUT / "skins"
    out.mkdir(parents=True, exist_ok=True)
    img = skin_sheet(ctx, skin, f"{path.stem} — скин", slim=args.slim, reference=ref)
    img.save(out / f"{path.stem}.png")
    print(out / f"{path.stem}.png", f"за {time.time() - t0:.1f} с")


if __name__ == "__main__":
    main()
