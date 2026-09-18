import os
import sys
import tempfile
from pathlib import Path


root = Path(os.environ.get("TMPDIR") or tempfile.gettempdir())
marker = root / "haifa-transient-probe.marker"
if not marker.exists():
    marker.write_text("retry", encoding="utf-8")
    print("temporary unavailable", file=sys.stderr)
    raise SystemExit(75)
marker.unlink()
print("probe ready")
