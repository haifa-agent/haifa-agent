import sys
from pathlib import Path


def cache_round_trip(value: str) -> str:
    root = Path("/tmp") / "haifa-python-cache"
    root.mkdir(parents=True, exist_ok=True)
    entry = root / "entry.txt"
    entry.write_text(value, encoding="utf-8")
    return entry.read_text(encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 2 or not sys.argv[1]:
        return 2
    print(cache_round_trip(sys.argv[1]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
