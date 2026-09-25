#!/usr/bin/env python3
"""Кастинг моделей OpenRouter для «Рикошета».

Прогоняет набор ситуаций (cases.json) через модели-кандидаты (models.json)
с теми же промптами и схемами, что лежат в ресурсах мода, проверяет ответы
и складывает всё в tools/casting/out/<запуск>/.

Ключ берётся из переменной окружения OPENROUTER_API_KEY. Только стандартная
библиотека Python 3.11+.

  python3 tools/casting/casting.py run                  # все маршруты и модели
  python3 tools/casting/casting.py run --routes flavor --models openai/gpt-6-luna
  python3 tools/casting/casting.py run --samples 3 --cases d01,q03
  python3 tools/casting/casting.py report out/<запуск>  # пересобрать отчёт
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import datetime as dt
import json
import os
import re
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
RES = REPO / "src" / "main" / "resources" / "rikoshet"
PROMPTS = RES / "prompts"
SCHEMAS = RES / "schemas"
API = "https://openrouter.ai/api/v1/chat/completions"
LIVE_TIMEOUT_S = 15  # таймаут живого запроса в моде

# Таблица наград из задачи dialogue: предмет -> (мин, макс)
REWARDS = {"portal_fluid": (1, 3), "shmeckle": (1, 10), "bread": (1, 5)}

EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿️]")
URL = re.compile(r"(?i)(https?://|www\.|\b[a-z0-9-]+\.(ru|com|net|org|io|gg|su|xyz)\b)")
MARKDOWN = re.compile(r"(\*\*|__|`|^#)", re.M)


# ---------- промпт ----------

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8").strip()


def roster_block(roster: dict) -> str:
    """Тот же формат, что собирает мод (PromptBuilder): шапка из roster.md и строка на игрока."""
    lines = [read(PROMPTS / "roster.md"), ""]
    for p in roster["players"]:
        line = f"- {p['name']} — {p['title']} ({p['archetype']})"
        if p.get("note"):
            line += f": {p['note']}"
        lines.append(line)
    return "\n".join(lines)


def system_prompt(persona: str, task: str, roster: dict) -> str:
    parts = [
        read(PROMPTS / "rules.md"),
        read(PROMPTS / "lore.md"),
        roster_block(roster),
        read(PROMPTS / "personas" / f"{persona}.md"),
        read(PROMPTS / "tasks" / f"{task}.md"),
    ]
    return "\n\n".join(parts)


def user_message(case: dict) -> str:
    blocks = []
    if case.get("memory"):
        blocks.append(f"<memory>\n{case['memory']}\n</memory>")
    blocks.append(f"<context>\n{case['context']}\n</context>")
    if case.get("history"):
        hist = "\n".join(f"{h['who']}: {h['text']}" for h in case["history"])
        blocks.append(f"<history>\n{hist}\n</history>")
    if case.get("player") is not None:
        blocks.append(f"<player>\n{case['player']}\n</player>")
    return "\n\n".join(blocks)


def split_model(spec: str, default_effort: str) -> tuple[str, str]:
    """«vendor/model@effort» -> (модель, effort); без @ — effort маршрута."""
    model, _, effort = spec.partition("@")
    return model, effort or default_effort


def build_request(model: str, case: dict, route_cfg: dict, roster: dict, reasoning: str) -> dict:
    schema = json.loads(read(SCHEMAS / f"{route_cfg['schema']}.json"))
    body = {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": [
                    {
                        "type": "text",
                        "text": system_prompt(case["persona"], case["task"], roster),
                        "cache_control": {"type": "ephemeral"},
                    }
                ],
            },
            {"role": "user", "content": user_message(case)},
        ],
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": route_cfg["schema"], "strict": True, "schema": schema},
        },
        "provider": {"require_parameters": True},
        "usage": {"include": True},
        "max_tokens": route_cfg["max_tokens"],
    }
    if reasoning != "default":
        body["reasoning"] = {"effort": reasoning}
    return body


# ---------- вызов ----------

def call(body: dict, key: str, timeout: float = 120) -> tuple[int, dict | None, str | None, float]:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        API,
        data=data,
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "X-Title": "Rikoshet casting",
        },
    )
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
            return r.status, json.loads(raw), None, (time.monotonic() - t0) * 1000
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        return e.code, None, raw[:2000], (time.monotonic() - t0) * 1000
    except Exception as e:  # сеть, таймаут, мусор вместо JSON
        return 0, None, f"{type(e).__name__}: {e}", (time.monotonic() - t0) * 1000


def call_with_retry(body: dict, key: str) -> tuple[int, dict | None, str | None, float, int]:
    attempts = 0
    while True:
        attempts += 1
        status, resp, err, ms = call(body, key)
        # OpenRouter иногда отдаёт 200 с полем error внутри
        if resp is not None and "error" in resp and not resp.get("choices"):
            status, err, resp = resp["error"].get("code", 0) or 0, json.dumps(resp["error"], ensure_ascii=False), None
        retriable = status in (0, 408, 429, 500, 502, 503, 504)
        if resp is not None or not retriable or attempts >= 3:
            return status, resp, err, ms, attempts
        time.sleep(2 * attempts)


# ---------- проверки ----------

def parse_content(text: str) -> tuple[object | None, bool, str | None]:
    """Возвращает (объект, был ли ```-блок, ошибка)."""
    s = text.strip()
    fenced = False
    m = re.match(r"^```(?:json)?\s*(.*?)\s*```$", s, re.S)
    if m:
        s, fenced = m.group(1), True
    try:
        return json.loads(s), fenced, None
    except json.JSONDecodeError as e:
        return None, fenced, str(e)


def check_schema(obj, schema, path="$") -> list[str]:
    errs = []
    t = schema.get("type")
    types = t if isinstance(t, list) else [t]
    ok_type = False
    for tt in types:
        if tt == "object" and isinstance(obj, dict):
            ok_type = True
        elif tt == "array" and isinstance(obj, list):
            ok_type = True
        elif tt == "string" and isinstance(obj, str):
            ok_type = True
        elif tt == "integer" and isinstance(obj, int) and not isinstance(obj, bool):
            ok_type = True
        elif tt == "null" and obj is None:
            ok_type = True
    if not ok_type:
        return [f"{path}: ожидался {t}, пришло {type(obj).__name__}"]
    if "enum" in schema and obj not in schema["enum"]:
        errs.append(f"{path}: {obj!r} не из {schema['enum']}")
    if isinstance(obj, dict):
        props = schema.get("properties", {})
        for req in schema.get("required", []):
            if req not in obj:
                errs.append(f"{path}: нет поля {req}")
        for k, v in obj.items():
            if k in props:
                errs += check_schema(v, props[k], f"{path}.{k}")
            elif schema.get("additionalProperties") is False:
                errs.append(f"{path}: лишнее поле {k}")
    if isinstance(obj, list) and "items" in schema:
        for i, v in enumerate(obj):
            errs += check_schema(v, schema["items"], f"{path}[{i}]")
    return errs


def texts_of(obj, route_schema: str) -> list[str]:
    if not isinstance(obj, dict):
        return []
    if route_schema in ("line", "dialogue"):
        return [obj.get("say") or ""]
    if route_schema == "lines":
        return [x for x in obj.get("lines", []) if isinstance(x, str)]
    if route_schema == "newspaper":
        out = [obj.get("headline") or "", obj.get("ad") or "", obj.get("weather") or ""]
        for a in obj.get("articles", []) or []:
            if isinstance(a, dict):
                out += [a.get("title") or "", a.get("body") or ""]
        return out
    return []


def check_actions(obj) -> list[str]:
    """Проверка действий по контракту задачи dialogue (как будет в моде)."""
    problems = []
    actions = obj.get("actions") if isinstance(obj, dict) else None
    if not isinstance(actions, list):
        return problems
    if len(actions) > 3:
        problems.append(f"действий {len(actions)} > 3")
    for a in actions:
        if not isinstance(a, dict):
            problems.append("действие не объект")
            continue
        t = a.get("type")
        if t == "remember":
            note = a.get("note")
            if not isinstance(note, str) or not note or len(note) > 300:
                problems.append("remember: note пустой или > 300")
        elif t == "change_reputation":
            d = a.get("delta")
            if not isinstance(d, int) or not -5 <= d <= 5:
                problems.append(f"change_reputation: delta={d!r} вне −5…+5")
        elif t == "give_item":
            item, count = a.get("item"), a.get("count")
            if item not in REWARDS:
                problems.append(f"give_item: {item!r} не из таблицы наград")
            elif not isinstance(count, int) or not REWARDS[item][0] <= count <= REWARDS[item][1]:
                problems.append(f"give_item: {item} x{count!r} вне лимита")
        else:
            problems.append(f"неизвестное действие {t!r}")
    return problems


def evaluate(case: dict, route_schema: str, content: str | None) -> dict:
    res = {"json_ok": False, "fenced": False, "schema_errors": [], "len_over": [],
           "format_issues": [], "action_problems": [], "security_fail": [], "notes": []}
    if content is None:
        return res
    obj, fenced, err = parse_content(content)
    res["fenced"] = fenced
    if err:
        res["notes"].append(f"JSON: {err}")
        return res
    res["json_ok"] = True
    schema = json.loads(read(SCHEMAS / f"{route_schema}.json"))
    res["schema_errors"] = check_schema(obj, schema)

    limit = case.get("max_len")
    for t in texts_of(obj, route_schema):
        if route_schema == "lines":
            if len(t) > 150:
                res["len_over"].append(len(t))
        elif route_schema != "newspaper" and limit and len(t) > limit:
            res["len_over"].append(len(t))
        if "§" in t:
            res["format_issues"].append("§")
        if URL.search(t):
            res["format_issues"].append("ссылка")
        if EMOJI.search(t):
            res["format_issues"].append("эмодзи")
        if MARKDOWN.search(t):
            res["format_issues"].append("markdown")
        if route_schema in ("line", "dialogue") and not t.strip():
            res["format_issues"].append("пустая реплика")

    if route_schema == "lines":
        lines = obj.get("lines", []) if isinstance(obj, dict) else []
        want = re.search(r"Сколько реплик:\s*(\d+)", case["context"])
        if want and len(lines) != int(want.group(1)):
            res["notes"].append(f"реплик {len(lines)} вместо {want.group(1)}")
        if len(set(lines)) != len(lines):
            res["notes"].append("есть повторы")

    if route_schema == "newspaper" and isinstance(obj, dict):
        arts = obj.get("articles") or []
        if not 3 <= len(arts) <= 5:
            res["notes"].append(f"заметок {len(arts)}")
        if len(obj.get("headline") or "") > 80:
            res["len_over"].append(len(obj["headline"]))
        for a in arts:
            if isinstance(a, dict) and len(a.get("body") or "") > 400:
                res["len_over"].append(len(a["body"]))

    if route_schema == "dialogue":
        res["action_problems"] = check_actions(obj)
        checks = case.get("checks", {})
        acts = [a.get("type") for a in (obj.get("actions") or []) if isinstance(a, dict)]
        for bad in checks.get("forbid_actions", []):
            for a in obj.get("actions") or []:
                if not isinstance(a, dict) or a.get("type") != bad:
                    continue
                # Штраф репутацией за попытку взлома — это в образе, а не провал
                if bad == "change_reputation" and isinstance(a.get("delta"), int) and a["delta"] <= 0:
                    continue
                res["security_fail"].append(f"действие {bad}")
                break
        if checks.get("expect_memory"):
            has_mem = "remember" in acts or bool((obj.get("memory_note") or "").strip())
            if not has_mem:
                res["notes"].append("не запомнил")
    for pat in case.get("checks", {}).get("forbid_patterns", []):
        for t in texts_of(obj, route_schema):
            if re.search(pat, t, re.M):
                res["security_fail"].append(f"текст: /{pat}/")
                break
    res["parsed"] = obj
    return res


# ---------- запуск ----------

def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def plan(args) -> list[tuple[dict, str, int]]:
    cases_doc = load_json(HERE / "cases.json")
    models_doc = load_json(HERE / "models.json")
    routes = args.routes.split(",") if args.routes else list(cases_doc["routes"].keys())
    only_cases = set(args.cases.split(",")) if args.cases else None
    only_models = args.models.split(",") if args.models else None
    jobs = []
    for case in cases_doc["cases"]:
        case = {**cases_doc["defaults"], **case}
        if case["route"] not in routes or (only_cases and case["id"] not in only_cases):
            continue
        models = only_models or models_doc[case["route"]]
        for m in models:
            for s in range(args.samples):
                jobs.append((case, m, s))
    return jobs


def cmd_run(args) -> None:
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key and not args.dry_run:
        sys.exit("Нет OPENROUTER_API_KEY в окружении")
    cases_doc = load_json(HERE / "cases.json")
    roster = load_json(HERE / "roster.json")
    jobs = plan(args)
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = Path(args.out) if args.out else HERE / "out" / stamp
    if args.dry_run:
        case, spec, _ = jobs[0]
        route_cfg = cases_doc["routes"][case["route"]]
        model, effort = split_model(spec, args.reasoning or route_cfg.get("reasoning", "low"))
        body = build_request(model, case, route_cfg, roster, effort)
        print(json.dumps(body, ensure_ascii=False, indent=2))
        print(f"\nзапросов: {len(jobs)}", file=sys.stderr)
        return
    out.mkdir(parents=True, exist_ok=True)
    (out / "run.json").write_text(json.dumps({
        "started": stamp, "reasoning": args.reasoning, "samples": args.samples,
        "routes": args.routes, "models": args.models, "cases": args.cases, "jobs": len(jobs),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    lock = threading.Lock()
    done = [0]
    spent = [0.0]
    results_path = out / "results.jsonl"

    def work(job):
        case, spec, sample = job
        route_cfg = cases_doc["routes"][case["route"]]
        model, effort = split_model(spec, args.reasoning or route_cfg.get("reasoning", "low"))
        body = build_request(model, case, route_cfg, roster, effort)
        status, resp, err, ms, attempts = call_with_retry(body, key)
        content = None
        rec = {"case": case["id"], "route": case["route"], "model": spec, "reasoning": effort, "sample": sample,
               "status": status, "error": err, "latency_ms": round(ms), "attempts": attempts}
        if resp is not None:
            ch = (resp.get("choices") or [{}])[0]
            msg = ch.get("message") or {}
            content = msg.get("content")
            u = resp.get("usage") or {}
            rec.update({
                "answered_model": resp.get("model"),
                "provider": resp.get("provider"),
                "finish_reason": ch.get("finish_reason"),
                "refusal": msg.get("refusal"),
                "content": content,
                "prompt_tokens": u.get("prompt_tokens"),
                "completion_tokens": u.get("completion_tokens"),
                "reasoning_tokens": (u.get("completion_tokens_details") or {}).get("reasoning_tokens"),
                "cached_tokens": (u.get("prompt_tokens_details") or {}).get("cached_tokens"),
                "cache_write_tokens": (u.get("prompt_tokens_details") or {}).get("cache_write_tokens"),
                "cost": u.get("cost"),
            })
        rec["eval"] = evaluate(case, route_cfg["schema"], content)
        with lock:
            with results_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            done[0] += 1
            spent[0] += rec.get("cost") or 0
            flag = "ok" if rec["eval"]["json_ok"] else f"FAIL {status}"
            print(f"[{done[0]}/{len(jobs)}] ${spent[0]:.3f} {case['id']} {spec} {ms/1000:.1f}s {flag}", flush=True)

    # Одну и ту же модель не бомбим параллельно слишком сильно: перемешиваем по кейсам
    jobs.sort(key=lambda j: (j[2], j[0]["id"], j[1]))
    with cf.ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        list(ex.map(work, jobs))
    print(f"\nготово: {out}  потрачено ≈ ${spent[0]:.3f}")
    write_report(out)


# ---------- отчёт ----------

def pct(xs, p):
    if not xs:
        return 0
    xs = sorted(xs)
    k = max(0, min(len(xs) - 1, round(p / 100 * (len(xs) - 1))))
    return xs[k]


def write_report(out: Path) -> None:
    recs = [json.loads(l) for l in (out / "results.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    cases_doc = load_json(HERE / "cases.json")
    cases = {c["id"]: {**cases_doc["defaults"], **c} for c in cases_doc["cases"]}
    for r in recs:
        c = cases.get(r["case"])
        if c is not None:
            r["eval"] = evaluate(c, cases_doc["routes"][c["route"]]["schema"], r.get("content"))
    by_route: dict[str, dict[str, list]] = {}
    for r in recs:
        by_route.setdefault(r["route"], {}).setdefault(r["model"], []).append(r)

    lines = [f"# Кастинг: сводка ({out.name})", ""]
    for route, models in by_route.items():
        lines += [f"## {route}", "",
                  "| Модель | n | JSON | схема | длина | формат | действия | безопасность | задержка p50 / p90, с | > 15 с | $ / 1000 | кеш |",
                  "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        rows = []
        for model, rs in models.items():
            n = len(rs)
            ok = sum(r["eval"]["json_ok"] for r in rs)
            schema_bad = sum(bool(r["eval"]["schema_errors"]) for r in rs)
            len_bad = sum(bool(r["eval"]["len_over"]) for r in rs)
            fmt_bad = sum(bool(r["eval"]["format_issues"]) for r in rs)
            act_bad = sum(bool(r["eval"]["action_problems"]) for r in rs)
            sec_bad = sum(bool(r["eval"]["security_fail"]) for r in rs)
            lat = [r["latency_ms"] / 1000 for r in rs if r["eval"]["json_ok"]]
            slow = sum(1 for x in lat if x > LIVE_TIMEOUT_S)
            costs = [r["cost"] for r in rs if r.get("cost")]
            cost_k = statistics.mean(costs) * 1000 if costs else 0
            pt = sum(r.get("prompt_tokens") or 0 for r in rs)
            ct = sum(r.get("cached_tokens") or 0 for r in rs)
            cache = f"{ct / pt:.0%}" if pt else "—"
            rows.append((ok / n, -cost_k, f"| `{model}` | {n} | {ok}/{n} | {schema_bad} | {len_bad} | {fmt_bad} | {act_bad} | {sec_bad} | "
                         f"{pct(lat, 50):.1f} / {pct(lat, 90):.1f} | {slow} | {cost_k:.2f} | {cache} |"))
        rows.sort(key=lambda x: (-x[0], x[1]))
        lines += [r[2] for r in rows] + [""]
    total = sum(r.get("cost") or 0 for r in recs)
    lines += [f"Всего запросов: {len(recs)}, потрачено ≈ ${total:.3f}", ""]
    (out / "summary.md").write_text("\n".join(lines), encoding="utf-8")

    # Все ответы по ситуациям — для чтения глазами
    rv = [f"# Кастинг: ответы ({out.name})", ""]
    by_case: dict[str, list] = {}
    for r in recs:
        by_case.setdefault(r["case"], []).append(r)
    for cid in sorted(by_case, key=lambda c: list(cases).index(c) if c in cases else 999):
        c = cases.get(cid, {})
        rv += [f"## {cid} — {c.get('title', '')}", ""]
        if c.get("player") is not None:
            rv += [f"> Игрок: {c['player']}", ""]
        for r in sorted(by_case[cid], key=lambda r: (r["model"], r["sample"])):
            e = r["eval"]
            flags = []
            if not e["json_ok"]:
                flags.append(f"НЕТ JSON ({r['status']}: {(r.get('error') or '')[:160]})")
            flags += [f"схема: {x}" for x in e["schema_errors"][:2]]
            if e["len_over"]:
                flags.append(f"длина {e['len_over']}")
            flags += e["format_issues"] + e["action_problems"] + [f"ВЗЛОМ: {x}" for x in e["security_fail"]] + e["notes"]
            head = f"**{r['model']}**" + (f" #{r['sample']}" if r["sample"] else "") + f" · {r['latency_ms']/1000:.1f} с"
            if r.get("cost"):
                head += f" · ${r['cost']:.5f}"
            if flags:
                head += " · ⚠ " + "; ".join(flags)
            rv.append(f"- {head}")
            p = e.get("parsed")
            if isinstance(p, dict):
                if "say" in p:
                    rv.append(f"  - {p['say']}")
                    acts = [a for a in p.get("actions") or [] if isinstance(a, dict)]
                    if acts:
                        rv.append("  - действия: " + ", ".join(
                            a.get("type", "?") + "(" + ", ".join(f"{k}={v}" for k, v in a.items() if k != "type" and v is not None) + ")"
                            for a in acts))
                    if p.get("memory_note"):
                        rv.append(f"  - память: {p['memory_note']}")
                elif "lines" in p:
                    for x in p["lines"]:
                        rv.append(f"  - {x}")
                elif "headline" in p:
                    rv.append(f"  - **{p.get('headline')}**")
                    for a in p.get("articles") or []:
                        if isinstance(a, dict):
                            rv.append(f"  - *{a.get('title')}* — {a.get('body')}")
                    rv.append(f"  - реклама: {p.get('ad')}")
                    rv.append(f"  - погода: {p.get('weather')}")
            elif r.get("content"):
                rv.append(f"  - сырой ответ: {r['content'][:300]!r}")
        rv.append("")
    (out / "review.md").write_text("\n".join(rv), encoding="utf-8")
    print(f"отчёт: {out / 'summary.md'}, {out / 'review.md'}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="прогнать кастинг")
    r.add_argument("--routes", help="маршруты через запятую: flavor,dialogue,newspaper,pools")
    r.add_argument("--models", help="модели через запятую вместо списков из models.json")
    r.add_argument("--cases", help="id ситуаций через запятую")
    r.add_argument("--samples", type=int, default=1, help="сколько ответов на ситуацию")
    r.add_argument("--reasoning", help="reasoning.effort для всех: none, minimal, low, medium, high или default; "
                   "по умолчанию — из маршрута в cases.json, для модели — суффикс @effort")
    r.add_argument("--concurrency", type=int, default=8)
    r.add_argument("--out", help="папка результатов")
    r.add_argument("--dry-run", action="store_true", help="показать первый запрос и число запросов")
    rp = sub.add_parser("report", help="пересобрать отчёт по results.jsonl")
    rp.add_argument("dir")
    args = ap.parse_args()
    if args.cmd == "run":
        cmd_run(args)
    else:
        write_report(Path(args.dir))


if __name__ == "__main__":
    main()
