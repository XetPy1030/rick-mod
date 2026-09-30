#!/usr/bin/env python3
"""Библиотека образцов: как строят красиво — смотреть, оценивать, брать палитры и узоры.

    python3 tools/refs.py sources                              # источники, что скачано, остаток бюджета
    python3 tools/refs.py scan hermitcraft9                    # мир → постройки (run/refs/worlds/hermitcraft9/)
    python3 tools/refs.py modrinth --top 24                    # самые скачиваемые моды с постройками → sources.json
    python3 tools/refs.py fetch modrinth                       # скачать их jar (или один: fetch modrinth:incendium)
    python3 tools/refs.py inbox                                # скачанное руками в run/refs/inbox/
    python3 tools/refs.py index                                # всё вместе: размеры, палитры, оценка → index.json
    python3 tools/refs.py gallery hermitcraft10: --sort score  # миниатюры → лист и index.html
    python3 tools/refs.py gallery --style futuristic
    python3 tools/refs.py palette minecraft:trial_chambers     # карточка палитры с текстурами блоков
    python3 tools/refs.py find minecraft:copper_bulb           # где этот блок встречается
    python3 tools/refs.py styles                               # группы по материалам — стили
    python3 tools/refs.py patterns hermitcraft                 # что с чем сочетают, низ-стены-верх, мотивы

Источники — tools/refs/sources.json; jar сборки (ванила, Terralith, BetterEnd…) — всегда. Фильтр — начало id:
«minecraft:end_city», «betterend:», «hermitcraft9:end». Выходы — в run/visual/refs/, скачанное — в run/refs/
(docs/architecture/visual-pipeline.md#библиотека-образцов-toolsrefs). Чужое — только для изучения.
"""
import argparse
import html
import json
import sys
import time
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from PIL import Image, ImageDraw  # noqa: E402

from mc import OUT, sources  # noqa: E402
from mc.views import Ctx, BG, INK, DIM, auto_scale, font, render, sheet  # noqa: E402
from mc.volume import Volume  # noqa: E402
from mc import classify  # noqa: E402
from mc.worlds import describe, scan  # noqa: E402

REFS = OUT / "refs"
DL = sources.DL
PATTERN_EXT = (".nbt", ".schem", ".litematic")


# --------------------------------------------------------------------------------------------- что есть
class _ZipSrc:
    def __init__(self, zf, name):
        self.zip = zf if isinstance(zf, zipfile.ZipFile) else zipfile.ZipFile(zf)
        self.name = name

    def names(self):
        return self.zip.namelist()

    def read(self, p):
        return self.zip.read(p)


class _FileSrc:
    name = "files"

    def read(self, p):
        return Path(p).read_bytes()


def _jars():
    return sorted((DL / "jars").glob("*.jar")) + sorted((DL / "jars").glob("*.zip"))


def ctx_for_refs():
    return Ctx(extra_jars=_jars())


def entries(ctx):
    """Все образцы: id → (источник-читатель, путь, id источника). Порядок: сборка, моды Modrinth, миры, входящие."""
    out = {}

    def from_zip(layer, src_id):
        for n in layer.names():
            parts = n.split("/")
            if len(parts) >= 4 and parts[0] == "data" and parts[2] in ("structure", "structures") and n.endswith(".nbt"):
                out.setdefault(f"{parts[1]}:{'/'.join(parts[3:])[:-4]}", (layer, n, src_id))

    downloaded = {j.name for j in _jars()}
    for layer in ctx.assets.layers:
        z = getattr(layer, "zip", None)
        if z is not None and layer.name not in downloaded:
            from_zip(_ZipSrc(z, layer.name), "build")
    for jar in _jars():
        from_zip(_ZipSrc(jar, jar.name), "modrinth:" + jar.name.split("__")[0])
    files = _FileSrc()
    for d in sorted((DL / "worlds").glob("*")) if (DL / "worlds").is_dir() else []:
        # После сегментации (refs.py segment) — отдельные постройки, иначе — плитки скана
        src_dir = d / "seg" if (d / "seg" / "builds.json").exists() else d
        for f in sorted(src_dir.glob("*.npz")):
            out[f"{d.name}:{f.stem}"] = (files, str(f), d.name)
    inbox = DL / "inbox"
    if inbox.is_dir():
        for f in sorted(inbox.rglob("*")):
            if f.suffix in PATTERN_EXT:
                out[f"inbox:{f.relative_to(inbox).with_suffix('').as_posix()}"] = (files, str(f), "inbox")
            elif f.suffix == ".zip":
                z = _ZipSrc(f, f.name)
                for n in z.names():
                    if n.endswith(PATTERN_EXT) and "/data/" not in f"/{n}":
                        out[f"inbox:{f.stem}/{Path(n).with_suffix('').as_posix()}"] = (z, n, "inbox")
                from_zip(z, "inbox")
    return dict(sorted(out.items()))


