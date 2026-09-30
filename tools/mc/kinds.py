"""Что это за блок в постройке: природа или рукотворное — порт BlockKinds.classify из мода
(src/main/java/ru/xetpy/rikoshet/builds/BlockKinds.java, docs/design/builds.md#что-рукотворное).

Правило то же, что у мода, чтобы «постройка» в мире и в разборе образцов значила одно и то же.
Меняешь одно — меняй и другое.
"""
from functools import lru_cache

from .registry import default, split_id

NATURAL, STRUCTURE, FURNITURE, STORAGE, FARM, REDSTONE, WORKSHOP, MAGIC, LIGHT, BED, RAIL, PORTAL = (
    "natural", "structure", "furniture", "storage", "farm", "redstone", "workshop", "magic", "light", "bed", "rail",
    "portal")

FURNITURE_MODS = {"voxelized_furniture", "mcwdoors"}
NATURAL_EXCEPTIONS = {"minecraft:smooth_basalt", "voxelized_furniture:bamboo_cluster"}
REDSTONE_IDS = {"redstone_wire", "repeater", "comparator", "piston", "sticky_piston", "observer", "dispenser", "dropper",
                "lever", "redstone_torch", "redstone_wall_torch", "redstone_lamp", "daylight_detector", "target",
                "tripwire_hook", "note_block", "crafter", "redstone_block"}
MAGIC_IDS = {"enchanting_table", "bookshelf", "chiseled_bookshelf", "beacon", "lectern", "brewing_stand", "conduit",
             "respawn_anchor"}
WORKSHOP_IDS = {"crafting_table", "furnace", "blast_furnace", "smoker", "anvil", "chipped_anvil", "damaged_anvil",
                "smithing_table", "stonecutter", "loom", "grindstone", "cartography_table", "fletching_table",
                "cauldron", "water_cauldron", "lava_cauldron", "powder_snow_cauldron", "composter"}
FD_WORKSHOP = ("stove", "cooking_pot", "skillet", "cutting_board", "cabinet")
STORAGE_WORDS = ("chest", "barrel", "shulker_box", "hopper")
LIGHT_WORDS = ("torch", "lantern", "_lamp", "sea_lantern", "end_rod", "candle")
STRUCTURE_WORDS = ("glass", "concrete", "bricks", "tiles", "polished_", "smooth_", "chiseled_", "cut_", "stripped_",
                   "glazed_terracotta", "quartz", "scaffolding", "ladder", "iron_bars", "chain", "cobblestone",
                   "cobbled_", "copper_block", "waxed_", "_copper")
STRUCTURE_EXACT = {"iron_block", "gold_block", "diamond_block", "emerald_block", "netherite_block", "lapis_block",
                   "hay_block", "stone_bricks", "packed_mud", "bamboo_mosaic"}
FARM_IDS = {"farmland", "hay_block", "beehive"}
TAGS = {"planks", "stairs", "slabs", "walls", "fences", "fence_gates", "doors", "trapdoors", "wool", "wool_carpets",
        "all_signs", "banners", "flower_pots", "beds", "rails", "crops", "candles"}
STRUCTURE_TAGS = {"planks", "stairs", "slabs", "walls", "fences", "fence_gates", "doors", "trapdoors", "wool",
                  "wool_carpets", "all_signs", "banners", "flower_pots"}
# Блока нет в каталоге (старый мир, чужой мод) — теги угадываем по имени
_GUESS = (("_planks", "planks"), ("_stairs", "stairs"), ("_slab", "slabs"), ("_wall", "walls"), ("_fence", "fences"),
          ("_fence_gate", "fence_gates"), ("_door", "doors"), ("_trapdoor", "trapdoors"), ("_wool", "wool"),
          ("_carpet", "wool_carpets"), ("_sign", "all_signs"), ("_banner", "banners"), ("potted_", "flower_pots"),
          ("_bed", "beds"), ("rail", "rails"))


def classify(ns, path, tags):
    """Чистое правило, как в BlockKinds.classify: пространство имён, путь id, имена тегов без «minecraft:»."""
    if f"{ns}:{path}" in NATURAL_EXCEPTIONS:
        return NATURAL
    if ns in FURNITURE_MODS:
        return FURNITURE
    if path == "nether_portal":
        return PORTAL
    if "beds" in tags:
        return BED
    if "rails" in tags:
        return RAIL
    if any(w in path for w in STORAGE_WORDS):
        return STORAGE
    if path in REDSTONE_IDS:
        return REDSTONE
    if path in MAGIC_IDS:
        return MAGIC
    if path in WORKSHOP_IDS or ns == "farmersdelight" and any(w in path for w in FD_WORKSHOP):
        return WORKSHOP
    if path in FARM_IDS or "crops" in tags:
        return FARM
    if "candles" in tags or any(w in path for w in LIGHT_WORDS) and not path.startswith("torchflower"):
        return LIGHT
    if (path in STRUCTURE_EXACT or tags & STRUCTURE_TAGS
            or any(w in path for w in STRUCTURE_WORDS) and not path.endswith("_ore")):
        return STRUCTURE
    return NATURAL


