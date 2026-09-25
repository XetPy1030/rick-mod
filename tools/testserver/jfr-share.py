#!/usr/bin/env python3
"""Сколько времени главного потока сервера ушло на код мода.

Вход — `jfr print --json --events jdk.ExecutionSample` на stdin, аргумент — длина записи в секундах.
При выборке раз в 1 мс одна выборка примерно равна одной миллисекунде работы потока.
"""
import collections
import json
import sys

MOD = "ru.xetpy.rikoshet"
DEV = "ru.xetpy.rikoshet.command.DevCommand"

secs = float(sys.argv[1]) if len(sys.argv) > 1 else 60
events = json.load(sys.stdin)["recording"]["events"]
total = mod = dev = 0
top = collections.Counter()
for e in events:
    v = e["values"]
    if v["sampledThread"]["javaName"] != "Server thread":
        continue
    total += 1
    types = [f["method"]["type"]["name"].replace("/", ".") for f in v["stackTrace"]["frames"]]
    ours = [t for t in types if t.startswith(MOD)]
    if not ours:
        continue
    mod += 1
    if any(t.startswith(DEV) for t in types):
        dev += 1
    top[ours[0].split("$$")[0]] += 1

ticks = secs * 20
print(f"главный поток: {total} выборок (~{total / ticks:.2f} мс на тик)")
print(f"мод: {mod} выборок ({100 * mod / max(total, 1):.1f}%), из них /rickdev: {dev}")
print(f"мод без /rickdev: ~{(mod - dev) / ticks:.4f} мс на тик из 50")
for name, n in top.most_common(8):
    print(f"  {n:5d}  {name}")
