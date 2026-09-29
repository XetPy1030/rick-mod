#!/usr/bin/env python3
"""Скин Рика Санчеза, 64×64, classic (руки 4 px). Простая версия этапа 3, детальная — этап 3.5.

    python3 tools/skins/rick.py

Узнаваемое: седые с голубым отливом волосы торчком, сплошная бровь, слюна в углу рта,
белый лабораторный халат до колен поверх голубой рубашки, коричневые брюки.
"""
from skinlib import (BODY, BODY2, HAT, HEAD, LARM, LARM2, LLEG, LLEG2, RARM, RARM2, RLEG, RLEG2,
                     Skin, hexc, mirror, shade)

SKIN = hexc("f2d3b6")
SKIN_D = shade(SKIN, -0.12)
SKIN_DD = shade(SKIN, -0.25)
HAIR = hexc("b9dce6")
HAIR_D = shade(HAIR, -0.18)
HAIR_L = shade(HAIR, 0.25)
BROW = hexc("8fb3bf")
EYE_W = hexc("fbfbfb")
PUPIL = hexc("1a1a1a")
MOUTH = hexc("5a2e2a")
DROOL = hexc("a7d84a")

COAT = hexc("eeeeee")
COAT_D = shade(COAT, -0.12)
COAT_DD = shade(COAT, -0.25)
SHIRT = hexc("9ccbe6")
SHIRT_D = shade(SHIRT, -0.15)
PANTS = hexc("6e4b2c")
PANTS_D = shade(PANTS, -0.18)
SHOE = hexc("3a2a1e")
SOLE = hexc("22180f")

L = {
    "H": HAIR, "h": HAIR_D, "l": HAIR_L, "B": BROW,
    "S": SKIN, "d": SKIN_D, "x": SKIN_DD, "W": EYE_W, "P": PUPIL, "M": MOUTH, "g": DROOL,
    "C": COAT, "c": COAT_D, "k": COAT_DD, "s": SHIRT, "t": SHIRT_D,
    "p": PANTS, "q": PANTS_D, "o": SHOE, "u": SOLE, ".": None, " ": (0, 0, 0, 0),
}

skin = Skin()
paint = skin.paint

# ---------------------------------------------------------------- голова
paint(HEAD, "front", [
    "HHHHHHHH",
    "HSSSSSSH",   # высокий лоб
    "SBBBBBBS",   # сплошная бровь
    "SWWSSWWS",
    "SWPdSPWS",   # зрачки врозь: Рик смотрит мимо тебя
    "SSSddSSS",   # нос
    "SdMMMMdS",   # оскал
    "SSSSSgxS",   # слюна в углу рта
], L)
side = [
    "HHHHHHHH",
    "HHHHHHHH",
    "HHHHHSSS",
    "HHHSSSSS",
    "HhSxdSSS",   # ухо
    "SSSxdSSS",
    "SSSSSSSd",
    "SdSSSSSS",
]
paint(HEAD, "right", side, L)
paint(HEAD, "left", mirror(side), L)
paint(HEAD, "back", ["HHHHHHHH"] * 5 + ["hHHHHHHh", "SSSSSSSS", "SdSSSSdS"], L)
paint(HEAD, "top", ["HHHHHHHH", "HHlHHHHH", "HHHHHlHH", "HHHHHHHH", "HlHHHHHH", "HHHHHHlH", "HHHHHHHH", "hHHHHHHh"], L)
paint(HEAD, "bottom", ["SSSSSSSS"] * 8, L)

# Волосы торчком — во внешнем слое головы: рваный край по бокам, сверху и сзади
paint(HAT, "top", ["H H H HH", " HHlHH H", "HHHHHHH ", " HlHHHHH", "HHHHHlH ", " HHHHHHH", "HH HlHH ", "H H HH H"], L)
paint(HAT, "front", ["H lH H H", "        ", "        ", "        ", "        ", "        ", "        ", "        "], L)
hat_side = [
    "HlHHHH H",
    "HHHHH H ",
    "HHH H   ",
    "hH H    ",
    "H       ",
    "        ",
    "        ",
    "        ",
]
paint(HAT, "right", hat_side, L)
paint(HAT, "left", mirror(hat_side), L)
paint(HAT, "back", ["HHlHHHHH", "HHHHHlHH", "HlHHHHHH", "H HH HHh", " H  H H ", "        ", "        ", "        "], L)

# ---------------------------------------------------------------- торс: халат нараспашку, рубашка
paint(BODY, "front", [
    "CkssssCC",   # воротник
    "CcsssscC",   # лацканы
    "CCsssCCC",
    "CCssscCC",
    "CCssscCC",
    "CCsstcCC",
    "CCsstcCC",
    "CCsstcCC",
    "CCsstcCC",
    "CCssscCC",
    "CCppppCC",   # брюки видны между полами
    "CCppppCC",
], L)
coat_side = ["CCCC", "CCCc", "CCCc", "CCCc", "CCCc", "CCCc", "CCcc", "CCcc", "CCcc", "CCcc", "Cccc", "cccc"]
paint(BODY, "right", coat_side, L)
paint(BODY, "left", mirror(coat_side), L)
paint(BODY, "back", ["CCCCCCCC", "CCCCCCCC", "CCCcCCCC", "CCCcCCCC", "CCCcCCCC", "CCCcCCCC",
                     "CCCcCCCC", "CCCcCCCC", "CCCcCCCC", "CCcccCCC", "CccCccCC", "cccccccc"], L)
paint(BODY, "top", ["CCCssCCC", "CCssssCC", "CCCCCCCC", "CCCCCCCC"], L)
paint(BODY, "bottom", ["pppppppp"] * 4, L)

# ---------------------------------------------------------------- руки: рукава халата, кисти
arm = ["CCCC"] * 3 + ["CCCc"] * 5 + ["cCCc", "kkkk", "SSSS", "SdSd"]
for part in (RARM, LARM):
    for face in ("front", "back", "right", "left"):
        paint(part, face, arm, L)
    paint(part, "top", ["CCCC"] * 4, L)
    paint(part, "bottom", ["SSSS"] * 4, L)

# ---------------------------------------------------------------- ноги: брюки, ботинки
leg = ["pppp"] * 3 + ["pppq"] * 6 + ["pqqq", "oooo", "uuuu"]
for part in (RLEG, LLEG):
    for face in ("front", "back", "right", "left"):
        paint(part, face, leg, L)
    paint(part, "top", ["pppp"] * 4, L)
    paint(part, "bottom", ["uuuu"] * 4, L)

# Полы халата до колен — во внешнем слое ног: спереди разрез, сзади сплошь
paint(RLEG2, "front", ["CC  ", "Cc  ", "cc  ", "kk  "] + ["    "] * 8, L)
paint(LLEG2, "front", ["  CC", "  cC", "  cc", "  kk"] + ["    "] * 8, L)
for part in (RLEG2, LLEG2):
    paint(part, "back", ["CCCC", "CcCC", "cccc", "kkkk"] + ["    "] * 8, L)
    for face in ("right", "left"):
        paint(part, face, ["CCCC", "CCCc", "cccc", "kkkk"] + ["    "] * 8, L)
# Внутренние бока ног под халатом не видны — оставляем прозрачными
paint(RLEG2, "left", ["    "] * 12, L)
paint(LLEG2, "right", ["    "] * 12, L)

# Внешние слои торса и рук не используем
_ = (BODY2, RARM2, LARM2)

print(skin.save("rick"))
