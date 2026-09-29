"""Общее для генераторов скинов NPC: раскладка 64×64 (1.8+), цвета, рисование по строкам.

По образцу генератора Джерри из сборки сервера (~/common/projects/mc-fabric-26.2/skins/jerry-prime.py).
Скины кладутся в ресурспак мода: src/main/resources/assets/rikoshet/textures/entity/npc/<id>.png
(docs/architecture/content-delivery.md#пайплайн-ассетов).
"""
import os

from PIL import Image

NONE = (0, 0, 0, 0)

HEAD = dict(top=(8, 0), bottom=(16, 0), right=(0, 8), front=(8, 8), left=(16, 8), back=(24, 8))
HAT = dict(top=(40, 0), bottom=(48, 0), right=(32, 8), front=(40, 8), left=(48, 8), back=(56, 8))
BODY = dict(top=(20, 16), bottom=(28, 16), right=(16, 20), front=(20, 20), left=(28, 20), back=(32, 20))
BODY2 = dict(top=(20, 32), bottom=(28, 32), right=(16, 36), front=(20, 36), left=(28, 36), back=(32, 36))
RARM = dict(top=(44, 16), bottom=(48, 16), right=(40, 20), front=(44, 20), left=(48, 20), back=(52, 20))
RARM2 = dict(top=(44, 32), bottom=(48, 32), right=(40, 36), front=(44, 36), left=(48, 36), back=(52, 36))
LARM = dict(top=(36, 48), bottom=(40, 48), right=(32, 52), front=(36, 52), left=(40, 52), back=(44, 52))
LARM2 = dict(top=(52, 48), bottom=(56, 48), right=(48, 52), front=(52, 52), left=(56, 52), back=(60, 52))
RLEG = dict(top=(4, 16), bottom=(8, 16), right=(0, 20), front=(4, 20), left=(8, 20), back=(12, 20))
RLEG2 = dict(top=(4, 32), bottom=(8, 32), right=(0, 36), front=(4, 36), left=(8, 36), back=(12, 36))
LLEG = dict(top=(20, 48), bottom=(24, 48), right=(16, 52), front=(20, 52), left=(24, 52), back=(28, 52))
LLEG2 = dict(top=(4, 48), bottom=(8, 48), right=(0, 52), front=(4, 52), left=(8, 52), back=(12, 52))

# Размер грани: голова 8×8, торс 8×12 спереди и 4×12 сбоку, руки и ноги 4×12
SIZES = {
    "head": dict(top=(8, 8), bottom=(8, 8), right=(8, 8), front=(8, 8), left=(8, 8), back=(8, 8)),
    "body": dict(top=(8, 4), bottom=(8, 4), right=(4, 12), front=(8, 12), left=(4, 12), back=(8, 12)),
    "limb": dict(top=(4, 4), bottom=(4, 4), right=(4, 12), front=(4, 12), left=(4, 12), back=(4, 12)),
}


def hexc(s, a=255):
    s = s.lstrip("#")
    return int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a


def mix(c1, c2, t):
    return tuple(round(a + (b - a) * t) for a, b in zip(c1, c2))


def shade(c, t):
    """t < 0 — темнее, t > 0 — светлее."""
    if t < 0:
        return mix(c, (0, 0, 0, c[3]), -t)
    return mix(c, (255, 255, 255, c[3]), t)


def mirror(rows):
    return ["".join(reversed(r)) for r in rows]


class Skin:
    def __init__(self):
        self.img = Image.new("RGBA", (64, 64), NONE)
        self.px = self.img.load()

    def paint(self, part, face, rows, legend):
        """rows — строки одинаковой длины; legend: символ → цвет, None — не трогать."""
        ox, oy = part[face]
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                c = legend.get(ch, NONE)
                if c is not None:
                    self.px[ox + x, oy + y] = c

    def fill(self, part, kind, color, faces=("top", "bottom", "right", "front", "left", "back")):
        for face in faces:
            w, h = SIZES[kind][face]
            ox, oy = part[face]
            for y in range(h):
                for x in range(w):
                    self.px[ox + x, oy + y] = color

    def save(self, name):
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        out = os.path.join(root, "src/main/resources/assets/rikoshet/textures/entity/npc", name + ".png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        self.img.save(out)
        return out
