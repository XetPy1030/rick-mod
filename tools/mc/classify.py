"""Разметка образца по осям tools/refs/taxonomy.json: тип, стиль, масштаб, среда, зоны Цитадели.

Автоматика — три источника, по убыванию доверия:
1. слова в id (у структур из jar: «village/desert/houses/…», «castle», «tower»);
2. геометрия: высота к основанию, закрытый объём (помещения), опора на землю, крыша из рельефа;
3. материалы: подписи стилей — веса семейств (kinds.family) в taxonomy.json.
Поверх — ручные метки tools/refs/labels.json: что там сказано, то и правда.
"""
import fnmatch
import json
import re
from functools import lru_cache

import numpy as np

from . import OUT, TOOLS, kinds
from .registry import default, split_id

TAXONOMY = TOOLS / "refs" / "taxonomy.json"
LABELS = TOOLS / "refs" / "labels.json"
MODEL = OUT / "refs" / "style-model.npz"
_AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void"}
_SKIP = {"minecraft:jigsaw", "minecraft:structure_block", "minecraft:barrier", "minecraft:light"}
_COLORS = ("red_", "orange_", "yellow_", "lime_", "green_", "cyan_", "light_blue_", "blue_", "purple_", "magenta_",
           "pink_", "brown_")


@lru_cache(maxsize=None)
def taxonomy():
    return json.loads(TAXONOMY.read_text(encoding="utf-8"))


def labels():
    return json.loads(LABELS.read_text(encoding="utf-8")) if LABELS.exists() else {}