def load(entry, sid):
    reader, path, _ = entry
    return Volume.from_bytes(reader.read(path), sid.replace(":", "_").replace("/", "_"), Path(path).suffix)


def _styles():
    return {s["id"]: s.get("styles", []) for s in sources.load()["sources"]}


# --------------------------------------------------------------------------------------------- источники
def cmd_sources(ctx, args):
    spent = sources.traffic()
    d = sources.load()
    for s in d["sources"]:
        done = ""
        if s["kind"] == "world":
            bj = DL / "worlds" / s["id"] / "builds.json"
            done = f"построек {json.loads(bj.read_text())['summary']['builds']}" if bj.exists() else "не сканирован"
        elif s["kind"] == "jar":
            done = "скачан" if list((DL / "jars").glob(f"{s['modrinth']}__*")) else "не скачан"
        else:
            done = "вручную → run/refs/inbox/"
        mb = spent.get(s["id"], 0) / 1e6
        print(f"{s['id']:<36} {s['kind']:<6} {done:<26} {mb:7.0f} МБ  {s.get('title', '')}")
    print(f"\nбюджет {d['budget_gb']} ГБ, потрачено {sum(spent.values()) / 1e9:.2f} ГБ, осталось {sources.left() / 1e9:.2f} ГБ")


def cmd_scan(ctx, args):
    if args.source == "all":
        todo = [s["id"] for s in sources.load()["sources"] if s["kind"] == "world"
                and not (DL / "worlds" / s["id"] / "builds.json").exists() and s["id"] not in (args.skip or "").split(",")]
        print("очередь:", ", ".join(todo))
        for sid in todo:
            args.source = sid
            cmd_scan(ctx, args)
        return
    s = sources.get(args.source)
    if s["kind"] != "world":
        sys.exit(f"{args.source} — не мир")
    out = DL / "worlds" / s["id"]
    lock = out / ".scanning"
    if lock.exists():
        import os
        try:
            os.kill(int(lock.read_text()), 0)
            print(f"{s['id']}: уже сканируется (pid {lock.read_text()})")
            return
        except (OSError, ValueError):
            pass
    budget = min(args.budget * 1e9 if args.budget else 1e30, sources.left())
    if budget <= 0:
        sys.exit("бюджет трафика исчерпан — поднимите budget_gb в sources.json")
    out.mkdir(parents=True, exist_ok=True)
    import os
    lock.write_text(str(os.getpid()))
    print(f"== {s['id']}: {s.get('title')}")
    loc = s["url"]
    try:
        if s.get("fetch") == "full":
            loc = str(_download_whole(s))
        summary = scan(loc, out, DL / "cache" / s["id"], budget=budget,
                       workers=args.workers, only_dims=args.dims.split(",") if args.dims else None)
        if s.get("fetch") == "full" and not summary.get("stopped"):
            Path(loc).unlink(missing_ok=True)
    except Exception as e:                 # один сломанный мир не останавливает очередь
        print(f"{s['id']}: ошибка {type(e).__name__}: {e}")
        return
    finally:
        lock.unlink(missing_ok=True)
    if summary.get("builds"):
        from mc.segment import segment
        segment(out, city=s.get("segment") == "city")
    if summary["unknown"]:
        print("неизвестные блоки (пополнить RENAMES в mc/registry.py):",
              ", ".join(f"{k} {v}" for k, v in list(summary["unknown"].items())[:15]))


def _download_whole(s):
    """Архив целиком, с докачкой: для хостингов, которые режут чтение по частям (MediaFire)."""
    import subprocess
    from mc.remote import resolve
    path = DL / "dl" / f"{s['id']}.zip"
    path.parent.mkdir(parents=True, exist_ok=True)
    size = int(s.get("size_gb", 0) * 1e9)
    for attempt in range(12):
        if path.exists() and size and path.stat().st_size >= size * 0.999 and zipfile.is_zipfile(path):
            break
        url = resolve(s["url"])          # ссылка MediaFire временная — каждый раз заново
        before = path.stat().st_size if path.exists() else 0
        subprocess.run(["curl", "-sS", "-L", "-A", "Mozilla/5.0", "-C", "-", "--retry", "3", "-o", str(path), url])
        sources.spend(f"{s['id']}#zip", (path.stat().st_size if path.exists() else 0) - before)
        with open(path, "rb") as f:
            if f.read(2) != b"PK":        # вместо архива пришла страница-заглушка
                path.unlink()
    if not zipfile.is_zipfile(path):
        raise OSError(f"{s['id']}: архив не скачался")
    return path


