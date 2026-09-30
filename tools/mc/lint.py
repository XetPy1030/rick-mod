"""Линтер построек: что не видно на картинке (docs/architecture/visual-pipeline.md#линтер).

Уровни: «ошибка» — в игре сломается или нарушен бюджет; «предупреждение» — выглядит или ходится плохо;
«сведения» — цифры для разбора. Каждая находка — с координатами внутри постройки.
"""
from collections import deque

import numpy as np

from .scene import block_light, _solid_mask

ERROR, WARN, INFO = "ошибка", "предупреждение", "сведения"

BUDGET = {"blocks": 300_000, "block_entities": 300}
CITADEL_MARKERS = ("rick", "spawn", "portal_back")

# Сквозь это можно пройти: трава, цветы, факелы, кнопки, таблички, рельсы, двери (открываются)
PASS_TAGS = {"minecraft:replaceable", "minecraft:flowers", "minecraft:saplings", "minecraft:rails", "minecraft:all_signs",
             "minecraft:buttons", "minecraft:pressure_plates", "minecraft:banners", "minecraft:doors",
             "minecraft:wool_carpets", "minecraft:candles", "minecraft:crops"}
PASS_WORDS = ("torch", "carpet", "button", "pressure_plate", "_sign", "banner")
# Точки для кода (структурные блоки) мод после установки заменяет воздухом
PASS_IDS = {"minecraft:light", "minecraft:structure_block", "minecraft:structure_void", "minecraft:redstone_wire",
            "minecraft:tripwire", "minecraft:lever"}
# Стоят только на опоре снизу
FLOOR_TAGS = {"minecraft:wool_carpets", "minecraft:pressure_plates", "minecraft:rails", "minecraft:flowers",
              "minecraft:saplings", "minecraft:candles", "minecraft:crops", "minecraft:standing_signs",
              "minecraft:banners"}
FLOOR_IDS = {"minecraft:torch", "minecraft:soul_torch", "minecraft:redstone_torch", "minecraft:copper_torch",
             "minecraft:redstone_wire", "minecraft:repeater", "minecraft:comparator", "minecraft:snow",
             "minecraft:moss_carpet", "minecraft:pale_moss_carpet"}
# Висят на стене — опора сзади, противоположно facing
WALL_TAGS = {"minecraft:wall_signs"}
WALL_IDS = {"minecraft:wall_torch", "minecraft:soul_wall_torch", "minecraft:redstone_wall_torch", "minecraft:copper_wall_torch",
            "minecraft:ladder", "minecraft:tripwire_hook"}
OPP = {"north": (0, 0, 1), "south": (0, 0, -1), "west": (1, 0, 0), "east": (-1, 0, 0)}


class Finding:
    __slots__ = ("level", "text", "pos")

    def __init__(self, level, text, pos=None):
        self.level, self.text, self.pos = level, text, pos

    def __str__(self):
        where = f" {tuple(int(v) for v in self.pos)}" if self.pos is not None else ""
        return f"[{self.level}]{where} {self.text}"