def save_labels(d):
    LABELS.write_text(json.dumps(dict(sorted(d.items())), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


# ------------------------------------------------------------------------------------------------ признаки
def features(vol):
    """Числа, по которым решаются тип, масштаб и среда. Считается только рукотворное; рельеф — как стены."""
    reg = default()
    n = len(vol.palette)
    art_l = np.zeros(n + 1, bool)
    air_l = np.ones(n + 1, bool)                   # −1 (не задано) — воздух
    water_l = np.zeros(n + 1, bool)
    counts = np.bincount(vol.idx[vol.idx >= 0].ravel(), minlength=n)
    fam, kind_c, shape_c = {}, {}, {}
    colored = light = 0
    for i, (name, props) in enumerate(vol.palette):
        air_l[i] = name in _AIR
        water_l[i] = name == "minecraft:water" or props.get("waterlogged") == "true"
        k = kinds.kind(name)
        if name in _AIR or name in _SKIP or k in (kinds.NATURAL, kinds.RAIL):
            continue
        art_l[i] = True
        c = int(counts[i])
        f = kinds.family(name)
        fam[f] = fam.get(f, 0) + c
        kind_c[k] = kind_c.get(k, 0) + c
        sh = kinds.shape(name)
        shape_c[sh] = shape_c.get(sh, 0) + c
        if split_id(name)[1].startswith(_COLORS):
            colored += c
        if reg.light(name, props) >= 10:
            light += c
    art = art_l[vol.idx]
    total = int(art.sum())
    out = {"artificial": total, "families": fam, "kinds": kind_c, "shapes": shape_c}
    if total == 0:
        return out
    air = air_l[vol.idx]
    ys = np.nonzero(art.any(axis=(0, 2)))[0]
    xs = np.nonzero(art.any(axis=(1, 2)))[0]
    zs = np.nonzero(art.any(axis=(0, 1)))[0]
    cols = art.any(axis=1)
    h = int(ys[-1] - ys[0] + 1)
    dx, dz = int(xs[-1] - xs[0] + 1), int(zs[-1] - zs[0] + 1)

    # Закрытый объём: воздух, над которым есть блок и который со всех четырёх сторон упирается в блоки
    solid = ~air
    def seen(a, axis, rev):
        a = np.flip(a, axis) if rev else a
        acc = np.maximum.accumulate(a, axis=axis)
        acc = np.flip(acc, axis) if rev else acc
        return acc
    above = seen(solid, 1, True)
    enclosed = air & above & seen(solid, 0, False) & seen(solid, 0, True) & seen(solid, 2, False) & seen(solid, 2, True)
    enclosed &= art.any(axis=1, keepdims=True)     # только под рукотворными колоннами — не пещеры
    interior = int(enclosed.sum())
    int_h = int(enclosed.sum(axis=1).max()) if interior else 0

    # Опора: есть ли рельеф под нижним рукотворным блоком колонны; крыша из рельефа — над рукотворным
    natural = ~air & ~art
    low = np.argmax(art, axis=1)                   # первый рукотворный снизу
    xi, zi = np.nonzero(cols)
    ly = low[xi, zi]
    below_nat = np.array([natural[x, max(0, y - 3):y, z].any() for x, y, z in zip(xi, ly, zi)]) if len(xi) else []
    over_nat = np.zeros_like(natural)
    over_nat[:, :-1, :] = seen(natural, 1, True)[:, 1:, :]     # рельеф где-то выше этого блока
    under = int((art & over_nat).sum())
    out.update({
        "size": [dx, h, dz], "footprint": int(cols.sum()), "interior": interior, "interior_h": int_h,
        "grounded": float(np.mean(below_nat)) if len(xi) else 0.0,
        "underground": under / total, "water": float(water_l[vol.idx].mean()),
        "colored": colored / total, "light": light / total,
        "detail": 1 - shape_c.get("full", 0) / total,
    })
    return out


# ------------------------------------------------------------------------------------------------- решения
_WORLD_ID = re.compile(r"^[^:]+:(overworld|nether|end|[a-z0-9_]+_[a-z0-9_]+)_-?\d+_-?\d+")


def is_world(sid):
    """Постройка, вырезанная из мира: «hermitcraft7:nether_-170_75_170». Слова в таком id — это измерение
    и координаты, а не стиль: «nether» тут не значит «из незерского кирпича»."""
    return bool(_WORLD_ID.match(sid))


def _words(sid):
    return set(w for w in re.split(r"[:/_\-.\d]+", sid.lower()) if w) | {sid.lower()}


def _hit(words, sid, vocab):
    low = sid.lower()
    return any(w in words or (("_" in w or "/" in w) and w in low) for w in vocab)


def style_scores(fam_counts):
    """Стиль по материалам: Σ доля семейства × вес лучшего шаблона. Возвращает {стиль: очки}."""
    tot = sum(fam_counts.values()) or 1
    out = {}
    for style, sig in taxonomy()["styles"].items():
        pats = [((p if ":" in p else "minecraft:" + p), w) for p, w in sig.items()]
        s = 0.0
        for f, c in fam_counts.items():
            best = max((w for p, w in pats if fnmatch.fnmatchcase(f, p)), default=0.0)
            s += best * c / tot
        out[style] = s
    return out


def classify(vol, sid, feat=None):
    """Автоматические метки: {"type": [[тип, уверенность]], "style": [...], "scale", "env", "feat"}."""
    tx = taxonomy()
    f = feat or features(vol)
    words = _words(sid)
    types, styles = {}, {}
    if not f.get("artificial"):
        return {"type": [], "style": [], "scale": "detail", "env": "ground", "feat": {}}

    # 1. Слова в id (у построек из миров там только измерение и координаты)
    if is_world(sid):
        words = set()
    for t, vocab in tx["type_words"].items():
        if _hit(words, sid, vocab):
            types[t] = 1.0
    for s, vocab in tx["style_words"].items():
        if _hit(words, sid, vocab):
            styles[s] = max(styles.get(s, 0), 0.6)

    # 2. Материалы
    raw = style_scores(f["families"])
    if f.get("colored", 0) < 0.08:
        raw["cyberpunk"] *= 0.4                    # без цвета киберпанка нет
    if f.get("light", 0) < 0.004:
        raw["futuristic"] *= 0.7
    top = max(raw.values()) or 1
    for s, v in raw.items():
        if v >= 0.18 and v >= 0.6 * top:
            styles[s] = max(styles.get(s, 0), round(min(1.0, v), 3))

    # 3. Геометрия
    dx, h, dz = f["size"]
    big, small = max(dx, dz), min(dx, dz)
    k = f["kinds"]
    art = f["artificial"]
    share = lambda *names: sum(k.get(x, 0) for x in names) / art  # noqa: E731
    if h >= 12 and h >= 1.8 * big:
        types.setdefault("tower", 0.8)
    if h <= 5 and f["footprint"] >= 150:
        types.setdefault("plaza", 0.7)
    if big >= 4 * small and small <= 7 and big >= 16:
        types.setdefault("bridge" if f["grounded"] < 0.5 else "wall", 0.5)
    if share(kinds.FARM) >= 0.2:
        types.setdefault("farm", 0.8)
    if share(kinds.REDSTONE, kinds.WORKSHOP) >= 0.06:
        types.setdefault("workshop", 0.6)
    if share(kinds.STORAGE) >= 0.05:
        types.setdefault("storage", 0.6)
    if share(kinds.PORTAL) > 0:
        types.setdefault("portal", 0.6)
    if f["underground"] >= 0.8:                    # подвалы и парковки под городом — ещё не подземелье
        types.setdefault("dungeon", 0.6)
    if raw.get("ruined", 0) >= 0.25:
        types.setdefault("ruin", 0.5)
    if f["interior"] >= 1500 and f["interior_h"] >= 7:
        types.setdefault("hall", 0.6)
    elif f["interior"] >= 60:
        types.setdefault("house", 0.5 if share(kinds.BED, kinds.FURNITURE, kinds.STORAGE) > 0 else 0.35)
    furnish = share(kinds.FURNITURE, kinds.STORAGE, kinds.WORKSHOP, kinds.MAGIC, kinds.BED) + \
        f["shapes"].get("tiny", 0) / art
    if f["interior"] >= 60 and furnish >= 0.04:
        types.setdefault("interior", round(min(1.0, 0.4 + furnish * 4), 2))
    if f["grounded"] < 0.3 and f["underground"] < 0.1:
        types.setdefault("ship", 0.3)

    # Масштаб и среда
    m = max(dx, h, dz)
    scale = ("detail" if art < 150 or m <= 7 else "room" if m <= 16 else "building" if m <= 48
             else "complex" if m <= 128 else "district")
    dim = sid.split(":", 1)[1].split("_", 1)[0] if ":" in sid else ""
    if dim == "nether" or styles.get("nether", 0) >= 0.6 and "nether" in sid:
        env = "nether"
    elif dim == "end" or "end_city" in sid or sid.startswith("betterend:"):
        env = "end"
    elif f["water"] >= 0.15:
        env = "water"
    elif f["underground"] >= 0.8:
        env = "underground"
    elif f["grounded"] < 0.3:
        env = "floating"
    else:
        env = "ground"
    if env == "nether":
        styles["nether"] = max(styles.get("nether", 0), 0.4)
    if env == "end":
        styles["end"] = max(styles.get("end", 0), 0.3)

    feat_out = {k: (round(v, 3) if isinstance(v, float) else v) for k, v in f.items()
                if k in ("size", "footprint", "interior", "interior_h", "grounded", "underground", "water",
                         "colored", "light", "detail")}
    return {"type": sorted(([t, v] for t, v in types.items()), key=lambda x: -x[1]),
            "style": sorted(([s, v] for s, v in styles.items()), key=lambda x: -x[1])[:4],
            "raw_style": {s: round(v, 3) for s, v in sorted(raw.items(), key=lambda kv: -kv[1])[:5]},
            "scale": scale, "env": env, "feat": feat_out}


def final(entry, sid, lab=None):
    """Метки с учётом ручных: {"type": [...], "style": [...], "scale", "env", "rating", "note", "manual"}."""
    auto = entry.get("tags") or {}
    lab = (lab if lab is not None else labels()).get(sid, {})
    out = {"type": [t for t, v in auto.get("type", []) if v >= 0.5] or [t for t, _ in auto.get("type", [])[:1]],
           "style": [s for s, _ in auto_styles(entry, sid)[:2]],
           "scale": auto.get("scale"), "env": auto.get("env"), "rating": None, "note": "", "manual": False}
    for k in ("type", "style"):
        if k in lab:
            out[k] = lab[k] if isinstance(lab[k], list) else [lab[k]]
            out["manual"] = True
    for k in ("scale", "env", "rating", "note", "reject"):
        if k in lab:
            out[k] = lab[k]
            out["manual"] = True
    if "zones" in lab:
        out["zones_manual"] = lab["zones"]
    return out


def zone_scores(entry, sid, lab=None):
    """Насколько образец подходит зонам Цитадели: {зона: 0..1}. Ручные зоны — 1."""
    tx = taxonomy()
    fin = final(entry, sid, lab)
    if fin.get("reject"):
        return {}
    auto = entry.get("tags") or {}
    st = dict(auto_styles(entry, sid))
    if fin["manual"] and fin["style"]:
        st = {s: 1.0 for s in fin["style"]}
    ty = dict((t, v) for t, v in auto.get("type", []))
    if fin["manual"] and fin["type"]:
        ty = {t: 1.0 for t in fin["type"]}
    q = min(1.0, entry.get("score", 0) / 4.0)
    quality = q * q if not fin.get("rating") else fin["rating"] / 5      # слабое без ручной оценки — в конец
    feat = auto.get("feat", {})
    out = {}
    for z, rule in tx["zones"].items():
        sm = max((w * st.get(s, 0) for s, w in rule["styles"].items()), default=0)
        tm = max((w * ty.get(t, 0) for t, w in rule["types"].items()), default=0)
        v = sm * (0.4 + 0.6 * tm) * (0.1 + 0.9 * quality)
        if rule.get("interior") and feat.get("interior", 0) < 60:
            v *= 0.5
        if v >= 0.05:
            out[z] = round(v, 3)
    for z in fin.get("zones_manual", []):
        out[z] = 0.8 + 0.04 * (fin.get("rating") or 3)      # ручная зона — выше любой автоматики, пятёрки первыми
    return dict(sorted(out.items(), key=lambda kv: -kv[1]))


def truth_from_id(sid):
    """Стиль по словам id — для проверки правил по материалам на структурах из jar."""
    if is_world(sid):
        return None
    words = _words(sid)
    hits = [s for s, vocab in taxonomy()["style_words"].items() if _hit(words, sid, vocab)]
    return hits[0] if len(hits) == 1 else None


def check(index):
    """Точность стиля по материалам там, где стиль известен по id: {стиль: (верно, всего)}, путаница."""
    res, confusion = {}, {}
    for sid, e in index.items():
        t = truth_from_id(sid)
        raw = (e.get("tags") or {}).get("raw_style")
        if not t or not raw:
            continue
        pred = next(iter(raw))
        ok, n = res.get(t, (0, 0))
        res[t] = (ok + (pred == t), n + 1)
        if pred != t:
            confusion[(t, pred)] = confusion.get((t, pred), 0) + 1
    return res, sorted(confusion.items(), key=lambda kv: -kv[1])[:15]


# ------------------------------------------------------------------------------------------ обучаемый стиль
def _features(entry, vocab):
    """Корень долей семейств (топ-12 из индекса) + цветное и свет — вектор для модели."""
    fam = entry.get("families") or []
    tot = sum(c for _, c in fam) or 1
    x = np.zeros(len(vocab) + 2)
    for f, c in fam:
        i = vocab.get(f)
        if i is not None:
            x[i] = np.sqrt(c / tot)
    feat = (entry.get("tags") or {}).get("feat", {})
    x[-2] = min(1.0, feat.get("colored", 0) * 3)
    x[-1] = min(1.0, feat.get("light", 0) * 20)
    return x


def train(index, lab=None, min_examples=15, iters=400, lr=0.5, l2=1e-3, seed=26):
    """Softmax-регрессия стиля: примеры — стиль по словам id (вес 1) и ручные метки (вес 3).
    Стили, где примеров меньше min_examples, остаются за подписями из taxonomy.json.
    Возвращает точность на отложенных 20% по стилям."""
    lab = lab if lab is not None else labels()
    rows = []
    for sid, e in index.items():
        if "error" in e or not e.get("families"):
            continue
        man = lab.get(sid, {})
        if man.get("reject"):
            continue
        if man.get("style"):
            rows.append((sid, man["style"][0], 3.0))
        else:
            t = truth_from_id(sid)
            if t:
                rows.append((sid, t, 1.0))
    counts = {}
    for _, t, _ in rows:
        counts[t] = counts.get(t, 0) + 1
    classes = sorted(c for c, n in counts.items() if n >= min_examples)
    rows = [r for r in rows if r[1] in classes]
    df = {}
    for sid, _, _ in rows:
        for f, _ in index[sid]["families"]:
            df[f] = df.get(f, 0) + 1
    vocab = {f: i for i, f in enumerate(sorted(f for f, n in df.items() if n >= 5))}
    X = np.array([_features(index[sid], vocab) for sid, _, _ in rows])
    y = np.array([classes.index(t) for _, t, _ in rows])
    w = np.array([wt for _, _, wt in rows])
    rng = np.random.default_rng(seed)
    test = rng.random(len(rows)) < 0.2
    # Классы уравновешены: иначе тысячи деревенских домов задавят редкие стили
    cw = np.array([1.0 / max(1, (y == k).sum()) for k in range(len(classes))])
    w = w * cw[y] * len(rows) / len(classes)

    def fit(mask):
        W = np.zeros((X.shape[1], len(classes)))
        b = np.zeros(len(classes))
        Y = np.eye(len(classes))[y[mask]]
        Xm, wm = X[mask], w[mask][:, None]
        for _ in range(iters):
            z = Xm @ W + b
            z -= z.max(1, keepdims=True)
            P = np.exp(z)
            P /= P.sum(1, keepdims=True)
            G = (P - Y) * wm / wm.sum()
            W -= lr * (Xm.T @ G + l2 * W)
            b -= lr * G.sum(0)
        return W, b

    W, b = fit(~test)
    pred = np.argmax(X[test] @ W + b, 1)
    acc = {}
    for k, c in enumerate(classes):
        m = y[test] == k
        if m.any():
            acc[c] = (int((pred[m] == k).sum()), int(m.sum()))
    W, b = fit(np.ones(len(rows), bool))      # итоговая — на всех примерах
    MODEL.parent.mkdir(parents=True, exist_ok=True)
    np.savez(MODEL, W=W, b=b, classes=np.array(classes), vocab=np.array(json.dumps(vocab)))
    _model.cache_clear()
    return acc, counts


@lru_cache(maxsize=1)
def _model():
    if not MODEL.exists():
        return None
    z = np.load(MODEL)
    return z["W"], z["b"], [str(c) for c in z["classes"]], json.loads(str(z["vocab"]))


def ml_styles(entry):
    m = _model()
    if m is None or not entry.get("families"):
        return {}
    W, b, classes, vocab = m
    z = _features(entry, vocab) @ W + b
    z = np.exp(z - z.max())
    p = z / z.sum()
    return {c: float(v) for c, v in zip(classes, p)}


def auto_styles(entry, sid):
    """Стиль без ручных меток: слова id → модель (для стилей, где она обучена) → подписи материалов."""
    auto = entry.get("tags") or {}
    out = {}
    tx = taxonomy()
    if not is_world(sid):
        words = _words(sid)
        for s, vocab in tx["style_words"].items():
            if _hit(words, sid, vocab):
                out[s] = 0.9
    ml = ml_styles(entry)
    for s, v in ml.items():
        if v >= 0.3:
            out[s] = max(out.get(s, 0), round(v, 3))
    trained = set(ml)
    raw = auto.get("raw_style") or {}
    top = max(raw.values(), default=0) or 1
    for s, v in raw.items():
        if s not in trained and v >= 0.2 and v >= 0.6 * top:
            out[s] = max(out.get(s, 0), round(min(1.0, v), 3))
    env = auto.get("env")
    if env in ("nether", "end"):
        out[env] = max(out.get(env, 0), 0.5)
    return sorted(out.items(), key=lambda kv: -kv[1])