def cmd_modrinth(ctx, args):
    top = sources.modrinth_top(args.top)
    d = sources.load()
    keep = [s for s in d["sources"] if not s["id"].startswith("modrinth:")]
    old = {s["id"]: s for s in d["sources"] if s["id"].startswith("modrinth:")}
    for s in top:                     # стили, проставленные руками, не теряем
        s["styles"] = old.get(s["id"], {}).get("styles") or s["styles"]
    d["sources"] = keep + top
    sources.save(d)
    for s in top:
        print(f"{s['id']:<44} {s['rating']}")


def cmd_fetch(ctx, args):
    todo = [s for s in sources.load()["sources"] if s["kind"] == "jar" and
            (s["id"] == args.what or args.what == "modrinth")]
    for s in todo:
        try:
            sources.fetch_jar(s)
        except OSError as e:
            print(f"{s['id']}: {e}")


def cmd_inbox(ctx, args):
    """Скачанное руками: zip и rar с мирами сканируются (и режутся на здания), схематики и структуры
    индексируются как есть. Файл, указанный в sources.json полем «file», получает id своего источника."""
    import subprocess
    from mc.segment import segment
    inbox = DL / "inbox"
    inbox.mkdir(parents=True, exist_ok=True)
    by_file = {s_["file"]: s_["id"] for s_ in sources.load()["sources"] if s_.get("file")}
    for f in sorted(inbox.iterdir()):
        sid = by_file.get(f.name, f"inbox_{f.stem}")
        loc = None
        if f.suffix == ".rar":
            target = inbox / f.stem
            if not target.exists():
                print(f"распаковка {f.name}")
                target.mkdir()
                subprocess.run(["tar", "-xf", str(f), "-C", str(target)], check=True)   # bsdtar читает RAR
            continue                                                                        # папку возьмёт цикл ниже
        if f.suffix == ".zip":
            with zipfile.ZipFile(f) as z:
                if any(n.endswith(".mca") for n in z.namelist()):
                    loc = str(f)
        elif f.is_dir() and any(f.rglob("*.mca")):
            loc = str(f)
            sid = by_file.get(f.name + ".rar", by_file.get(f.name, sid))
        if not loc:
            continue
        out = DL / "worlds" / sid
        if (out / "builds.json").exists():
            continue
        print(f"== {sid}: {f.name}")
        summary = scan(loc, out, DL / "cache" / sid, workers=args.workers)
        if summary.get("builds"):
            segment(out, city=next((s_.get("segment") == "city" for s_ in sources.load()["sources"]
                                    if s_["id"] == sid), False))
        if summary.get("unknown"):
            print("неизвестные блоки:", ", ".join(f"{k} {v}" for k, v in list(summary["unknown"].items())[:15]))
    print(f"готово; схематики (.schem, .litematic, .nbt) из {inbox} попадают в index сами")


# --------------------------------------------------------------------------------------------- индекс
def cmd_index(ctx, args):
    all_ = entries(ctx)
    styles = _styles()
    old = _index(required=False)
    index = {}
    t0 = time.time()
    clusters = {}                         # постройка из мира → номер кластера (здания одного кластера рядом)
    for bj in (DL / "worlds").glob("*/seg/builds.json"):
        for b in json.loads(bj.read_text())["builds"]:
            clusters[f"{bj.parent.parent.name}:{b['id']}"] = b.get("cluster")
    for sid, entry in all_.items():
        src = entry[2]
        if sid in old and "error" not in old[sid] and "tags" in old[sid] and not args.full:
            index[sid] = old[sid]
            if sid in clusters:
                index[sid]["cluster"] = clusters[sid]
            continue
        try:
            v = load(entry, sid)
        except Exception as e:          # битые и старые файлы не мешают остальным
            index[sid] = {"error": str(e), "source": src}
            continue
        counts = v.counts()
        index[sid] = {"size": list(v.size), "blocks": sum(counts.values()), "top": list(counts.items())[:12],
                      "source": src, "styles": styles.get(src, []), **describe(v),
                      "tags": classify.classify(v, sid)}
        if sid in clusters:
            index[sid]["cluster"] = clusters[sid]
    REFS.mkdir(parents=True, exist_ok=True)
    (REFS / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=0))
    by_src = {}
    for e in index.values():
        by_src[e["source"]] = by_src.get(e["source"], 0) + 1
    print(f"{len(index)} образцов за {time.time() - t0:.0f} с → {REFS / 'index.json'}")
    print("  " + ", ".join(f"{k} {v}" for k, v in sorted(by_src.items(), key=lambda kv: -kv[1])))


