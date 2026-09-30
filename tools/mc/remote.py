"""Удалённый файл как локальный: чтение по HTTP Range с упреждением.

Так zipfile читает архив мира на 2 ГБ, не скачивая его: оглавление с конца, потом нужные регионы.
Сервер обязан отвечать 206 на Range — Hermitcraft, MediaFire, Modrinth умеют.
"""
import io
import re
import time
import urllib.request

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) rikoshet-refs/1.0 (+github.com/XetPy1030)"


def _open(url, start=None, end=None, tries=4):
    headers = {"User-Agent": UA}
    if start is not None:
        headers["Range"] = f"bytes={start}-{end}"
    for k in range(tries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60)
        except OSError:
            if k == tries - 1:
                raise
            time.sleep(2 ** k)


def fetch(url, path, progress=None):
    """Скачать целиком (небольшие jar)."""
    with _open(url) as r, open(path, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
            if progress:
                progress(f.tell())


def mediafire(url):
    """Страница MediaFire → прямая ссылка (она временная — получать заново перед каждым сканом)."""
    with _open(url) as r:
        page = r.read().decode("utf-8", "replace")
    m = re.search(r'href="(https://download\d*\.mediafire\.com/[^"]+)"', page)
    if not m:
        raise OSError(f"MediaFire не отдал прямую ссылку: {url}")
    return m.group(1)


def resolve(url):
    return mediafire(url[len("mediafire:"):]) if url.startswith("mediafire:") else url


class RangeFile(io.RawIOBase):
    """Файл по HTTP с упреждающим чтением блоками. Счётчик `fetched` — сколько байт реально пришло."""

    def __init__(self, url, block=8 << 20):
        self.url = url
        self.block = block
        with _open(url, 0, 0) as r:
            if r.status != 206:
                raise OSError(f"сервер не отдаёт части файла (HTTP {r.status}): {url}")
            self.size = int(r.headers["Content-Range"].rsplit("/", 1)[1])
        self.pos = 0
        self.buf_start = 0
        self.buf = b""
        self.fetched = 0

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
        return self.pos

    def _fill(self, n):
        want = max(n, self.block)
        end = min(self.size, self.pos + want) - 1
        with _open(self.url, self.pos, end) as r:
            self.buf = r.read()
        self.buf_start = self.pos
        self.fetched += len(self.buf)

    def prefetch(self, start, length):
        """Забрать ровно этот кусок — когда известно, что читать дальше (член zip по оглавлению)."""
        rel = start - self.buf_start
        if 0 <= rel and rel + length <= len(self.buf):
            return
        end = min(self.size, start + length) - 1
        with _open(self.url, start, end) as r:
            self.buf = r.read()
        self.buf_start = start
        self.fetched += len(self.buf)

    def read(self, n=-1):
        if n is None or n < 0:
            n = self.size - self.pos
        if n == 0 or self.pos >= self.size:
            return b""
        rel = self.pos - self.buf_start
        if not (0 <= rel and rel + n <= len(self.buf)):
            # Оглавление zip читается мелкими кусками с конца — там блок поменьше
            if self.size - self.pos < self.block:
                self.pos, keep = max(0, self.size - self.block), self.pos
                self._fill(self.size - self.pos)
                self.pos = keep
            else:
                self._fill(n)
            rel = self.pos - self.buf_start
        out = self.buf[rel:rel + n]
        self.pos += len(out)
        return out

    def readinto(self, b):
        data = self.read(len(b))
        b[:len(data)] = data
        return len(data)
