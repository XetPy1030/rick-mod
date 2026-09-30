"""Демо генератора: слева купол со сглаживанием (ступени и плиты), справа — тот же без него.

    python3 tools/build demo/showcase

Пишет в run/visual/build/ — в мод не идёт. Проверяет формы, кисти, сглаживание, соединения заборов
и стен, префаб-функцию по кругу, проём-арку, автоподсветку.
"""
from build import Scene, brush, shape
from mc import OUT


def lamp_post(f):
    """Фонарный столб «лицом на юг» от своего основания: стена, забор, фонарь, лавочка перед ним."""
    f.box(0, 0, 0, 0, 1, 0, "polished_blackstone_wall")
    f.set(0, 2, 0, "dark_oak_fence")
    f.set(0, 3, 0, "lantern", hanging="false")
    f.set(0, 0, 1, "dark_oak_stairs", facing="north")
    f.set(-1, 0, 1, "dark_oak_stairs", facing="north")
    f.set(1, 0, 1, "dark_oak_stairs", facing="north")


def build():
    s = Scene("demo/showcase", size=(96, 26, 48), seed=26, out=OUT / "build" / "showcase.nbt")
    hull = brush.gradient(["polished_andesite", "stone_bricks", "quartz_block", "white_concrete"], axis="y",
                          noise=0.12)
    trim = brush.edges(hull, "polished_blackstone_bricks")          # на гладком куполе рёбер нет — только у проёма
    for cx, smooth in ((24, True), (72, False)):
        c = (cx, 1, 24)
        # Платформа: гладкий край плитами, узор пола
        floor = brush.pattern([["polished_andesite", "andesite"], ["andesite", "polished_andesite"]])
        s.fill(shape.cylinder((cx, 0, 24), r=21.5, h=1), brush.by_normal(floor, "polished_blackstone_bricks"),
               smooth=smooth)
        # Купол-оболочка изнутри, со светлым градиентом к верху и тёмными рёбрами
        dome = shape.sphere(c, 13).cut(y0=1)
        s.fill(dome, trim, smooth=smooth, hollow=1)
        # Проём-арка на юг
        s.fill(shape.arch((cx, 1, 24 + 12), width=5, height=6, depth=4, axis="z"), "air")
        # Кольцо-мостик и труба к нему
        s.fill(shape.torus((cx, 8.5, 24), 17.5, 1.2), brush.solid("cut_copper"), smooth=smooth)
        s.fill(shape.pipe([(cx + 9, 11, 24), (cx + 14, 9, 24), (cx + 17, 8, 24)], 0.9), brush.glass_tube("lime"))
        # Фонари по кругу: префаб-функция, повёрнутая лицом к центру
        s.radial(6, lambda fr: fr.prefab(lamp_post, at=(0, 1, 19), facing="north"), center=(cx, 24), start=30)
    s.marker("spawn", (24, 1, 44), "north")
    s.lights(target=9, limit=24)
    return s