class Linter:
    def __init__(self, registry, markers=CITADEL_MARKERS, wall_limit=6):
        self.reg = registry
        self.required = markers
        self.wall_limit = wall_limit

    # ---------------------------------------------------------------- свойства блоков
    def _passable(self, name):
        if name == "minecraft:air" or name in PASS_IDS:
            return True
        b = self.reg.block(name)
        if b is None:
            return True                       # чего нет в игре, станет воздухом
        if set(b.get("tags", ())) & PASS_TAGS:
            return True
        path = name.split(":", 1)[1]
        return any(w in path for w in PASS_WORDS) and not b.get("full")

    def _tagged(self, name, tags, ids):
        return name in ids or bool(set((self.reg.block(name) or {}).get("tags", ())) & tags)

    # ---------------------------------------------------------------- проверка
    def run(self, vol):
        out = []
        reg = self.reg
        sx, sy, sz = vol.size
        names = [p[0] for p in vol.palette]
        known = np.array([reg.block(n) is not None for n in names] + [True])
        pas = np.array([self._passable(n) for n in names] + [True])      # последний — «не задано»
        idx = np.where(vol.idx >= 0, vol.idx, len(names))
        passable = pas[idx]
        filled = ~passable

        # 1. Палитра по каталогу
        for i, (name, props) in enumerate(vol.palette):
            for e in reg.check(name, props):
                where = np.argwhere(vol.idx == i)
                out.append(Finding(ERROR, f"{e} — в игре станет воздухом или состоянием по умолчанию ({len(where)} шт.)",
                                   where[0] if len(where) else None))

        # 2. Опоры: падающие, напольные, настенные, висячие
        def solid_at(x, y, z):
            return 0 <= x < sx and 0 <= y < sy and 0 <= z < sz and filled[x, y, z]

        for i, (name, props) in enumerate(vol.palette):
            if not known[i]:
                continue
            b = reg.block(name)
            full = reg.props(name, props)
            kind = None
            if b.get("falling"):
                kind = "falling"
            elif self._tagged(name, FLOOR_TAGS, FLOOR_IDS) or (name.endswith("lantern") and full.get("hanging") == "false"):
                kind = "floor"
            elif name.endswith("lantern") and full.get("hanging") == "true":
                kind = "ceiling"
            elif self._tagged(name, WALL_TAGS, WALL_IDS) or ("face" in full and full.get("face") == "wall"):
                kind = "wall"
            elif "face" in full and full["face"] in ("floor", "ceiling"):
                kind = "floor" if full["face"] == "floor" else "ceiling"
            if kind is None:
                continue
            bad = []
            for x, y, z in np.argwhere(vol.idx == i):
                if kind in ("falling", "floor"):
                    ok = solid_at(x, y - 1, z)
                elif kind == "ceiling":
                    ok = solid_at(x, y + 1, z)
                else:
                    dx, dy, dz = OPP.get(full.get("facing", "north"), (0, 0, 1))
                    ok = solid_at(x + dx, y + dy, z + dz)
                if not ok:
                    bad.append((x, y, z))
            if bad:
                what = {"falling": "упадёт", "floor": "отвалится: нет опоры снизу",
                        "ceiling": "отвалится: нет опоры сверху", "wall": "отвалится: нет стены сзади"}[kind]
                out.append(Finding(ERROR, f"{name} {what} ({len(bad)} шт.)", bad[0]))

        # 3. Точки
        marks = vol.markers()
        byname = {}
        for m in marks:
            byname.setdefault(m.name, []).append(m)
        for req in self.required:
            if req not in byname:
                out.append(Finding(ERROR, f"нет точки {req}"))
        for name, ms in byname.items():
            if len(ms) > 1:
                out.append(Finding(ERROR, f"точка {name} стоит {len(ms)} раза", ms[1].pos))
        for m in marks:
            x, y, z = m.pos
            if not solid_at(x, y - 1, z):
                out.append(Finding(ERROR, f"точка {m.name}: под ней нет опоры", m.pos))
            if y + 1 < sy and not passable[x, y + 1, z]:
                out.append(Finding(ERROR, f"точка {m.name}: над ней нет места для головы", m.pos))

        # 4. Проходимость от точки прибытия
        walk = self._walkable(passable, filled)
        if "spawn" in byname:
            reach = self._reach(walk, byname["spawn"][0].pos)
            out.append(Finding(INFO, f"от spawn можно дойти до {int(reach.sum())} клеток пола из {int(walk.sum())}"))
            for m in marks:
                if m.name != "spawn" and not self._near(reach, m.pos):
                    out.append(Finding(ERROR, f"от spawn не дойти до точки {m.name}", m.pos))
            low = int((reach & ~np.pad(passable, ((0, 0), (0, 2), (0, 0)), constant_values=True)[:, 2:, :]).sum())
            if low:
                out.append(Finding(INFO, f"клеток пути с потолком ровно в 2 блока: {low} — проходы и двери норма, комнаты — тесно"))

        # 5. Свет: пол, где блочного света меньше 7
        solid = _solid_mask(vol, reg)
        L = block_light(vol, reg, solid)[1:-1, 1:-1, 1:-1]
        if walk.any():
            dark = walk & (L < 7)
            share = dark.sum() / walk.sum()
            lvl = WARN if share > 0.25 else INFO
            out.append(Finding(lvl, f"пол без света (блочный свет < 7): {share:.0%} — в Цитадели без ламп темно",
                               np.argwhere(dark)[0] if dark.any() else None))

        # 6. Скучные стены: квадрат одного блока больше wall_limit на вертикальной грани
        out.extend(self._flat_walls(vol, passable))

        # 7. Модовые блоки в оболочке, палитра, бюджеты
        counts = vol.counts()
        total = sum(counts.values())
        full_blocks = {n: c for n, c in counts.items() if (reg.block(n) or {}).get("full")}
        modded = sum(c for n, c in full_blocks.items() if not n.startswith("minecraft:"))
        if full_blocks and modded / sum(full_blocks.values()) > 0.2:
            out.append(Finding(WARN, f"полных модовых блоков {modded / sum(full_blocks.values()):.0%}: уберут мод — останутся дыры; оболочку лучше из ванилы"))
        if len(counts) > 40:
            out.append(Finding(WARN, f"в постройке {len(counts)} разных блоков — пестрит"))
        be = sum(c for n, c in counts.items() if (reg.block(n) or {}).get("block_entity"))
        if total > BUDGET["blocks"]:
            out.append(Finding(ERROR, f"блоков {total} > бюджета {BUDGET['blocks']}"))
        if be > BUDGET["block_entities"]:
            out.append(Finding(ERROR, f"блок-сущностей {be} > бюджета {BUDGET['block_entities']}"))
        out.append(Finding(INFO, f"блоков {total}, разных {len(counts)}, блок-сущностей {be}"))
        order = {ERROR: 0, WARN: 1, INFO: 2}
        return sorted(out, key=lambda f: order[f.level])

    # ---------------------------------------------------------------- ходьба
    @staticmethod
    def _walkable(passable, filled):
        """Клетка, где стоят ноги: сама и над ней проходимы, под ней — опора."""
        sx, sy, sz = passable.shape
        w = np.zeros_like(passable)
        w[:, 1:sy - 1, :] = passable[:, 1:sy - 1, :] & passable[:, 2:, :] & filled[:, :sy - 2, :]
        w[:, sy - 1, :] = passable[:, sy - 1, :] & filled[:, sy - 2, :]
        return w

    @staticmethod
    def _reach(walk, start):
        sx, sy, sz = walk.shape
        seen = np.zeros_like(walk)
        x, y, z = start
        if not walk[x, y, z]:
            return seen
        q = deque([(x, y, z)])
        seen[x, y, z] = True
        while q:
            x, y, z = q.popleft()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if not (0 <= nx < sx and 0 <= nz < sz):
                    continue
                for dy in (0, 1, -1, -2, -3):       # шаг, прыжок на блок, спуск до трёх
                    ny = y + dy
                    if 0 <= ny < sy and walk[nx, ny, nz] and not seen[nx, ny, nz]:
                        seen[nx, ny, nz] = True
                        q.append((nx, ny, nz))
                        break
        return seen

    @staticmethod
    def _near(reach, pos):
        x, y, z = pos
        sx, sy, sz = reach.shape
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                nx, nz = x + dx, z + dz
                if 0 <= nx < sx and 0 <= nz < sz and reach[nx, y, nz]:
                    return True
        return False

    # ---------------------------------------------------------------- скучные стены
    def _flat_walls(self, vol, passable):
        out = []
        limit = self.wall_limit
        idx = vol.idx
        sx, sy, sz = vol.size
        seen = set()
        for axis in (0, 2):
            for sign in (1, -1):
                for c in range(vol.size[axis]):
                    nc = c + sign
                    sl = idx[c, :, :] if axis == 0 else idx[:, :, c].T      # (y, другая ось)
                    if 0 <= nc < vol.size[axis]:
                        open_ = passable[nc, :, :] if axis == 0 else passable[:, :, nc].T
                    else:
                        open_ = np.ones_like(sl, dtype=bool)
                    for i in np.unique(sl[(sl >= 0) & open_]):
                        name = vol.palette[i][0]
                        if name == "minecraft:air" or self._passable(name):
                            continue
                        mask = (sl == i) & open_
                        size, (py, pq) = _max_square(mask)
                        if size > limit:
                            key = (name, axis, c)
                            if key in seen:
                                continue
                            seen.add(key)
                            pos = (c, py, pq) if axis == 0 else (pq, py, c)
                            side = {(0, 1): "восток", (0, -1): "запад", (2, 1): "юг", (2, -1): "север"}[(axis, sign)]
                            out.append(Finding(WARN, f"скучная стена: {name} сплошным квадратом {size}×{size}, смотрит на {side}", pos))
        return out


def _max_square(mask):
    """Самый большой квадрат из True: (сторона, (строка, столбец) верхнего угла)."""
    h, w = mask.shape
    dp = np.zeros((h + 1, w + 1), dtype=np.int32)
    best, at = 0, (0, 0)
    for r in range(h):
        row = mask[r]
        for c in range(w):
            if row[c]:
                v = 1 + min(dp[r, c + 1], dp[r + 1, c], dp[r, c])
                dp[r + 1, c + 1] = v
                if v > best:
                    best, at = v, (r - v + 1, c - v + 1)
    return best, at
