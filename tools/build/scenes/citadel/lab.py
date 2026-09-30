"""Лаборатория Рика в Цитадели — простая версия этапа 3 (эффектная — этап 3.5).

    python3 tools/build citadel/lab

Пишет src/main/resources/data/rikoshet/structure/citadel/lab.nbt. Мод ставит её один раз
в измерение rikoshet:citadel (docs/architecture/worlds.md#цитадель).

Точки для кода — структурные блоки в режиме DATA, в metadata «имя:сторона света»:
  rick:south         — где стоит Рик и куда смотрит;
  spawn:north        — куда попадает игрок из портала;
  portal_back:north  — центр портала обратно.
После установки мод заменяет их воздухом. Перестроил лабораторию — поставь такие же блоки,
сохрани /rickadmin citadel save, и мод найдёт точки сам.

Блоки Voxelized Furniture сверены по jar сборки сервера. Без этого мода они станут воздухом.
Перенесено из tools/citadel/build.py этапа 3 без изменений в постройке.
"""
from build import Scene

SX, SY, SZ = 27, 12, 31
VF = "voxelized_furniture:"


def build():
    s = Scene("citadel/lab", size=(SX, SY, SZ), seed=26)
    put, box = s.set, s.box

    def on_platform(x, z):
        """Площадка — прямоугольник со срезанными углами."""
        cut = 4
        return min(x, SX - 1 - x) + min(z, SZ - 1 - z) >= cut

    # ------------------------------------------------------------ площадка
    for x in range(SX):
        for z in range(SZ):
            if not on_platform(x, z):
                continue
            edge = not (on_platform(x - 1, z) and on_platform(x + 1, z) and on_platform(x, z - 1)
                        and on_platform(x, z + 1)) or x in (0, SX - 1) or z in (0, SZ - 1)
            put(x, 0, z, "gray_concrete")                          # корпус
            put(x, 1, z, "iron_block" if edge else "polished_andesite")
            if edge:
                put(x, 2, z, "light_gray_stained_glass")           # бортик: в пустоту не шагнуть

    # Сетка пола и подсветка снизу
    for x in range(2, SX - 2, 6):
        for z in range(2, SZ - 2, 6):
            if on_platform(x, z) and s.get((x, 1, z)) == ("minecraft:polished_andesite", {}):
                put(x, 1, z, "sea_lantern")

    # ------------------------------------------------------------ лаборатория
    X0, X1, Z0, Z1 = 5, 21, 2, 13       # стены
    FLOOR, TOP = 1, 8
    box(X0 + 1, FLOOR, Z0 + 1, X1 - 1, FLOOR, Z1 - 1, "smooth_stone")
    for y in range(FLOOR + 1, TOP):
        for x in range(X0, X1 + 1):
            for z in (Z0, Z1):
                put(x, y, z, "white_concrete")
        for z in range(Z0, Z1 + 1):
            for x in (X0, X1):
                put(x, y, z, "white_concrete")
    # Колонны по углам и через четыре блока
    for x in range(X0, X1 + 1, 4):
        for z in (Z0, Z1):
            box(x, FLOOR + 1, z, x, TOP - 1, z, "light_gray_concrete")
    for x in (X0, X1):
        box(x, FLOOR + 1, Z0, x, TOP - 1, Z0, "light_gray_concrete")
        box(x, FLOOR + 1, Z1, x, TOP - 1, Z1, "light_gray_concrete")
    # Окна на боковых и задней стенах
    for z in range(Z0 + 2, Z1 - 1, 3):
        for x in (X0, X1):
            box(x, 4, z, x, 5, z + 1, "light_blue_stained_glass")
    for x in range(X0 + 2, X1 - 1, 4):
        box(x, 4, Z0, x + 1, 5, Z0, "light_blue_stained_glass")
    # Крыша с лампами
    box(X0, TOP, Z0, X1, TOP, Z1, "white_concrete")
    for x in range(X0 + 2, X1 - 1, 3):
        for z in range(Z0 + 2, Z1 - 1, 3):
            put(x, TOP, z, "sea_lantern")
    # Вход: проём 3×3 в южной стене и зелёная арка
    box(12, FLOOR + 1, Z1, 14, FLOOR + 3, Z1, "air")
    box(11, FLOOR + 1, Z1, 11, FLOOR + 4, Z1, "lime_concrete")
    box(15, FLOOR + 1, Z1, 15, FLOOR + 4, Z1, "lime_concrete")
    box(11, FLOOR + 4, Z1, 15, FLOOR + 4, Z1, "lime_concrete")

    # Вдоль задней стены — рабочее место Рика
    Y = FLOOR + 1
    row = Z0 + 1
    put(6, Y, row, VF + "fridge", facing="south")
    put(7, Y, row, VF + "dark_oak_drawer", facing="south")
    put(7, Y + 1, row, VF + "tv", facing="south")
    put(8, Y, row, "crafting_table")
    put(9, Y, row, "brewing_stand")
    put(10, Y, row, "water_cauldron", level=3)
    put(11, Y, row, "smithing_table")
    put(12, Y, row, VF + "dark_oak_drawer", facing="south")
    put(12, Y + 1, row, VF + "laptop", facing="south")
    put(13, Y, row, VF + "dark_oak_drawer", facing="south")
    put(13, Y + 1, row, VF + "microwave", facing="south", state=0)
    put(14, Y, row, VF + "dark_oak_drawer", facing="south")
    put(14, Y + 1, row, VF + "emerald_geode", facing="south")
    put(15, Y, row, "bookshelf")
    put(16, Y, row, "bookshelf")
    put(16, Y + 1, row, VF + "redstone_geode", facing="south")
    # Западная стена — ящики с «образцами»
    for z in (5, 6, 8):
        put(6, Y, z, VF + "wooden_crate", facing="east", state=z % 3)
    put(6, Y + 1, 5, VF + "wooden_crate", facing="east", state=4)
    # Восточная стена — корабль Рика, пока заготовка
    box(17, Y, 6, 19, Y, 10, "gray_concrete")
    box(17, Y + 1, 7, 19, Y + 1, 10, "light_gray_concrete")
    box(17, Y + 1, 6, 19, Y + 1, 6, "light_blue_stained_glass")
    box(17, Y + 2, 7, 19, Y + 2, 8, "light_blue_stained_glass")
    put(18, Y + 2, 9, "red_concrete")
    put(17, Y, 11, "iron_trapdoor", facing="south", half="bottom", open="false")
    put(19, Y, 11, "iron_trapdoor", facing="south", half="bottom", open="false")

    # Рик стоит посреди лаборатории и смотрит на вход
    s.marker("rick", (13, Y, 6), "south")

    # ------------------------------------------------------------ площадь
    # Дорожка от входа к точке прибытия и порталу
    box(12, FLOOR, Z1 + 1, 14, FLOOR, 29, "light_blue_concrete")
    s.marker("spawn", (13, Y, 22), "north")
    # Портал обратно: светящаяся площадка, частицы рисует мод
    box(12, FLOOR, 26, 14, FLOOR, 28, "lime_stained_glass")
    box(12, 0, 26, 14, 0, 28, "sea_lantern")
    s.marker("portal_back", (13, Y, 27), "north")
    for x in (11, 15):
        box(x, Y, 26, x, Y + 2, 26, "iron_block")
        put(x, Y + 3, 26, "end_rod", facing="up")

    # Место торговца (этап 5)
    box(2, FLOOR, 17, 7, FLOOR, 22, "purple_concrete")
    put(3, Y, 18, VF + "wooden_crate", facing="east", state=1)
    put(3, Y, 19, VF + "wooden_crate", facing="east", state=2)
    put(3, Y + 1, 18, VF + "wooden_crate", facing="east", state=3)
    # Место аркад (этап 6): три автомата
    box(19, FLOOR, 17, 24, FLOOR, 22, "black_concrete")
    for z in (18, 20, 22):
        if s.get((23, 1, z)) is not None:
            put(23, Y, z, "black_concrete")
            put(23, Y + 1, z, "light_blue_stained_glass")
            put(23, Y + 2, z, "magenta_concrete")

    # Фонари-стойки по углам площади
    for x, z in ((5, 16), (21, 16), (5, 27), (21, 27)):
        box(x, Y, z, x, Y + 1, z, "andesite_wall")
        put(x, Y + 2, z, "sea_lantern")
    return s