def _index(required=True):
    p = REFS / "index.json"
    if not p.exists():
        if required:
            sys.exit("сначала: python3 tools/refs.py index")
        return {}
    return json.loads(p.read_text())


def select(index, flt="", style=None, min_blocks=0, sort="id", type_=None, scale=None, env=None, zone=None,
           manual=False, keep_rejected=False):
    """Отбор по началу id и осям разметки (ручные метки главнее автоматики)."""
    lab = classify.labels()
    out = []
    for sid, e in index.items():
        if not sid.startswith(flt) or "error" in e:
            continue
        fin = classify.final(e, sid, lab)
        if fin.get("reject") and not keep_rejected:
            continue
        if style and style not in fin["style"] and style not in e.get("styles", []):
            continue
        if type_ and type_ not in fin["type"]:
            continue
        if scale and fin["scale"] != scale:
            continue
        if env and fin["env"] != env:
            continue
        if manual and not fin["manual"]:
            continue
        if min_blocks and e["blocks"] < min_blocks:
            continue
        if zone:
            z = classify.zone_scores(e, sid, lab).get(zone, 0)
            if z <= 0:
                continue
            e["_zone"] = z
        out.append(sid)
    if zone:
        out.sort(key=lambda s: -index[s]["_zone"])
    elif sort == "score":
        out.sort(key=lambda s: -(classify.final(index[s], s, lab).get("rating") or 0) * 10 - index[s].get("score", 0))
    return out


def _caption(sid, e, lab=None, num=None):
    fin = classify.final(e, sid, lab)
    sx, sy, sz = e["size"]
    tags = "/".join(fin["type"][:2]) + " · " + "/".join(fin["style"][:2])
    star = f"★{fin['rating']}" if fin.get("rating") else f"☆{e.get('score', 0):.1f}"
    mark = "✎" if fin["manual"] else ""
    head = f"{num}. " if num is not None else ""
    return f"{head}{sid.split(':', 1)[1][:28]}  {sx}×{sy}×{sz} {star}{mark}\n{tags}"


