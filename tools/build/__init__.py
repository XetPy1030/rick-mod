"""Генератор построек (docs/architecture/visual-pipeline.md#генератор-построек-toolsbuild).

    python3 tools/build citadel/lab          # tools/build/scenes/citadel/lab.py → NBT, лист рендера, отчёт линтера
    python3 tools/build demo/showcase --no-lint

Сцена — модуль в scenes/ с функцией build() → Scene. Формы — shape, кисти — brush, состояния — states.
"""
from . import brush, shape, states  # noqa: F401
from .scene import Scene  # noqa: F401
