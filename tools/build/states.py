"""Состояния блоков: поворот и отражение, соединения соседей — как у игры.

Поворот — по часовой, шагами по 90°, как Rotation.CLOCKWISE_90 в StructureTemplate: север → восток → юг → запад.
Отражение — Mirror.LEFT_RIGHT (север ↔ юг) и FRONT_BACK (запад ↔ восток).
Соединения — эмуляция updateShape для ступеней, заборов, стен, панелей и решёток: рендер совпадает с игрой,
а при установке игра всё равно пересчитает.
"""
from mc.registry import default

DIRS = ("north", "east", "south", "west")
STEP = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
OPP = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "down", "down": "up"}
CCW = {"north": "west", "west": "south", "south": "east", "east": "north"}


def rot_dir(d, k):
    return DIRS[(DIRS.index(d) + k) % 4] if d in DIRS else d


def mirror_dir(d, m):
    if m == "left_right" and d in ("north", "south"):
        return OPP[d]
    if m == "front_back" and d in ("east", "west"):
        return OPP[d]
    return d


def rotate(props, k=0, mirror=None):
    """Свойства после отражения и поворота на k×90° по часовой."""
    if not props:
        return props
    p = dict(props)
    if mirror:
        for key in ("facing", "horizontal_facing"):
            if key in p:
                p[key] = mirror_dir(p[key], mirror)
        sides = {d: p[d] for d in DIRS if d in p}
        for d, v in sides.items():
            p[mirror_dir(d, mirror)] = v
        if "rotation" in p:                      # таблички, флаги, головы: 16 направлений (Mirror.mirror)
            r = int(p["rotation"])
            p["rotation"] = str((16 - r) % 16 if mirror == "front_back" else (8 - r) % 16)
        if "hinge" in p:
            p["hinge"] = "right" if p["hinge"] == "left" else "left"
        if "shape" in p and ("left" in p["shape"] or "right" in p["shape"]):
            s = p["shape"]
            p["shape"] = s.replace("left", "@").replace("right", "left").replace("@", "right")
        if p.get("type") in ("left", "right"):  # двойные сундуки
            p["type"] = "right" if p["type"] == "left" else "left"
    if k % 4:
        for key in ("facing", "horizontal_facing"):
            if key in p:
                p[key] = rot_dir(p[key], k)
        if "axis" in p and k % 2:
            p["axis"] = {"x": "z", "z": "x"}.get(p["axis"], p["axis"])
        sides = {d: p[d] for d in DIRS if d in p}
        if sides:
            for d, v in sides.items():
                p[rot_dir(d, k)] = v
        if "rotation" in p:
            p["rotation"] = str((int(p["rotation"]) + 4 * k) % 16)
        if "orientation" in p:                   # jigsaw, crafter: «north_up»
            a, _, b = p["orientation"].partition("_")
            p["orientation"] = f"{rot_dir(a, k)}_{rot_dir(b, k)}"
        if "shape" in p and p["shape"].startswith(("north_south", "east_west", "ascending_", "south_", "north_")):
            p["shape"] = _rail(p["shape"], k)
    return p


def _rail(shape, k):
    if shape in ("north_south", "east_west"):
        return shape if k % 2 == 0 else ("east_west" if shape == "north_south" else "north_south")
    if shape.startswith("ascending_"):
        return "ascending_" + rot_dir(shape[10:], k)
    a, b = shape.split("_")
    a, b = rot_dir(a, k), rot_dir(b, k)
    order = {"south": 0, "north": 1}
    if a not in order:
        a, b = b, a
    return f"{a}_{b}"


def rotate_pos(x, z, k, sx, sz):
    """Позиция в рамке sx×sz после поворота k×90° по часовой: новая рамка — (sz, sx) при нечётном k."""
    for _ in range(k % 4):
        x, z = sz - 1 - z, x
        sx, sz = sz, sx
    return x, z