def _thumb(ctx, sid, entry, width=360):
    path = REFS / "thumbs" / (sid.replace(":", "/") + ".png")
    if path.exists():
        return Image.open(path)
    v = load(entry, sid)
    if entry[2] not in ("build",) and not entry[2].startswith("modrinth:"):
        _trim_terrain(v)
    scale = min(auto_scale(v, width - 30), 10.0)
    # Закрытая коробка (комнаты под землёй) снаружи ничего не скажет — показываем разрезом по середине
    below = None
    if (v.idx >= 0).any():
        top = [(v.idx[:, y, :] >= 0).mean() for y in range(v.size[1])]
        if max(top[v.size[1] // 2:], default=0) > 0.6 and "minecraft:air" not in {p[0] for p in v.palette[:1]}:
            below = max(1, int(v.size[1] * 0.55))
    img, _ = render(ctx, v, "iso_se", scale, below=below, markers=False)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    return img


def _trim_terrain(v, keep=3):
    """Вырезанное из мира: рельеф глубже keep блоков под поверхностью скрыть — на миниатюре нужен
    дом, а не пласт грунта в разрезе рамки."""
    import numpy as np
    from mc import kinds
    nat = np.zeros(len(v.palette) + 1, bool)
    for i, (name, _) in enumerate(v.palette):
        nat[i] = kinds.kind(name) == kinds.NATURAL and name not in ("minecraft:air", "minecraft:cave_air",
                                                                     "minecraft:water")
    n = nat[v.idx]
    # Поверхность колонки — самый верхний природный блок
    ys = np.arange(v.size[1])[None, :, None]
    top = np.where(n, ys, -1).max(axis=1, keepdims=True)
    v.idx[n & (ys < top - keep)] = -1


def cmd_gallery(ctx, args):
    all_ = entries(ctx)
    index = _index()
    ids = [s for s in select(index, args.filter, args.style, args.min, args.sort, args.type, args.scale, args.env,
                             args.zone) if s in all_][:args.limit]
    t0 = time.time()
    tiles = []
    lab = classify.labels()
    for sid in ids:
        img = _thumb(ctx, sid, all_[sid])
        tiles.append((_caption(sid, index[sid], lab), img))
    name = "-".join(x for x in (args.filter.replace(":", "_").replace("/", "_").strip("_"), args.style, args.type,
                                args.scale, args.env, args.zone and "zone_" + args.zone) if x) or "all"
    REFS.mkdir(parents=True, exist_ok=True)
    for k in range(0, len(tiles), 24):
        part = tiles[k:k + 24]
        title = f"{name} — образцы {k + 1}–{k + len(part)} из {len(tiles)}"
        img = sheet(part, title, width=1600, cols=4)
        img.save(REFS / f"{name}-{k // 24 + 1}.png")
        print(REFS / f"{name}-{k // 24 + 1}.png")
    _html(index)
    print(f"{len(tiles)} миниатюр за {time.time() - t0:.0f} с; галерея — {REFS / 'index.html'}")


def _html(index):
    """Галерея всех отрендеренных миниатюр: фильтр по id, источнику, стилю и блоку, сортировка по оценке."""
    cards = []
    for png in sorted((REFS / "thumbs").rglob("*.png")):
        rel = png.relative_to(REFS / "thumbs").with_suffix("")
        sid = f"{rel.parts[0]}:{'/'.join(rel.parts[1:])}"
        info = index.get(sid, {})
        top = ", ".join(f"{n.split(':', 1)[1]} {c}" for n, c in info.get("top", [])[:6])
        key = " ".join([sid, info.get("source") or "", *info.get("styles", []), *(n for n, _ in info.get("top", []))])
        size = "×".join(str(v) for v in info.get("size", []))
        score = info.get("score", 0)
        cards.append(f'<figure data-k="{html.escape(key)}" data-s="{score}"><img loading="lazy" '
                     f'src="thumbs/{rel.as_posix()}.png"><figcaption><b>{html.escape(sid)}</b> {size} ★{score:.1f}'
                     f'<br><small>{html.escape(top)}</small></figcaption></figure>')
    page = f"""<!doctype html><meta charset="utf-8"><title>Образцы построек</title>
<style>body{{background:#2b2d30;color:#e1e4e8;font:14px system-ui;margin:16px}}input,select{{font:inherit;padding:6px}}
input{{width:min(480px,100%)}}main{{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px;margin-top:12px}}
figure{{margin:0;background:#202124;padding:8px}}img{{width:100%;image-rendering:pixelated}}small{{color:#969ba2}}</style>
<h1>Образцы построек — {len(cards)}</h1><input id=q placeholder="фильтр: hermitcraft9, futuristic, copper, betterend:…">
<select id=o><option value=id>по id</option><option value=s>по оценке</option></select>
<main id=m>{''.join(cards)}</main>
<script>const m=document.getElementById('m');
q.oninput=()=>{{const t=q.value.toLowerCase();for(const f of m.children)f.hidden=!f.dataset.k.toLowerCase().includes(t)}};
o.onchange=()=>{{const a=[...m.children];if(o.value==='s')a.sort((x,y)=>y.dataset.s-x.dataset.s);else a.sort((x,y)=>x.dataset.k<y.dataset.k?-1:1);m.append(...a)}}</script>"""
    (REFS / "index.html").write_text(page)


# --------------------------------------------------------------------------------------------- палитры
def _swatch(ctx, name):
    """Текстура блока — боковая грань модели по умолчанию, 16×16."""
    if name == "minecraft:water":
        name = "minecraft:water_cauldron"
    quads = ctx.models.quads(name, {})
    if name == "minecraft:water_cauldron":
        quads = [q for q in quads if "water" in q.tex.id]
    if not quads:
        c = ctx.registry.map_color(name, 2)
        return Image.new("RGBA", (16, 16), c + (255,))
    q = max(quads, key=lambda q: (abs(q.normal[1]) < 0.5, q.tex.w * q.tex.h))
    t = q.tex.rgba.copy()
    tint = ctx.models.tint(name, q.tint)
    if tint is not None:
        t[..., :3] *= tint
    return Image.fromarray((t * 255).astype("uint8"), "RGBA").resize((16, 16), Image.NEAREST)


def palette_card(ctx, rows, title):
    """Карточка: образец текстуры, id, полоса доли. rows — [(id, доля)]."""
    W, rh = 900, 40
    img = Image.new("RGBA", (W, 60 + rh * len(rows)), BG)
    d = ImageDraw.Draw(img)
    d.text((16, 14), title, font=font(22), fill=INK)
    f = font(15)
    top = max((s for _, s in rows), default=1) or 1
    for k, (name, share) in enumerate(rows):
        y = 56 + k * rh
        img.alpha_composite(_swatch(ctx, name).resize((32, 32), Image.NEAREST), (16, y))
        d.text((60, y + 7), name, font=f, fill=INK)
        d.rectangle((520, y + 8, 520 + int(300 * share / top), y + 24), fill=(90, 150, 220, 255))
        d.text((830, y + 7), f"{share:.1%}", font=f, fill=DIM)
    return img


def cmd_palette(ctx, args):
    index = _index()
    all_ = entries(ctx)
    total = {}
    ids = select(index, args.filter, args.style)
    for sid in ids:
        info = index[sid]
        v = load(all_[sid], sid) if args.full and sid in all_ else None
        items = v.counts().items() if v else info["top"]
        for name, c in items:
            total[name] = total.get(name, 0) + c
    if not total:
        sys.exit("ничего не нашлось")
    skip = {"minecraft:air", "minecraft:structure_void", "minecraft:jigsaw", "minecraft:structure_block"}
    rows = [(k, c) for k, c in sorted(total.items(), key=lambda kv: -kv[1]) if k not in skip][:args.top]
    s = sum(c for _, c in rows)
    img = palette_card(ctx, [(k, c / s) for k, c in rows], f"палитра {args.filter or args.style} — {len(ids)} построек")
    name = (args.filter or args.style).replace(":", "_").replace("/", "_").strip("_")
    REFS.mkdir(parents=True, exist_ok=True)
    img.save(REFS / f"palette-{name}.png")
    print(REFS / f"palette-{name}.png")


def cmd_find(ctx, args):
    index = _index()
    hits = [(sid, dict(info["top"]).get(args.block, 0)) for sid, info in index.items() if "top" in info]
    hits = [h for h in hits if h[1]]
    for sid, c in sorted(hits, key=lambda h: -h[1])[:40]:
        print(f"{c:6d}  {sid}")
    print(f"в топ-12 палитры у {len(hits)} построек")


# --------------------------------------------------------------------------------------------- разбор
def cmd_styles(ctx, args):
    from mc import patterns
    patterns.styles_report(ctx, _index(), entries(ctx), REFS, k=args.k, flt=args.filter, min_score=args.min_score)


def cmd_patterns(ctx, args):
    from mc import patterns
    patterns.report(ctx, _index(), entries(ctx), REFS, flt=args.filter, style=args.style, top=args.top,
                    min_score=args.min_score)


def cmd_segment(ctx, args):
    from mc.segment import segment
    ids = ([s["id"] for s in sources.load()["sources"] if s["kind"] == "world"] if args.source == "all"
           else [args.source])
    mode = {s_["id"]: s_.get("segment") for s_ in sources.load()["sources"]}
    for sid in ids:
        if (DL / "worlds" / sid / "builds.json").exists():
            segment(DL / "worlds" / sid, city=mode.get(sid) == "city")


REVIEW = REFS / "review.json"


def cmd_review(ctx, args):
    """Лист с номерами: смотришь картинку, размечаешь `refs.py label <номер> …`."""
    all_ = entries(ctx)
    index = _index()
    lab = classify.labels()
    ids = [s for s in select(index, args.filter, args.style, args.min, args.sort, args.type, args.scale, args.env,
                             args.zone) if s in all_ and not (args.todo and s in lab)]
    ids = ids[args.offset:args.offset + args.limit]
    tiles = [(_caption(sid, index[sid], lab, n + args.offset), _thumb(ctx, sid, all_[sid])) for n, sid in enumerate(ids)]
    REFS.mkdir(parents=True, exist_ok=True)
    slug = "-".join(x for x in (args.filter.replace(":", "").replace("/", "_"), args.style, args.type, args.zone) if x) or "all"
    (REFS / f"review-{slug}.json").write_text(json.dumps({str(n + args.offset): sid for n, sid in enumerate(ids)},
                                                         ensure_ascii=False))
    REVIEW.write_text((REFS / f"review-{slug}.json").read_text())      # последний лист — для label <номер>
    img = sheet(tiles, f"разметка: {args.filter or 'все'} {args.style or ''} {args.type or ''} {args.zone or ''}"
                       f" — {args.offset}–{args.offset + len(ids) - 1}", width=1600, cols=4)
    out = REFS / f"review-{slug}.png"
    img.save(out)
    print(out)
    for n, sid in enumerate(ids):
        print(f"{n + args.offset:3d}  {sid}")


def cmd_label(ctx, args):
    """label 5 type=tower,hall style=scifi zones=lab,docks rating=4 note="медный купол"; reject=1 — выкинуть."""
    target = args.target
    if target.isdigit() and REVIEW.exists():
        target = json.loads(REVIEW.read_text())[target]
    lab = classify.labels()
    cur = lab.get(target, {})
    tx = classify.taxonomy()
    for pair in args.pairs:
        k, _, v = pair.partition("=")
        if k in ("type", "style", "zones"):
            vals = [x for x in v.split(",") if x]
            allowed = tx["zones"] if k == "zones" else tx["axes"][k]
            bad = [x for x in vals if x not in allowed]
            if bad:
                sys.exit(f"нет таких значений {k}: {bad}; можно: {', '.join(allowed)}")
            cur[k] = vals
        elif k == "rating":
            cur[k] = int(v)
        elif k == "reject":
            cur[k] = v not in ("0", "false", "")
        elif k in ("scale", "env"):
            if v not in tx["axes"][k]:
                sys.exit(f"{k}: можно {', '.join(tx['axes'][k])}")
            cur[k] = v
        elif k == "note":
            cur[k] = v
        else:
            sys.exit(f"неизвестный ключ {k}")
    lab[target] = cur
    classify.save_labels(lab)
    print(target, cur)


def cmd_stats(ctx, args):
    index = _index()
    lab = classify.labels()
    from collections import Counter
    axes = {"type": Counter(), "style": Counter(), "scale": Counter(), "env": Counter(), "source": Counter(),
            "zone": Counter()}
    manual = 0
    for sid, e in index.items():
        if "error" in e:
            continue
        fin = classify.final(e, sid, lab)
        manual += fin["manual"]
        for t in fin["type"]:
            axes["type"][t] += 1
        for t in fin["style"]:
            axes["style"][t] += 1
        axes["scale"][fin["scale"]] += 1
        axes["env"][fin["env"]] += 1
        axes["source"][e.get("source")] += 1
        for z, v in classify.zone_scores(e, sid, lab).items():
            if v >= 0.25:
                axes["zone"][z] += 1
    for k, c in axes.items():
        print(f"{k}: " + ", ".join(f"{a} {n}" for a, n in c.most_common()))
    print(f"размечено вручную: {manual}")


def cmd_check(ctx, args):
    index = _index()
    acc, counts = classify.train(index)
    print("модель стиля (отложенные 20%):")
    for s_, (ok, n) in sorted(acc.items(), key=lambda kv: -kv[1][1]):
        print(f"  {s_:<14} {ok:4d}/{n:<4d} {ok / n:5.0%}")
    tot_ok = sum(v[0] for v in acc.values())
    tot = sum(v[1] for v in acc.values()) or 1
    print(f"  всего {tot_ok}/{tot} = {tot_ok / tot:.0%}; примеров по стилям: "
          + ", ".join(f"{k} {v}" for k, v in sorted(counts.items(), key=lambda kv: -kv[1])))
    print("подписи материалов (без обучения):")
    res, conf = classify.check(index)
    for s, (ok, n) in sorted(res.items(), key=lambda kv: -kv[1][1]):
        print(f"{s:<14} {ok:4d}/{n:<4d} {ok / n:5.0%}")
    tot_ok = sum(v[0] for v in res.values())
    tot = sum(v[1] for v in res.values()) or 1
    print(f"всего {tot_ok}/{tot} = {tot_ok / tot:.0%}; чаще всего путает:",
          ", ".join(f"{a}→{b} {n}" for (a, b), n in conf[:8]))


def _diverse(ids, index, limit, per_building=2, per_source=5):
    """Не больше per_building образцов с одного здания (кластера мира) и per_source — с одного источника:
    доска из девяти кусков одной библиотеки ничего не скажет о зоне."""
    from collections import Counter
    b, src, out = Counter(), Counter(), []
    for sid in ids:
        e = index[sid]
        key = f"{e.get('source')}#{e['cluster']}" if "cluster" in e else sid
        if b[key] >= per_building or src[e.get("source")] >= per_source:
            continue
        b[key] += 1
        src[e.get("source")] += 1
        out.append(sid)
        if len(out) >= limit:
            break
    return out


def cmd_board(ctx, args):
    from mc import patterns
    all_ = entries(ctx)
    index = _index()
    zone = classify.taxonomy()["zones"].get(args.zone)
    if not zone:
        sys.exit(f"нет зоны {args.zone}; есть: {', '.join(classify.taxonomy()['zones'])}")
    ids = _diverse([s for s in select(index, zone=args.zone) if s in all_], index, args.limit)
    lab = classify.labels()
    out = REFS / "boards" / args.zone
    out.mkdir(parents=True, exist_ok=True)
    tiles = [(_caption(sid, index[sid], lab, n) + f"  ⌖{index[sid]['_zone']:.2f}", _thumb(ctx, sid, all_[sid]))
             for n, sid in enumerate(ids)]
    sheet(tiles, f"доска «{zone['ru']}» — {len(ids)} образцов", width=1600, cols=4).save(out / "board.png")
    (out / "board.json").write_text(json.dumps(ids, ensure_ascii=False, indent=1))
    res = patterns.report(ctx, {s: index[s] for s in ids}, all_, out, flt="", top=36)
    print(out / "board.png")
    if args.export:
        pal = REFS.parent.parent.parent / "tools" / "build" / "palettes" / f"{args.zone}.json"
        pal.parent.mkdir(parents=True, exist_ok=True)
        pal.write_text(json.dumps({"zone": args.zone, "ru": zone["ru"], "from": len(ids),
                                   "note": "выведено refs.py board --export из образцов доски; чужих построек тут нет",
                                   "layers": res["layers"], "pairs": res["pairs"][:40]},
                                  ensure_ascii=False, indent=1) + "\n")
        print(pal)


def _axes(p):
    p.add_argument("--type")
    p.add_argument("--scale")
    p.add_argument("--env")
    p.add_argument("--zone")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("sources")
    sc = sub.add_parser("scan")
    sc.add_argument("source", help="id мира из sources.json или all — все несканированные по порядку")
    sc.add_argument("--skip", help="через запятую — не трогать в all")
    sc.add_argument("--budget", type=float, help="ГБ трафика на этот мир")
    sc.add_argument("--dims", help="overworld,nether,end")
    sc.add_argument("--workers", type=int, default=3)
    mr = sub.add_parser("modrinth")
    mr.add_argument("--top", type=int, default=24)
    fe = sub.add_parser("fetch")
    fe.add_argument("what", help="modrinth — все jar из реестра, или id источника")
    ib = sub.add_parser("inbox")
    ib.add_argument("--workers", type=int, default=3)
    ix = sub.add_parser("index")
    ix.add_argument("--full", action="store_true", help="пересчитать всё, а не только новое")
    g = sub.add_parser("gallery")
    g.add_argument("filter", nargs="?", default="")
    g.add_argument("--style")
    g.add_argument("--sort", choices=("id", "score"), default="id")
    g.add_argument("--limit", type=int, default=96)
    g.add_argument("--min", type=int, default=0, help="не меньше N блоков — без мелочи")
    _axes(g)
    p = sub.add_parser("palette")
    p.add_argument("filter", nargs="?", default="")
    p.add_argument("--style")
    p.add_argument("--top", type=int, default=24)
    p.add_argument("--full", action="store_true", help="считать по всем блокам, а не по топ-12 каждой постройки")
    fnd = sub.add_parser("find")
    fnd.add_argument("block")
    rv = sub.add_parser("review", help="пронумерованный лист для ручной разметки")
    rv.add_argument("filter", nargs="?", default="")
    rv.add_argument("--style")
    rv.add_argument("--sort", choices=("id", "score"), default="score")
    rv.add_argument("--limit", type=int, default=24)
    rv.add_argument("--offset", type=int, default=0)
    rv.add_argument("--min", type=int, default=0)
    rv.add_argument("--todo", action="store_true", help="только без ручных меток")
    _axes(rv)
    lb = sub.add_parser("label", help="ручная метка: label <id|номер из review> type=tower style=scifi zones=lab rating=4")
    lb.add_argument("target")
    lb.add_argument("pairs", nargs="+")
    bd = sub.add_parser("board", help="доска зоны Цитадели: лучшие образцы, палитра, ярусы, сочетания, мотивы")
    bd.add_argument("zone")
    bd.add_argument("--limit", type=int, default=16)
    bd.add_argument("--export", action="store_true", help="палитру и ярусы — в tools/build/palettes/<зона>.json")
    sub.add_parser("stats", help="сколько образцов по осям и источникам")
    sub.add_parser("check", help="точность стиля по материалам на образцах, где стиль известен по id")
    sg = sub.add_parser("segment", help="плитки мира → отдельные постройки")
    sg.add_argument("source", help="id мира или all")
    st = sub.add_parser("styles")
    st.add_argument("filter", nargs="?", default="")
    st.add_argument("--k", type=int, default=12)
    st.add_argument("--min-score", type=float, default=0.0)
    pt = sub.add_parser("patterns")
    pt.add_argument("filter", nargs="?", default="")
    pt.add_argument("--style")
    pt.add_argument("--top", type=int, default=48)
    pt.add_argument("--min-score", type=float, default=0.0)
    args = ap.parse_args()
    light = ("sources", "modrinth", "fetch", "scan", "label", "stats", "check", "segment")
    ctx = ctx_for_refs() if args.cmd not in light else None
    {"sources": cmd_sources, "scan": cmd_scan, "modrinth": cmd_modrinth, "fetch": cmd_fetch, "inbox": cmd_inbox,
     "index": cmd_index, "gallery": cmd_gallery, "palette": cmd_palette, "find": cmd_find,
     "styles": cmd_styles, "patterns": cmd_patterns, "review": cmd_review, "label": cmd_label,
     "board": cmd_board, "stats": cmd_stats, "check": cmd_check, "segment": cmd_segment}[args.cmd](ctx, args)


if __name__ == "__main__":
    main()
