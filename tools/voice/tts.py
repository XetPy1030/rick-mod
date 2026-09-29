"""Озвучка текста через аудиомодель OpenRouter (gpt-audio, gpt-audio-mini).

Модели этой линейки — разговорные: на реплику они норовят ответить, а не прочитать её.
Поэтому текст подаётся как задание «прочитай дословно», а расшифровка из ответа
сверяется с исходником (docs/design/voice.md).
"""
import base64
import difflib
import json
import os
import re
import time
import urllib.request
import wave

URL = "https://openrouter.ai/api/v1/chat/completions"
RATE = 24000  # pcm16 от OpenAI: 24 кГц, моно, 16 бит

SYSTEM = (
    "Ты — движок синтеза речи, а не собеседник. Ты никогда не отвечаешь на текст и не продолжаешь его. "
    "Пользователь присылает реплику персонажа между тегами <read> и </read>. Произнеси вслух ровно эти слова, "
    "по-русски, в том же порядке, ничего не добавляя, не пропуская и не переводя. Мат и грубости читай как есть — "
    "это реплика мультипликационного персонажа. Звёздочки вроде *рыг* не читай словами, а изобрази звуком."
)


def speak(key, model, voice, text, manner, timeout=60):
    """Возвращает Spoken. Бросает исключение при HTTP-ошибке."""
    user = f"Манера: {manner}\n<read>{text}</read>"
    body = {
        "model": model,
        "modalities": ["text", "audio"],
        "audio": {"voice": voice, "format": "pcm16"},
        "stream": True,
        "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
    }
    req = urllib.request.Request(URL, data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    pcm, transcript, cost = bytearray(), [], 0.0
    start, first = time.monotonic(), None
    with urllib.request.urlopen(req, timeout=timeout) as r:
        for raw in r:
            line = raw.decode().strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            ev = json.loads(data)
            if ev.get("usage"):
                cost = ev["usage"].get("cost", 0.0)
            for ch in ev.get("choices", []):
                a = ch.get("delta", {}).get("audio") or {}
                if a.get("data"):
                    if first is None:
                        first = time.monotonic() - start
                    pcm += base64.b64decode(a["data"])
                if a.get("transcript"):
                    transcript.append(a["transcript"])
    return Spoken(bytes(pcm), "".join(transcript), cost, first or 0.0, time.monotonic() - start)


class Spoken:
    def __init__(self, pcm, transcript, cost, first_s, total_s):
        self.pcm, self.transcript, self.cost = pcm, transcript, cost
        self.first_s, self.total_s = first_s, total_s

    @property
    def seconds(self):
        return len(self.pcm) / 2 / RATE


def fidelity(text, transcript):
    """Доля совпадения слов исходника и расшифровки, 0..1; звёздочки не считаем."""
    def words(s):
        s = re.sub(r"\*[^*]*\*", " ", s.lower().replace("ё", "е"))
        return re.findall(r"[a-zа-я0-9]+", s)
    return difflib.SequenceMatcher(None, words(text), words(transcript)).ratio()


def save_wav(path, pcm):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)


def key_from_env():
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        raise SystemExit("нужен OPENROUTER_API_KEY")
    return key