@lru_cache(maxsize=None)
def kind(name):
    """Вид блока по id: теги — из каталога, для неизвестных — по имени."""
    ns, path = split_id(name)
    if path in ("air", "cave_air", "void_air"):
        return NATURAL
    b = default().block(f"{ns}:{path}")
    if b is not None:
        tags = {t.split(":", 1)[1] for t in b.get("tags", ()) if t.startswith("minecraft:")} & TAGS
    else:
        tags = {t for suffix, t in _GUESS if suffix in path}
    return classify(ns, path, tags)


def artificial(name):
    return kind(name) != NATURAL


# Семейство материала: «stone_brick_stairs» → «stone_brick», «weathered_cut_copper» → «copper», «quartz_pillar» →
# «quartz». Для разбора стилей: ступени, плиты и отделка одного материала — один материал. Потёртость (mossy_,
# cracked_) и цвет (red_, cyan_) не снимаются — это признаки стиля.
_SHAPES = ("_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_trapdoor", "_door", "_pressure_plate", "_button",
           "_wall_sign", "_wall_hanging_sign", "_hanging_sign", "_sign", "_pane", "_carpet", "_wall_banner", "_banner",
           "_wall_torch", "_bars", "_grate", "_bulb")
_PREFIXES = ("waxed_", "stripped_", "exposed_", "weathered_", "oxidized_", "smooth_", "chiseled_", "cut_")
_WOOD = ("_planks", "_log", "_wood", "_stem", "_hyphae")


@lru_cache(maxsize=None)
def family(name):
    ns, path = split_id(name)
    for s in _SHAPES:
        if path.endswith(s):
            path = path[: -len(s)]
            break
    changed = True
    while changed:
        changed = False
        for pre in _PREFIXES:
            if path.startswith(pre) and len(path) > len(pre):
                path = path[len(pre):]
                changed = True
    for suf in _WOOD + ("_block", "_pillar"):
        if path.endswith(suf) and len(path) > len(suf):
            path = path[: -len(suf)]
            break
    if path.endswith("_bricks") or path.endswith("_tiles"):
        path = path[:-1]
    if path in ("bricks", "tiles"):
        path = path[:-1]
    if path.endswith("s") and f"{ns}:{path[:-1]}" in _FAMILY_BASES:
        path = path[:-1]
    return f"{ns}:{path}"


_FAMILY_BASES = {"minecraft:brick", "minecraft:stone_brick", "minecraft:nether_brick", "minecraft:mud_brick"}
_FAMILY_TRY = ("", "s", "_planks", "_bricks", "_block", "_tiles", "_log", "_stem", "_wool", "_concrete",
               "_terracotta", "_carpet", "_stained_glass")


def family_block(fam, full=False):
    """Семейство → блок-представитель: сам id, *_planks, *_bricks, *_wool… full — только полный куб."""
    reg = default()
    for suf in _FAMILY_TRY:
        b = reg.block(fam + suf)
        if b and (not full or b.get("full")):
            return fam + suf
    return fam

# Форма блока — для разбора деталей: сколько в постройке ступеней, плит, заборов против полных блоков
SHAPE_OF = (("_stairs", "stairs"), ("_slab", "slab"), ("_wall", "wall"), ("_fence", "fence"),
            ("_trapdoor", "trapdoor"), ("_door", "door"), ("_pane", "pane"), ("_bars", "pane"), ("_button", "tiny"),
            ("_pressure_plate", "tiny"), ("_carpet", "carpet"), ("chain", "tiny"), ("lantern", "tiny"),
            ("_sign", "tiny"), ("_banner", "tiny"), ("_rod", "tiny"), ("candle", "tiny"), ("_head", "tiny"),
            ("_skull", "tiny"), ("flower_pot", "tiny"), ("potted_", "tiny"))


@lru_cache(maxsize=None)
def shape(name):
    path = split_id(name)[1]
    for w, s in SHAPE_OF:
        if w in path:
            return s
    return "full"
