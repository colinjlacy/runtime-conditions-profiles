from __future__ import annotations

import sys
from pathlib import Path

PYTHON_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PYTHON_ROOT))

from runtimeconditions.profiler import main


if __name__ == "__main__":
    raise SystemExit(main())
