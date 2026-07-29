import sys

from . import extract_archive


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: python3 -m safezip ARCHIVE DESTINATION", file=sys.stderr)
        return 2
    try:
        extract_archive(sys.argv[1], sys.argv[2])
    except Exception as error:
        print(f"safezip: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
