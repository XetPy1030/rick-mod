"""Общая библиотека визуального конвейера: NBT, каталог блоков, ассеты, модели, рендер.

docs/architecture/visual-pipeline.md
"""
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
ROOT = TOOLS.parent
OUT = ROOT / "run" / "visual"
