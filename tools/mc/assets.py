"""Ассеты слоями, как у клиента: наш пак → jar модов сборки → клиент Minecraft 26.2.

Клиентский jar берётся из кеша Loom (есть после любой сборки мода), моды — из сборки сервера.
Пути меняются переменными RIKOSHET_MC_CLIENT и RIKOSHET_MODS. Сами jar и текстуры Mojang в git не кладём.
"""
import io
import json
import os
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

from . import ROOT

CLIENT_JAR = Path(os.environ.get("RIKOSHET_MC_CLIENT", Path.home() / ".gradle/caches/fabric-loom/26.2/minecraft-client.jar"))
# Нет кеша Loom (чистили ~/.gradle) — клиент качается по манифесту Mojang сюда, со сверкой sha1
CLIENT_CACHE = ROOT / "run" / "visual" / "cache" / "minecraft-26.2-client.jar"
MC_VERSION = "26.2"
VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def ensure_client(path=CLIENT_JAR):
    """Клиентский jar: из кеша Loom, из своего кеша или скачать у Mojang (~30 МБ)."""
    if Path(path).exists():
        return Path(path)
    if CLIENT_CACHE.exists():
        return CLIENT_CACHE
    import hashlib
    import urllib.request
    get = lambda u: urllib.request.urlopen(u, timeout=60).read()  # noqa: E731
    versions = json.loads(get(VERSION_MANIFEST))["versions"]
    meta = json.loads(get(next(v["url"] for v in versions if v["id"] == MC_VERSION)))
    dl = meta["downloads"]["client"]
    data = get(dl["url"])
    if hashlib.sha1(data).hexdigest() != dl["sha1"]:
        raise OSError("клиент Minecraft скачался с неверным sha1")
    CLIENT_CACHE.parent.mkdir(parents=True, exist_ok=True)
    CLIENT_CACHE.write_bytes(data)
    print(f"клиент Minecraft {MC_VERSION} скачан у Mojang → {CLIENT_CACHE}")
    return CLIENT_CACHE
MODS_DIR = Path(os.environ.get("RIKOSHET_MODS", Path.home() / "common/projects/mc-fabric-26.2/server/mods"))
PACK_DIRS = [ROOT / "src/main/resources"]


class Texture:
    """Первый кадр текстуры: rgba — float32 H×W×4 от 0 до 1. mode — как её рисует игра."""
    __slots__ = ("id", "rgba", "w", "h", "frames", "mode")

    def __init__(self, tex_id, img, frames=1):
        self.id = tex_id
        self.rgba = np.asarray(img, dtype=np.float32) / 255.0
        self.h, self.w = self.rgba.shape[:2]
        self.frames = frames
        a = self.rgba[..., 3]
        partial = np.count_nonzero((a > 0.04) & (a < 0.96))
        if partial > a.size * 0.01:
            self.mode = "translucent"
        elif np.any(a < 0.5):
            self.mode = "cutout"
        else:
            self.mode = "opaque"

    def avg(self):
        a = self.rgba[..., 3:4]
        s = float(a.sum())
        return tuple((self.rgba[..., :3] * a).sum(axis=(0, 1)) / s) if s else (0.0, 0.0, 0.0)


def _missing(tex_id):
    """Как у игры: пурпурно-чёрная шахматка — сразу видно, что текстуры нет."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    px = img.load()
    for y in range(16):
        for x in range(16):
            if (x < 8) != (y < 8):
                px[x, y] = (248, 0, 248, 255)
    return Texture(tex_id, img)


class _Dir:
    def __init__(self, root):
        self.root = Path(root)
        self.name = str(root)

    def names(self):
        base = self.root / "assets"
        if not base.is_dir():
            return []
        return [str(p.relative_to(self.root)).replace(os.sep, "/") for p in base.rglob("*") if p.is_file()]

    def read(self, path):
        return (self.root / path).read_bytes()


class _Zip:
    def __init__(self, source, name):
        self.zip = zipfile.ZipFile(source)
        self.name = name

    def names(self):
        return [n for n in self.zip.namelist() if n.startswith("assets/") and not n.endswith("/")]

    def read(self, path):
        return self.zip.read(path)

    def nested(self):
        """Вложенные jar (jar-in-jar): у некоторых модов ассеты лежат в библиотеках."""
        for n in self.zip.namelist():
            if n.startswith("META-INF/jars/") and n.endswith(".jar"):
                yield _Zip(io.BytesIO(self.zip.read(n)), f"{self.name}!{n}")


class Assets:
    def __init__(self, pack_dirs=None, mods_dir=MODS_DIR, client_jar=CLIENT_JAR, extra_jars=()):
        """extra_jars — после модов сборки: скачанные для образцов моды со своими блоками."""
        client_jar = ensure_client(client_jar)
        layers = [_Dir(d) for d in (pack_dirs or PACK_DIRS)]
        if mods_dir and Path(mods_dir).is_dir():
            for jar in sorted(Path(mods_dir).glob("*.jar")):
                z = _Zip(jar, jar.name)
                layers.append(z)
                layers.extend(z.nested())
        for jar in extra_jars:
            layers.append(_Zip(jar, Path(jar).name))
        layers.append(_Zip(client_jar, Path(client_jar).name))
        self.layers = layers
        self._index = {}
        for layer in layers:
            for n in layer.names():
                self._index.setdefault(n, layer)
        self._json = {}
        self._tex = {}

    def exists(self, path):
        return path in self._index

    def read(self, path):
        layer = self._index.get(path)
        return layer.read(path) if layer else None

    def source(self, path):
        layer = self._index.get(path)
        return layer.name if layer else None

    def json(self, path):
        if path not in self._json:
            data = self.read(path)
            self._json[path] = json.loads(data.decode("utf-8")) if data is not None else None
        return self._json[path]

    @staticmethod
    def _split(res_id):
        ns, _, p = res_id.rpartition(":")
        return (ns or "minecraft"), p

    def blockstate(self, block_id):
        ns, p = self._split(block_id)
        return self.json(f"assets/{ns}/blockstates/{p}.json")

    def model(self, model_id):
        ns, p = self._split(model_id)
        return self.json(f"assets/{ns}/models/{p}.json")

    def item_def(self, item_id):
        """Описание предмета 26.2: assets/<ns>/items/<id>.json."""
        ns, p = self._split(item_id)
        return self.json(f"assets/{ns}/items/{p}.json")

    def texture(self, tex_id):
        """Текстура по id («minecraft:block/stone»); нет — шахматка. Анимированная — первый кадр."""
        if tex_id in self._tex:
            return self._tex[tex_id]
        ns, p = self._split(tex_id)
        path = f"assets/{ns}/textures/{p}.png"
        data = self.read(path)
        if data is None:
            tex = _missing(tex_id)
        else:
            img = Image.open(io.BytesIO(data)).convert("RGBA")
            w, h = img.size
            frames = 1
            meta = self.json(path + ".mcmeta")
            anim = (meta or {}).get("animation")
            if anim is not None or (h > w and h % w == 0):
                fw = (anim or {}).get("width", w)
                fh = (anim or {}).get("height", fw if h > w else h)
                frames = max(1, h // fh)
                img = img.crop((0, 0, fw, fh))
            tex = Texture(tex_id, img, frames)
        self._tex[tex_id] = tex
        return tex

    def image(self, path):
        data = self.read(path)
        return Image.open(io.BytesIO(data)).convert("RGBA") if data is not None else None
