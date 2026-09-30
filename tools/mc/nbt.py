"""Минимальная запись и чтение NBT (big-endian) без внешних библиотек: структуры, схематики, регионы.

Типы Python → NBT: Byte/Short/Int/Long/Float/Double — обёртки ниже, str → String,
dict → Compound, list → List (тип по первому элементу), bytes → ByteArray, IntArray/LongArray.
При чтении IntArray и LongArray приходят массивами numpy — в схематиках и регионах они большие.
"""
import gzip
import struct
import zlib

import numpy as np


class Byte(int):
    pass


class Short(int):
    pass


class Int(int):
    pass


class Long(int):
    pass


class Float(float):
    pass


class Double(float):
    pass


class IntArray(list):
    pass


class LongArray(list):
    pass


END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


def _tag(v):
    if isinstance(v, Byte) or isinstance(v, bool):
        return BYTE
    if isinstance(v, Short):
        return SHORT
    if isinstance(v, Long):
        return LONG
    if isinstance(v, Float):
        return FLOAT
    if isinstance(v, Double):
        return DOUBLE
    if isinstance(v, IntArray):
        return INT_ARRAY
    if isinstance(v, LongArray):
        return LONG_ARRAY
    if isinstance(v, int):
        return INT
    if isinstance(v, float):
        return DOUBLE
    if isinstance(v, str):
        return STRING
    if isinstance(v, bytes):
        return BYTE_ARRAY
    if isinstance(v, list):
        return LIST
    if isinstance(v, dict):
        return COMPOUND
    raise TypeError(type(v))


def _str(s):
    b = s.encode("utf-8")  # у NBT «модифицированный UTF-8», для BMP без нулевого символа совпадает
    return struct.pack(">H", len(b)) + b


def _payload(t, v):
    if t == BYTE:
        return struct.pack(">b", int(v))
    if t == SHORT:
        return struct.pack(">h", v)
    if t == INT:
        return struct.pack(">i", v)
    if t == LONG:
        return struct.pack(">q", v)
    if t == FLOAT:
        return struct.pack(">f", v)
    if t == DOUBLE:
        return struct.pack(">d", v)
    if t == STRING:
        return _str(v)
    if t == BYTE_ARRAY:
        return struct.pack(">i", len(v)) + v
    if t == INT_ARRAY:
        return struct.pack(">i", len(v)) + b"".join(struct.pack(">i", x) for x in v)
    if t == LONG_ARRAY:
        return struct.pack(">i", len(v)) + b"".join(struct.pack(">q", x) for x in v)
    if t == LIST:
        et = _tag(v[0]) if v else END
        return struct.pack(">bi", et, len(v)) + b"".join(_payload(et, x) for x in v)
    if t == COMPOUND:
        out = b""
        for k, x in v.items():
            xt = _tag(x)
            out += struct.pack(">b", xt) + _str(k) + _payload(xt, x)
        return out + struct.pack(">b", END)
    raise TypeError(t)


def write(path, root):
    data = struct.pack(">b", COMPOUND) + _str("") + _payload(COMPOUND, root)
    # mtime=0 — одинаковый файл при одинаковой постройке, без лишних диффов в git
    with open(path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as f:
        f.write(data)


def read(path):
    """Файл NBT: сжатый gzip (структуры, .schem, .litematic) или нет."""
    with open(path, "rb") as f:
        return read_bytes(f.read())


def read_bytes(data):
    """NBT из байтов: gzip, zlib (чанки регионов) или без сжатия."""
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    elif data[:1] == b"\x78":
        data = zlib.decompress(data)
    pos = [0]

    def take(fmt):
        size = struct.calcsize(fmt)
        v = struct.unpack_from(fmt, data, pos[0])
        pos[0] += size
        return v[0] if len(v) == 1 else v

    def rstr():
        n = take(">H")
        s = data[pos[0]:pos[0] + n].decode("utf-8", "replace")
        pos[0] += n
        return s

    def payload(t):
        if t == BYTE:
            return take(">b")
        if t == SHORT:
            return take(">h")
        if t == INT:
            return take(">i")
        if t == LONG:
            return take(">q")
        if t == FLOAT:
            return take(">f")
        if t == DOUBLE:
            return take(">d")
        if t == BYTE_ARRAY:
            n = take(">i")
            b = data[pos[0]:pos[0] + n]
            pos[0] += n
            return b
        if t == STRING:
            return rstr()
        if t == LIST:
            et, n = take(">bi")
            return [payload(et) for _ in range(n)]
        if t == COMPOUND:
            out = {}
            while True:
                xt = take(">b")
                if xt == END:
                    return out
                k = rstr()
                out[k] = payload(xt)
        if t == INT_ARRAY:
            n = take(">i")
            a = np.frombuffer(data, ">i4", n, pos[0]).astype(np.int32)
            pos[0] += 4 * n
            return a
        if t == LONG_ARRAY:
            n = take(">i")
            a = np.frombuffer(data, ">i8", n, pos[0]).astype(np.int64)
            pos[0] += 8 * n
            return a
        raise ValueError(t)

    t = take(">b")
    rstr()
    return payload(t)
