# tools/skins

Скины NPC для ресурспака мода: PNG 64×64 в `src/main/resources/assets/rikoshet/textures/entity/npc/<id>.png`. Манекен берёт скин оттуда по `profile.texture` = `rikoshet:entity/npc/<id>` ([скины](../../docs/architecture/server-integration.md#скины)).

```sh
python3 tools/skins/rick.py    # нужен Pillow
```

- `skinlib.py` — раскладка 64×64 (1.8+), цвета, рисование гранями по строкам. По образцу генератора Джерри из сборки сервера (`~/common/projects/mc-fabric-26.2/skins/jerry-prime.py`).
- `rick.py` — Рик, простая версия этапа 3. Детальная — этап 3.5.

Новый персонаж — новый скрипт `<id>.py`, вызывающий `Skin().save("<id>")`.
