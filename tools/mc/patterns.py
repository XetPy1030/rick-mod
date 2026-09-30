"""Разбор образцов: стили, сочетания материалов, ярусы, мотивы.

- Стили — группы построек по материалам (k-means по долям семейств: ступени и плиты камня — один материал).
- Сочетания — пары материалов, которые кладут вплотную чаще, чем случайно (lift), и чем что обводят.
- Ярусы — из чего низ, стены и верх постройки.
- Мотивы — частые куски 3×3×3 с учётом поворота и отражения, встречающиеся в разных постройках.

Всё считается только по рукотворному (kinds.py): рельеф вокруг вырезанных из миров построек не мешает.
"""
import json
import math
from collections import Counter, defaultdict

import numpy as np
from PIL import Image, ImageDraw

from . import kinds
from .views import BG, INK, DIM, PANEL, font, render, sheet
from .volume import Volume


def _sel(index, flt="", style=None, min_score=0.0):
    return [s for s, e in index.items() if s.startswith(flt) and "error" not in e and e.get("families")
            and (not style or style in e.get("styles", [])) and e.get("score", 0) >= min_score]


def _load(entries, sid):
    reader, path, _ = entries[sid]
    from pathlib import Path
    return Volume.from_bytes(reader.read(path), sid, Path(path).suffix)


# ------------------------------------------------------------------------------------------------- стили
def kmeans(x, k, seed=26, iters=40):
    rng = np.random.default_rng(seed)
    # k-means++: центры подальше друг от друга
    c = [x[rng.integers(len(x))]]
    for _ in range(1, k):
        d = np.min(((x[:, None, :] - np.array(c)[None]) ** 2).sum(-1), axis=1)
        c.append(x[rng.choice(len(x), p=d / d.sum())] if d.sum() > 0 else x[rng.integers(len(x))])
    c = np.array(c)
    for _ in range(iters):
        lab = np.argmin(((x[:, None, :] - c[None]) ** 2).sum(-1), axis=1)
        new = np.array([x[lab == j].mean(0) if (lab == j).any() else c[j] for j in range(k)])
        if np.allclose(new, c):
            break
        c = new
    return lab, c


def styles(index, flt="", k=12, min_score=0.0):
    ids = _sel(index, flt, min_score=min_score)
    df = Counter(f for s in ids for f, _ in index[s]["families"])
    vocab = sorted(f for f, n in df.items() if n >= 3)
    pos = {f: i for i, f in enumerate(vocab)}
    x = np.zeros((len(ids), len(vocab)))
    for r, s in enumerate(ids):
        fam = index[s]["families"]
        tot = sum(c for _, c in fam) or 1
        for f, c in fam:
            if f in pos:
                x[r, pos[f]] = math.sqrt(c / tot)          # корень — чтобы один главный материал не решал всё
    x /= np.linalg.norm(x, axis=1, keepdims=True) + 1e-9
    k = min(k, len(ids))
    lab, cent = kmeans(x, k)
    out = []
    for j in range(k):
        members = sorted((ids[i] for i in np.nonzero(lab == j)[0]), key=lambda s: -index[s].get("score", 0))
        if not members:
            continue
        mix = Counter()
        for s in members:
            fam = index[s]["families"]
            tot = sum(c for _, c in fam) or 1
            for f, c in fam:
                mix[f] += c / tot / len(members)
        label = " + ".join(f.split(":", 1)[1] for f, _ in mix.most_common(3))
        srcs = Counter(index[s].get("source") for s in members)
        out.append({"label": label, "families": [[f, round(v, 4)] for f, v in mix.most_common(12)],
                    "members": members, "sources": dict(srcs.most_common())})
    out.sort(key=lambda st: -len(st["members"]))
    return out