# --------------------------------------------------------------------------------------------- соединения
class Connect:
    """Пересчёт форм по соседям — прогон по всей сцене перед записью."""

    def __init__(self, registry=None):
        self.reg = registry or default()

    def _tag(self, name, *tags):
        b = self.reg.block(name)
        return bool(b) and any(t in b.get("tags", ()) for t in tags)

    def is_stairs(self, name):
        return self._tag(name, "minecraft:stairs")

    def is_fence(self, name):
        return self._tag(name, "minecraft:fences")

    def is_wall(self, name):
        return self._tag(name, "minecraft:walls")

    def is_pane(self, name):
        return name.endswith("_pane") or name.endswith("iron_bars") or name == "minecraft:glass_pane"

    def run(self, scene):
        get = scene.get
        todo = []
        for (x, y, z), (name, props) in scene.states():
            if self.is_stairs(name):
                todo.append(((x, y, z), name, self._stairs(get, (x, y, z), props)))
            elif self.is_fence(name) or self.is_pane(name) or self.is_wall(name):
                todo.append(((x, y, z), name, self._sides(get, (x, y, z), name, props)))
        for pos, name, props in todo:
            scene.set_state(pos, name, props)

    def _stairs(self, get, pos, p):
        """StairBlock.getStairsShape."""
        d, half = p.get("facing", "north"), p.get("half", "bottom")

        def st(dirn):
            s = get(_add(pos, STEP[dirn]))
            return s if s and self.is_stairs(s[0]) and s[1].get("half", "bottom") == half else None

        def can_take(face):
            s = get(_add(pos, STEP[face]))
            return not (s and self.is_stairs(s[0])) or s[1].get("facing") != d or s[1].get("half") != half

        out = dict(p)
        out["shape"] = "straight"
        front = st(d)
        if front:
            d2 = front[1].get("facing")
            if _axis(d2) != _axis(d) and can_take(OPP[d2]):
                out["shape"] = "outer_left" if d2 == CCW[d] else "outer_right"
                return out
        back = st(OPP[d])
        if back:
            d3 = back[1].get("facing")
            if _axis(d3) != _axis(d) and can_take(d3):
                out["shape"] = "inner_left" if d3 == CCW[d] else "inner_right"
        return out

    def _joins(self, name, other, face):
        """Соединяется ли забор, стена, панель с соседом по стороне face."""
        if other is None:
            return False
        on, op = other
        if self.is_fence(name):
            wooden = self._tag(name, "minecraft:wooden_fences")
            if self.is_fence(on):
                return wooden == self._tag(on, "minecraft:wooden_fences")
            if self._tag(on, "minecraft:fence_gates"):
                return _axis(op.get("facing", "north")) != _axis(face)
        elif self.is_pane(name) or self.is_wall(name):
            if self.is_pane(on) or self.is_wall(on):
                return True
            if self._tag(on, "minecraft:fence_gates") and self.is_wall(name):
                return _axis(op.get("facing", "north")) != _axis(face)
        return self.reg.solid(on)

    def _sides(self, get, pos, name, p):
        out = dict(p)
        conn = {}
        for d in DIRS:
            conn[d] = self._joins(name, get(_add(pos, STEP[d])), d)
        if self.is_wall(name):
            above = get(_add(pos, (0, 1, 0)))
            for d in DIRS:
                tall = conn[d] and above is not None and self.reg.solid(above[0])
                out[d] = "tall" if tall else "low" if conn[d] else "none"
            straight = (conn["north"] and conn["south"] and not conn["east"] and not conn["west"]) or \
                       (conn["east"] and conn["west"] and not conn["north"] and not conn["south"])
            post_above = above is not None and (self.is_wall(above[0]) and above[1].get("up") == "true"
                                                or above[0].endswith(("torch", "lantern", "end_rod")))
            out["up"] = "false" if straight and not post_above else "true"
        else:
            for d in DIRS:
                out[d] = "true" if conn[d] else "false"
        return out


def _add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def _axis(d):
    return "x" if d in ("east", "west") else "z"
