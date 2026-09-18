import json
import sys

from .summary import analyze_csv


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: python3 -m expense_report TRANSACTIONS.csv", file=sys.stderr)
        return 2
    try:
        report = analyze_csv(sys.argv[1])
    except OSError as error:
        print(f"expense-report: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