def styles_report(ctx, index, entries, out, k=12, flt="", min_score=0.0, thumbs=4):
    from refs import _swatch, _thumb   # noqa: PLC0415 — общий кеш миниатюр
    st = styles(index, flt, k, min_score)
    rows = []
    for n, s in enumerate(st):
        W, H = 1600, 300
        img = Image.new("RGBA", (W, H), PANEL)
        d = ImageDraw.Draw(img)
        d.text((16, 10), f"стиль {n + 1}: {s['label']} — {len(s['members'])} построек", font=font(22), fill=INK)
        d.text((16, 40), ", ".join(f"{k} {v}" for k, v in list(s["sources"].items())[:5]), font=font(14), fill=DIM)
        for i, (f, v) in enumerate(s["families"][:10]):
            name = _family_block(ctx, f)
            img.alpha_composite(_swatch(ctx, name).resize((40, 40), Image.NEAREST), (16 + (i % 5) * 110, 70 + (i // 5) * 90))
            d.text((16 + (i % 5) * 110, 114 + (i // 5) * 90), f"{f.split(':', 1)[1][:12]}\n{v:.0%}", font=font(12), fill=DIM)
        for i, sid in enumerate(s["members"][:thumbs]):
            try:
                t = _thumb(ctx, sid, entries[sid])
            except Exception:
                continue
            t = t.copy()
            t.thumbnail((240, 230))
            img.alpha_composite(t.convert("RGBA"), (580 + i * 255, 60))
            d.text((580 + i * 255, 275), sid[:34], font=font(11), fill=DIM)
        rows.append(img)
    out.mkdir(parents=True, exist_ok=True)
    name = (flt.replace(":", "_").strip("_") or "all")
    sheet_img = _stack(rows, f"стили {flt or 'всех образцов'} — {len(st)} групп")
    sheet_img.save(out / f"styles-{name}.png")
    (out / f"styles-{name}.json").write_text(json.dumps(st, ensure_ascii=False, indent=1))
    print(out / f"styles-{name}.png")
    for n, s in enumerate(st):
        print(f"{n + 1:2d}. {s['label']:<50} {len(s['members']):4d}  лучшие: {', '.join(s['members'][:3])}")


def _family_block(ctx, fam):
    return kinds.family_block(fam)


def _stack(rows, title):
    W = max(r.width for r in rows) if rows else 800
    H = 60 + sum(r.height + 8 for r in rows)
    img = Image.new("RGBA", (W, H), BG)
    ImageDraw.Draw(img).text((16, 16), title, font=font(26), fill=INK)
    y = 60
    for r in rows:
        img.alpha_composite(r, (0, y))
        y += r.height + 8
    return img


# ------------------------------------------------------------------------------------------ сочетания, ярусы
class Vocab:
    """Глобальные номера блоков: 0 — воздух, 1 — природа, дальше — рукотворные id (без свойств)."""

    def __init__(self):
        self.ids = {"air": 0, "natural": 1}
        self.names = ["air", "natural"]

    def map(self, vol):
        lut = np.zeros(len(vol.palette) + 1, np.int32)
        for i, (name, _) in enumerate(vol.palette):
            if name in ("minecraft:air", "minecraft:cave_air", "minecraft:structure_void"):
                v = 0
            elif kinds.kind(name) in (kinds.NATURAL, kinds.RAIL, kinds.REDSTONE) or name in (
                    "minecraft:jigsaw", "minecraft:structure_block", "minecraft:hopper", "minecraft:dropper"):
                v = 1                                    # механика выживания — не отделка, в разбор вида не идёт
            else:
                v = self.ids.setdefault(name, len(self.ids))
                if v == len(self.names):
                    self.names.append(name)
            lut[i] = v
        lut[-1] = 0                                  # −1 (не задано) → воздух
        return lut[vol.idx]


def adjacency(arrs, vocab, min_count=40, origins=None):
    """Пары разных рукотворных блоков, касающихся гранями: [(a, b, пар, lift, в скольких зданиях)].
    Пара из одной постройки — её причуда, а не приём: нужна опора хотя бы в двух зданиях (или в 1/8)."""
    origins = origins or list(range(len(arrs)))
    pair, single, support = Counter(), Counter(), defaultdict(set)
    for a, org in zip(arrs, origins):
        art = a >= 2
        single.update(dict(zip(*np.unique(a[art], return_counts=True))))
        for ax in range(3):
            lo = np.take(a, range(a.shape[ax] - 1), axis=ax)
            hi = np.take(a, range(1, a.shape[ax]), axis=ax)
            m = (lo >= 2) & (hi >= 2) & (lo != hi)
            if not m.any():
                continue
            x, y = np.minimum(lo[m], hi[m]), np.maximum(lo[m], hi[m])
            key = x.astype(np.int64) * 100000 + y
            u, c = np.unique(key, return_counts=True)
            pair.update(dict(zip(u.tolist(), c.tolist())))
            for k in u.tolist():
                support[k].add(org)
    tot_s = sum(single.values()) or 1
    tot_p = sum(pair.values()) or 1
    need = max(2, len(set(origins)) // 8)
    out = []
    for key, c in pair.items():
        if c < min_count or len(support[key]) < need:
            continue
        a, b = divmod(int(key), 100000)
        if kinds.family(vocab.names[a]) == kinds.family(vocab.names[b]):
            continue                                   # ступени того же камня рядом со своим блоком — не сочетание
        lift = (c / tot_p) / ((single[a] / tot_s) * (single[b] / tot_s) * 2)
        out.append((vocab.names[a], vocab.names[b], c, lift, len(support[key])))
    out.sort(key=lambda t: -(t[4] * math.log(t[2]) * math.log(1 + t[3])))
    return out


def layers(arrs, vocab):
    """Низ, стены, верх: доли рукотворных id по трети высоты рукотворного в каждой постройке."""
    out = [Counter(), Counter(), Counter()]
    for a in arrs:
        ys = np.nonzero((a >= 2).any(axis=(0, 2)))[0]
        if len(ys) < 3:
            continue
        y0, y1 = ys[0], ys[-1] + 1
        cut = [y0, y0 + (y1 - y0) // 3, y0 + 2 * (y1 - y0) // 3, y1]
        for k in range(3):
            part = a[:, cut[k]:cut[k + 1], :]
            v, c = np.unique(part[part >= 2], return_counts=True)
            tot = c.sum() or 1
            for i, n in zip(v, c):
                out[k][kinds.family(vocab.names[i])] += n / tot
    return [[(f, v / max(1, len(arrs))) for f, v in c.most_common(10)] for c in out]


# ------------------------------------------------------------------------------------------------- мотивы
def _transforms(w):
    """8 вариантов куска: 4 поворота вокруг y и отражение. w — (n, 3, 3, 3) по x, y, z."""
    out = []
    for m in (w, w[:, ::-1]):
        for r in range(4):
            out.append(np.rot90(m, r, axes=(1, 3)))
    return out


def motifs(arrs, sids, min_support=3, top=48, origins=None):
    """Частые куски 3×3×3: [(хеш, опора — в скольких зданиях, всего, пример (sid, x, y, z))].
    origins — здание каждого объёма (сегменты одного кластера мира — одно здание); по умолчанию — sid."""
    origins = origins or sids
    rng = np.random.default_rng(7)
    weights = rng.integers(1, 2 ** 61, size=(3, 3, 3), dtype=np.int64)
    count, support, example = Counter(), defaultdict(set), {}
    for a, sid, org in zip(arrs, sids, origins):
        if min(a.shape) < 3:
            continue
        win = np.lib.stride_tricks.sliding_window_view(a, (3, 3, 3))
        c = a[1:-1, 1:-1, 1:-1]
        cand = c >= 2
        if not cand.any():
            continue
        pos = np.argwhere(cand)
        w = win[pos[:, 0], pos[:, 1], pos[:, 2]]                 # (n, 3, 3, 3)
        flat = w.reshape(len(w), -1)
        art = (flat >= 2).sum(1)
        filled = (flat >= 1).sum(1)
        srt = np.sort(flat, axis=1)
        distinct = ((srt[:, 1:] != srt[:, :-1]) & (srt[:, 1:] >= 2)).sum(1) + (srt[:, 0] >= 2)
        keep = (art >= 8) & (filled <= 24) & (distinct >= 3) & ((flat == 1).sum(1) <= 4)
        if not keep.any():
            continue
        w, pos = w[keep], pos[keep]
        hs = np.stack([(t.astype(np.int64) * weights[None]).sum(axis=(1, 2, 3)) for t in _transforms(w)])
        h = hs.min(0)
        u, first, n = np.unique(h, return_index=True, return_counts=True)
        for hv, fi, cnt in zip(u.tolist(), first.tolist(), n.tolist()):
            count[hv] += cnt
            support[hv].add(org)
            if hv not in example:
                example[hv] = (sid, *pos[fi].tolist())
    ranked = [(h, len(support[h]), c, example[h]) for h, c in count.items() if len(support[h]) >= min_support]
    ranked.sort(key=lambda t: -(t[1] * math.log(1 + t[2])))
    # Почти одинаковые мотивы (один блок разницы) склеивать дорого; просто не даём одному примеру занять всё
    seen_ex, out = set(), []
    for t in ranked:
        key = (t[3][0], t[3][1] // 4, t[3][2] // 4, t[3][3] // 4)
        if key in seen_ex:
            continue
        seen_ex.add(key)
        out.append(t)
        if len(out) >= top:
            break
    return out


# ------------------------------------------------------------------------------------------------- отчёт
def report(ctx, index, entries, out, flt="", style=None, top=48, min_score=0.0, limit=400):
    from refs import palette_card   # noqa: PLC0415
    ids = _sel(index, flt, style, min_score)
    ids = sorted(ids, key=lambda s: -index[s].get("score", 0))[:limit]
    vocab = Vocab()
    arrs, vols, sids = [], {}, []
    for sid in ids:
        try:
            v = _load(entries, sid)
        except Exception:
            continue
        arrs.append(vocab.map(v))
        vols[sid] = v
        sids.append(sid)
    name = (flt.replace(":", "_").strip("_") or "all") + (f"-{style}" if style else "")
    out.mkdir(parents=True, exist_ok=True)
    title = f"{flt or style or 'все'}: {len(sids)} построек"

    # Сочетания
    origin = [f"{index[s_].get('source')}#{index[s_]['cluster']}" if "cluster" in index[s_] else s_ for s_ in sids]
    adj = adjacency(arrs, vocab, origins=origin)
    rows = [(a, b, c, lift) for a, b, c, lift, _ in adj[:30]]
    img = _pairs_card(ctx, rows, f"сочетания — {title}")
    img.save(out / f"pairs-{name}.png")

    # Ярусы
    lay = layers(arrs, vocab)
    cards = [palette_card(ctx, [(_family_block(ctx, f), v) for f, v in l], t)
             for l, t in zip(lay, ("низ", "середина", "верх"))]
    W = sum(c.width for c in cards) + 16 * 4
    H = max(c.height for c in cards) + 70
    li = Image.new("RGBA", (W, H), BG)
    ImageDraw.Draw(li).text((16, 16), f"ярусы — {title}", font=font(24), fill=INK)
    x = 16
    for c in cards:
        li.alpha_composite(c, (x, 60))
        x += c.width + 16
    li.save(out / f"layers-{name}.png")

    # Мотивы
    # Опора мотива — в скольких разных зданиях: сегменты одного кластера мира — одно здание
    mot = motifs(arrs, sids, min_support=max(2, min(5, len(set(origin)) // 20)), top=top, origins=origin)
    tiles = []
    for h, sup, cnt, (sid, x0, y0, z0) in mot:
        v = vols[sid]
        sub = Volume((3, 3, 3), v.palette, np.ascontiguousarray(v.idx[x0:x0 + 3, y0:y0 + 3, z0:z0 + 3]), name="m")
        timg, _ = render(ctx, sub, "iso_se", 22.0, markers=False)
        blocks = Counter(v.palette[i][0].split(":", 1)[1] for i in sub.idx.ravel() if i >= 0
                         and kinds.kind(v.palette[i][0]) != kinds.NATURAL)
        cap = f"в {sup} постр., {cnt}×  " + ", ".join(b for b, _ in blocks.most_common(3))
        tiles.append((cap, timg))
    if tiles:
        sheet(tiles, f"мотивы 3×3×3 — {title}", width=1600, cols=6).save(out / f"motifs-{name}.png")

    res = {"builds": sids, "pairs": [[a, b, int(c), round(float(l), 2), int(n)] for a, b, c, l, n in adj[:80]],
           "layers": [[[f, round(float(v), 4)] for f, v in l] for l in lay],
           "motifs": [{"support": int(s), "count": int(c), "example": [e[0]] + [int(v) for v in e[1:]]}
                      for _, s, c, e in mot]}
    (out / f"patterns-{name}.json").write_text(json.dumps(res, ensure_ascii=False, indent=1))
    for f in ("pairs", "layers", "motifs"):
        print(out / f"{f}-{name}.png")
    return res


def _pairs_card(ctx, rows, title):
    from refs import _swatch   # noqa: PLC0415
    W, rh = 1100, 44
    img = Image.new("RGBA", (W, 60 + rh * len(rows)), BG)
    d = ImageDraw.Draw(img)
    d.text((16, 14), title, font=font(22), fill=INK)
    f = font(14)
    for k, (a, b, c, lift) in enumerate(rows):
        y = 56 + k * rh
        img.alpha_composite(_swatch(ctx, a).resize((32, 32), Image.NEAREST), (16, y))
        img.alpha_composite(_swatch(ctx, b).resize((32, 32), Image.NEAREST), (50, y))
        d.text((96, y + 8), f"{a.split(':', 1)[1]}  +  {b.split(':', 1)[1]}", font=f, fill=INK)
        d.text((760, y + 8), f"{c} касаний, ×{lift:.1f} чаще случайного", font=f, fill=DIM)
    return img
