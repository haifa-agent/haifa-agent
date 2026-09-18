import json
import sys

from catalog import search


def main() -> int:
    if len(sys.argv) != 2:
        return 2
    items = json.load(sys.stdin)
    print(json.dumps(search(items, sys.argv[1]), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
