import sys

from notesdb import open_database


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] != "migrate":
        print("usage: python3 manage.py migrate DATABASE", file=sys.stderr)
        return 2
    try:
        connection = open_database(sys.argv[2])
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        connection.close()
    except Exception as error:
        print(f"migration failed: {error}", file=sys.stderr)
        return 1
    print(f"schema version {version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
