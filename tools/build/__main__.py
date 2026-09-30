"""python3 tools/build <сцена> [--no-render] [--no-lint] — собрать сцену из tools/build/scenes/."""
import argparse
import importlib.util
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import build  # noqa: E402,F401 — пакет генератора для сцен


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("scene", help="путь в tools/build/scenes без .py: citadel/lab")
    ap.add_argument("--no-render", action="store_true")
    ap.add_argument("--no-lint", action="store_true")
    args = ap.parse_args()
    path = HERE / "scenes" / f"{args.scene}.py"
    if not path.exists():
        sys.exit(f"нет сцены {path}")
    spec = importlib.util.spec_from_file_location(f"scenes.{args.scene.replace('/', '.')}", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    t0 = time.time()
    scene = mod.build()
    print(f"сцена собрана за {time.time() - t0:.1f} с")
    scene.save(render=not args.no_render, lint=not args.no_lint)


if __name__ == "__main__":
    main()
