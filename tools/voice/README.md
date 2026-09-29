# tools/voice

Озвучка реплик аудиомоделями OpenRouter (`openai/gpt-audio-mini`, `openai/gpt-audio`) — образцы для выбора голоса ([voice](../../docs/design/voice.md#образцы-2909)).

```sh
OPENROUTER_API_KEY=… python3 tools/voice/samples.py [--models openai/gpt-audio-mini] [--voices ash,echo]
```

Пишет в `tools/voice/out/` (в git не идёт): WAV на каждую реплику, `rick_<модель>_<голос>.wav` — все реплики подряд, `report.md` — совпадение с текстом, длительность, задержка до первого звука, цена.

`tts.py` — запрос потоком (`pcm16`, 24 кГц) и сверка расшифровки с текстом: модели разговорные и иногда отвечают вместо того, чтобы прочитать.
