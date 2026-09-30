"""Источники образцов: реестр tools/refs/sources.json, учёт трафика, загрузка с Modrinth.

Скачанное — в run/refs/ (вне git): jars/ — моды и датапаки с постройками, worlds/<id>/ — вырезанное
из миров, inbox/ — что владелец скачал руками (Planet Minecraft и CurseForge закрыты от скриптов).
"""
import json
import time
import urllib.parse
import urllib.request

from . import ROOT, TOOLS
from .remote import UA, fetch

SOURCES = TOOLS / "refs" / "sources.json"
DL = ROOT / "run" / "refs"
LEDGER = DL / "traffic.json"
MODRINTH = "https://api.modrinth.com/v2"
# Не постройки, а библиотеки, настройки, совместимость и сборки под Forge — слова id целиком
SKIP_WORDS = {"api", "compat", "optimizer", "compass", "sparse", "structurify", "gel", "lib", "cobblemon", "forge",
              "neoforge", "extras", "sparsestructures"}


def load():
    return json.loads(SOURCES.read_text(encoding="utf-8"))


def save(d):
    SOURCES.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def get(sid):
    for s in load()["sources"]:
        if s["id"] == sid:
            return s
    raise SystemExit(f"нет источника {sid} в {SOURCES}")


def traffic():
    """Потрачено по источникам: jar — из журнала, миры — из сводок сканов (builds.json)."""
    t = json.loads(LEDGER.read_text()) if LEDGER.exists() else {}
    for bj in (DL / "worlds").glob("*/builds.json"):
        t[bj.parent.name] = json.loads(bj.read_text())["summary"]["fetched"]
    return t


def spend(sid, nbytes):
    t = traffic()
    t[sid] = t.get(sid, 0) + int(nbytes)
    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    LEDGER.write_text(json.dumps(t, indent=1))


def left():
    """Сколько байт осталось от бюджета."""
    return load()["budget_gb"] * 1e9 - sum(traffic().values())


def _api(path, **params):
    url = f"{MODRINTH}{path}" + ("?" + urllib.parse.urlencode(params) if params else "")
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
        return json.loads(r.read())


def modrinth_top(n=24):
    """Самые скачиваемые моды и датапаки с постройками → записи реестра modrinth:<slug>."""
    hits = []
    for q in ("structures", "dungeons", "villages", "towns"):
        res = _api("/search", query=q, index="downloads", limit=60,
                   facets=json.dumps([["project_type:mod", "project_type:datapack"], ["categories:worldgen"]]))
        hits += res["hits"]
    from .registry import default
    have = set(default().meta.get("mods", []))          # уже в сборке — их постройки и так в индексе
    seen, out = set(), []
    for h in sorted(hits, key=lambda h: -h["downloads"]):
        slug = h["slug"]
        if slug in seen or set(slug.split("-")) & SKIP_WORDS or slug.replace("-", "_") in have or slug in have:
            continue
        seen.add(slug)
        out.append({"id": f"modrinth:{slug}", "kind": "jar", "modrinth": slug, "title": h["title"],
                    "why": h["description"][:200], "rating": f"Modrinth: {h['downloads']:,} скачиваний, "
                                                             f"{h['follows']:,} подписчиков".replace(",", " "),
                    "downloads": h["downloads"], "license": h.get("license"), "mc": h["versions"][-1],
                    "styles": []})
        if len(out) >= n:
            break
    return out


def modrinth_file(slug):
    """Последний файл проекта: (url, имя, размер)."""
    versions = _api(f"/project/{slug}/version")
    for v in versions:                      # новее — раньше
        for f in v["files"]:
            if f.get("primary") or len(v["files"]) == 1:
                return f["url"], f["filename"], f["size"]
    raise OSError(f"у {slug} нет файлов")


def fetch_jar(src, log=print):
    """Скачать мод или датапак источника в run/refs/jars/. Уже скачанное не качается."""
    url, fname, size = modrinth_file(src["modrinth"])
    path = DL / "jars" / f"{src['modrinth']}__{fname}"
    if path.exists() and path.stat().st_size == size:
        return path
    if size > left():
        raise SystemExit(f"{src['id']}: {size / 1e6:.0f} МБ не влезает в бюджет, осталось {left() / 1e6:.0f} МБ")
    path.parent.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    fetch(url, path)
    spend(src["id"], size)
    log(f"{src['id']}: {size / 1e6:.1f} МБ за {time.time() - t0:.0f} с")
    return path
